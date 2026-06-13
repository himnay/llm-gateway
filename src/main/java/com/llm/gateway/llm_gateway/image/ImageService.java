package com.llm.gateway.llm_gateway.image;

import com.llm.gateway.llm_gateway.dto.ImageGenerationRequest;
import com.llm.gateway.llm_gateway.dto.ImageGenerationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.openai.OpenAiImageOptions;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Image generation backed by the OpenAI image model (DALL·E), auto-configured by the Spring AI
 * OpenAI starter. Blocking by design — the reactive {@code ImageHandler} offloads it to the
 * bounded-elastic scheduler.
 *
 * <p>Never throws: failures are captured in {@link ImageGenerationResponse#getError()} so the
 * handler can return a clean JSON error envelope, mirroring the text {@code LlmServiceProvider}
 * contract.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageService {

    private static final String DEFAULT_MODEL = "dall-e-3";
    private static final String DEFAULT_SIZE = "1024x1024";
    private static final String DEFAULT_FORMAT = "b64_json";

    private final ImageModel imageModel;

    public ImageGenerationResponse generate(ImageGenerationRequest request) {
        long start = System.currentTimeMillis();
        String model = blankTo(request.getModel(), DEFAULT_MODEL);
        String format = blankTo(request.getResponseFormat(), DEFAULT_FORMAT);
        int count = request.getN() == null || request.getN() < 1 ? 1 : request.getN();
        int[] dims = parseSize(blankTo(request.getSize(), DEFAULT_SIZE));

        try {
            OpenAiImageOptions options = OpenAiImageOptions.builder()
                    .model(model)
                    .n(count)
                    .width(dims[0])
                    .height(dims[1])
                    .responseFormat(format)
                    .build();

            ImageResponse response = imageModel.call(new ImagePrompt(request.getPrompt(), options));

            List<String> images = response.getResults().stream()
                    .map(result -> "url".equalsIgnoreCase(format)
                            ? result.getOutput().getUrl()
                            : result.getOutput().getB64Json())
                    .toList();

            log.info("IMAGE | generated | model={} | count={} | cid={}", model, images.size(), request.getCorrelationId());
            return ImageGenerationResponse.builder()
                    .model(model)
                    .responseFormat(format)
                    .images(images)
                    .count(images.size())
                    .latencyMs(System.currentTimeMillis() - start)
                    .timestamp(System.currentTimeMillis())
                    .correlationId(request.getCorrelationId())
                    .build();
        } catch (Exception ex) {
            log.error("IMAGE | generation failed | model={} | cid={} | error={}",
                    model, request.getCorrelationId(), ex.getMessage());
            return ImageGenerationResponse.builder()
                    .model(model)
                    .error(ex.getMessage())
                    .latencyMs(System.currentTimeMillis() - start)
                    .timestamp(System.currentTimeMillis())
                    .correlationId(request.getCorrelationId())
                    .build();
        }
    }

    private static int[] parseSize(String size) {
        try {
            String[] parts = size.toLowerCase().split("x");
            return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        } catch (RuntimeException ex) {
            return new int[]{1024, 1024};
        }
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
