package com.aes.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzh.BgeSmallZhEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class AesConfig {

    private static final Logger log = LoggerFactory.getLogger(AesConfig.class);

    @Value("${deepseek.api.key}")
    private String apiKey;

    @Value("${deepseek.base.url}")
    private String baseUrl;

    @Value("${deepseek.model.name}")
    private String modelName;

    @Value("${chroma.base.url:http://localhost:8000}")
    private String chromaBaseUrl;

    @Value("${chroma.collection.name:aes-knowledge}")
    private String chromaCollectionName;

    @Bean
    public ChatLanguageModel chatModel() {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.3)
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    @Bean
    public StreamingChatLanguageModel streamingChatModel() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.3)
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        return new BgeSmallZhEmbeddingModel();
    }

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        // 优先尝试 Chroma 本地向量库，不可用时自动回退到内存向量库
        try {
            log.info("尝试连接 Chroma 向量库: {}", chromaBaseUrl);
            ChromaEmbeddingStore store = ChromaEmbeddingStore.builder()
                    .baseUrl(chromaBaseUrl)
                    .collectionName(chromaCollectionName)
                    .build();
            log.info("Chroma 向量库连接成功, collection: {}", chromaCollectionName);
            return store;
        } catch (Exception e) {
            log.warn("Chroma 连接失败 ({}), 回退到 InMemoryEmbeddingStore", e.getMessage());
            log.warn("如需使用 Chroma，请先在另一个终端执行: chroma run --path ./chroma-data");
            return new InMemoryEmbeddingStore<>();
        }
    }
}
