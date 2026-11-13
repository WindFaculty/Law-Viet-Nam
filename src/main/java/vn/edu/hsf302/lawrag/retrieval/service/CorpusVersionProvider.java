package vn.edu.hsf302.lawrag.retrieval.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Corpus version shown in every retrieval trace. It is read from the same
 * manifest the importer uses, so a trace can be matched to its corpus.
 */
@Component
public class CorpusVersionProvider {

    private static final String MANIFEST_LOCATION = "classpath:corpus/manifest.json";

    private final String corpusVersion;

    public CorpusVersionProvider(ObjectMapper objectMapper, ResourceLoader resourceLoader) {
        Resource resource = resourceLoader.getResource(MANIFEST_LOCATION);
        try (var input = resource.getInputStream()) {
            JsonNode manifest = objectMapper.readTree(input);
            String version = manifest.path("corpusVersion").asText("");
            if (version.isBlank()) {
                throw new IllegalStateException("corpus manifest has no corpusVersion");
            }
            this.corpusVersion = version;
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read corpus manifest", exception);
        }
    }

    public String corpusVersion() {
        return corpusVersion;
    }
}
