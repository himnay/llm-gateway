package com.llm.gateway.llm_gateway.cache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class RedisChatMemoryRepository implements ChatMemoryRepository {

  private static final String KEY_PREFIX = "chat:history:";

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;

  @Value("${llm.chat.memory.ttl-hours:24}")
  private long ttlHours;

  record MessageEntry(String type, String content) {}

  /**
   * Lists all conversation IDs using SCAN instead of KEYS to avoid blocking Redis. SCAN is O(1) per
   * call and safe for production use even with millions of keys.
   */
  @Override
  public List<String> findConversationIds() {
    List<String> ids = new ArrayList<>();
    ScanOptions opts = ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(100).build();
    try {
      redisTemplate.execute(
          (RedisConnection conn) -> {
            try (Cursor<byte[]> cursor = conn.keyCommands().scan(opts)) {
              cursor.forEachRemaining(
                  key -> {
                    String keyStr = new String(key);
                    ids.add(keyStr.substring(KEY_PREFIX.length()));
                  });
            }
            return null;
          });
    } catch (Exception e) {
      log.warn("CHAT_MEMORY | scan error | error={}", e.getMessage());
    }
    return ids;
  }

  @Override
  public List<Message> findByConversationId(String conversationId) {
    try {
      String json = redisTemplate.opsForValue().get(KEY_PREFIX + conversationId);
      if (json == null || json.isBlank()) return List.of();
      redisTemplate.expire(KEY_PREFIX + conversationId, Duration.ofHours(ttlHours));
      List<MessageEntry> entries =
          objectMapper.readValue(
              json,
              objectMapper
                  .getTypeFactory()
                  .constructCollectionType(List.class, MessageEntry.class));
      return entries.stream().map(this::toMessage).toList();
    } catch (Exception e) {
      log.warn(
          "CHAT_MEMORY | Read error | conversationId={} | error={}",
          conversationId,
          e.getMessage());
      return List.of();
    }
  }

  @Override
  public void saveAll(String conversationId, List<Message> messages) {
    try {
      List<MessageEntry> entries =
          messages.stream()
              .map(m -> new MessageEntry(m.getMessageType().getValue(), m.getText()))
              .toList();
      String json = objectMapper.writeValueAsString(entries);
      redisTemplate
          .opsForValue()
          .set(KEY_PREFIX + conversationId, json, Duration.ofHours(ttlHours));
      log.debug(
          "CHAT_MEMORY | Saved {} messages | conversationId={}", messages.size(), conversationId);
    } catch (Exception e) {
      log.warn(
          "CHAT_MEMORY | Write error | conversationId={} | error={}",
          conversationId,
          e.getMessage());
    }
  }

  @Override
  public void deleteByConversationId(String conversationId) {
    redisTemplate.delete(KEY_PREFIX + conversationId);
    log.debug("CHAT_MEMORY | Deleted | conversationId={}", conversationId);
  }

  private Message toMessage(MessageEntry entry) {
    return switch (entry.type()) {
      case "assistant" -> new AssistantMessage(entry.content());
      case "system" -> new SystemMessage(entry.content());
      default -> new UserMessage(entry.content());
    };
  }
}
