package vn.edu.hsf302.lawrag.gemini.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import vn.edu.hsf302.lawrag.legaldata.service.LegalText;

import java.nio.ByteBuffer;
import java.util.Random;
import vn.edu.hsf302.lawrag.gemini.config.GeminiProperties;

/**
 * Offline/CI embedding double. The vectors are deterministic across JVMs and
 * use the configured embedding space id so idempotent imports can key on
 * (model, space, chunk, text hash) exactly like the real client.
 */
@Component
@ConditionalOnProperty(prefix = "app.gemini", name = "enabled", havingValue = "false", matchIfMissing = true)
public class DeterministicEmbeddingClient implements EmbeddingClient {

    private final GeminiProperties properties;

    public DeterministicEmbeddingClient(GeminiProperties properties) {
        this.properties = properties;
    }

    @Override
    public String spaceId() {
        // The configured space is the contract used by retrieval; this double
        // only replaces the provider call, not the logical vector space.
        return properties.embeddingSpaceId();
    }

    @Override
    public Embedding embed(String text, EmbeddingTaskType taskType) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("embedding text must not be blank");
        }
        String hash = LegalText.sha256Hex(taskType.name() + "\n" + text);
        long seed = ByteBuffer.wrap(hexToBytes(hash)).getLong();
        Random random = new Random(seed);
        float[] values = new float[properties.embeddingDimensions()];
        double sumSquares = 0;
        for (int index = 0; index < values.length; index++) {
            double value = random.nextDouble() * 2 - 1;
            values[index] = (float) value;
            sumSquares += value * value;
        }
        double norm = Math.sqrt(sumSquares);
        if (norm > 0) {
            for (int index = 0; index < values.length; index++) {
                values[index] = (float) (values[index] / norm);
            }
        }
        return new Embedding(spaceId(), values.length, values);
    }

    private static byte[] hexToBytes(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) Integer.parseInt(hex.substring(index * 2, index * 2 + 2), 16);
        }
        return bytes;
    }
}
