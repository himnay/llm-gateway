package com.llm.gateway.llm_gateway.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class SemanticPromptCacheTest {

  private StringRedisTemplate redisTemplate;
  private HashOperations<String, Object, Object> hashOps;
  private EmbeddingModel embeddingModel;
  private SemanticPromptCache cache;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    redisTemplate = mock(StringRedisTemplate.class);
    hashOps = mock(HashOperations.class);
    ZSetOperations<String, String> zSetOps = mock(ZSetOperations.class);
    when(redisTemplate.opsForHash()).thenReturn(hashOps);
    when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
    embeddingModel = mock(EmbeddingModel.class);

    ObjectProvider<EmbeddingModel> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(embeddingModel);

    cache = new SemanticPromptCache(redisTemplate, provider, objectMapper);
    ReflectionTestUtils.setField(cache, "enabled", true);
    ReflectionTestUtils.setField(cache, "similarityThreshold", 0.95);
    ReflectionTestUtils.setField(cache, "maxEntries", 256L);
    ReflectionTestUtils.setField(cache, "indexTtlMinutes", 120L);
    ReflectionTestUtils.setField(cache, "keyPrefix", "llm:cache");
  }

  @Test
  @DisplayName("disabled cache returns empty and never touches Redis or the embedding model")
  void disabledCacheDoesNothing() {
    ReflectionTestUtils.setField(cache, "enabled", false);

    assertThat(cache.findSimilarKey("openai", "gpt-4o", "prompt")).isEmpty();
    verifyNoInteractions(redisTemplate, embeddingModel);
  }

  @Test
  @DisplayName(
      "returns the key of the most similar cached prompt when its similarity exceeds the threshold")
  void returnsKeyOfMostSimilarPromptAboveThreshold() throws Exception {
    when(embeddingModel.embed(anyString())).thenReturn(new float[] {1.0f, 0.0f});
    when(hashOps.entries("llm:cache:sem:openai:gpt-4o:vec"))
        .thenReturn(
            Map.of(
                "key-identical", objectMapper.writeValueAsString(new float[] {0.99f, 0.01f}),
                "key-orthogonal", objectMapper.writeValueAsString(new float[] {0.0f, 1.0f})));

    Optional<String> result = cache.findSimilarKey("openai", "gpt-4o", "summarize the report");

    assertThat(result).contains("key-identical");
  }

  @Test
  @DisplayName("returns empty when no cached entry's similarity clears the threshold")
  void returnsEmptyWhenNothingClearsThreshold() throws Exception {
    when(embeddingModel.embed(anyString())).thenReturn(new float[] {1.0f, 0.0f});
    when(hashOps.entries(anyString()))
        .thenReturn(
            Map.of("key-different", objectMapper.writeValueAsString(new float[] {0.5f, 0.87f})));

    assertThat(cache.findSimilarKey("openai", "gpt-4o", "prompt")).isEmpty();
  }

  @Test
  @DisplayName("returns empty when the semantic index has no entries")
  void returnsEmptyOnEmptyIndex() {
    when(embeddingModel.embed(anyString())).thenReturn(new float[] {1.0f, 0.0f});
    when(hashOps.entries(anyString())).thenReturn(Map.of());

    assertThat(cache.findSimilarKey("openai", "gpt-4o", "prompt")).isEmpty();
  }

  @Test
  @DisplayName("swallows embedding model failures and returns empty instead of throwing")
  void embeddingFailureIsSwallowed() {
    when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("provider down"));

    assertThat(cache.findSimilarKey("openai", "gpt-4o", "prompt")).isEmpty();
  }

  @Test
  @DisplayName(
      "cosine() handles edge cases such as null vectors, mismatched lengths, and zero vectors")
  void cosineHandlesEdgeCases() {
    assertThat(SemanticPromptCache.cosine(new float[] {1, 0}, new float[] {1, 0})).isEqualTo(1.0);
    assertThat(SemanticPromptCache.cosine(new float[] {1, 0}, new float[] {0, 1})).isEqualTo(0.0);
    assertThat(SemanticPromptCache.cosine(null, new float[] {1})).isEqualTo(-1);
    assertThat(SemanticPromptCache.cosine(new float[] {1}, new float[] {1, 2})).isEqualTo(-1);
    assertThat(SemanticPromptCache.cosine(new float[] {0, 0}, new float[] {0, 0})).isEqualTo(-1);
  }
}
