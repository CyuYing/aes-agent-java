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
import org.springframework.context.annotation.Primary;

import java.time.Duration;

@Configuration
public class AesConfig {

    private static final Logger log = LoggerFactory.getLogger(AesConfig.class);

    @Value("${deepseek.api.key:${DEEPSEEK_API_KEY:not-configured}}")
    private String deepseekApiKey;

    @Value("${deepseek.base.url:${DEEPSEEK_BASE_URL:https://api.deepseek.com}}")
    private String deepseekBaseUrl;

    @Value("${deepseek.model.name:${DEEPSEEK_MODEL:deepseek-v4-flash}}")
    private String deepseekModelName;

    @Value("${grading.api.key:${GRADING_API_KEY:}}")
    private String gradingApiKey;

    @Value("${grading.base.url:${GRADING_BASE_URL:}}")
    private String gradingBaseUrl;

    @Value("${grading.model.name:${GRADING_MODEL:}}")
    private String gradingModelName;

    @Value("${chroma.base.url:http://localhost:8000}")
    private String chromaBaseUrl;

    @Value("${chroma.java.collection.name:${chroma.collection.name:aes-java-knowledge}}")
    private String chromaCollectionName;

    @Value("${chroma.database.collection.name:aes-database-knowledge}")
    private String databaseChromaCollectionName;

    @Value("${chroma.enabled:true}")
    private boolean chromaEnabled;

    @Value("${vision.api.key:disabled}")
    private String visionApiKey;

    @Value("${vision.base.url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String visionBaseUrl;

    @Value("${vision.model.name:qwen3.7-plus}")
    private String visionModelName;

    @Bean
    @Primary
    public ChatLanguageModel chatModel() {
        return OpenAiChatModel.builder()
                .baseUrl(effectiveBaseUrl())
                .apiKey(effectiveApiKey())
                .modelName(effectiveModelName())
                .temperature(0.3)
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    /**
     * 独立的 OpenAI 兼容视觉模型。DeepSeek 文本模型不支持图片输入时，
     * 可通过 VISION_* 环境变量接入任意支持图片的兼容服务。
     */
    @Bean("visionChatModel")
    public ChatLanguageModel visionChatModel() {
        return OpenAiChatModel.builder()
                .baseUrl(visionBaseUrl)
                .apiKey(visionApiKey)
                .modelName(visionModelName)
                .temperature(0.1)
                .timeout(Duration.ofSeconds(60))
                .maxRetries(0)
                .build();
    }

    @Bean
    public StreamingChatLanguageModel streamingChatModel() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(effectiveBaseUrl())
                .apiKey(effectiveApiKey())
                .modelName(effectiveModelName())
                .temperature(0.3)
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    private String effectiveApiKey() {
        return hasConfiguredValue(gradingApiKey) ? gradingApiKey : deepseekApiKey;
    }

    private String effectiveBaseUrl() {
        if (!hasConfiguredValue(gradingApiKey)) return deepseekBaseUrl;
        return hasConfiguredValue(gradingBaseUrl)
                ? gradingBaseUrl : "https://dashscope.aliyuncs.com/compatible-mode/v1";
    }

    private String effectiveModelName() {
        if (!hasConfiguredValue(gradingApiKey)) return deepseekModelName;
        return hasConfiguredValue(gradingModelName) ? gradingModelName : "qwen3.7-plus";
    }

    private boolean hasConfiguredValue(String value) {
        return value != null && !value.isBlank()
                && !"not-configured".equalsIgnoreCase(value)
                && !"disabled".equalsIgnoreCase(value);
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        return new BgeSmallZhEmbeddingModel();
    }

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        // 优先尝试 Chroma 本地向量库，不可用时自动回退到内存向量库
        return createEmbeddingStore(chromaCollectionName);
    }

    @Bean
    public EmbeddingStore<TextSegment> databaseEmbeddingStore() {
        return createEmbeddingStore(databaseChromaCollectionName);
    }

    private EmbeddingStore<TextSegment> createEmbeddingStore(String collectionName) {
        if (!chromaEnabled) {
            log.info("Chroma 已关闭，使用内存向量库, collection: {}", collectionName);
            return new InMemoryEmbeddingStore<>();
        }
        try {
            log.info("尝试连接 Chroma 向量库: {}", chromaBaseUrl);
            ChromaEmbeddingStore store = ChromaEmbeddingStore.builder()
                    .baseUrl(chromaBaseUrl)
                    .collectionName(collectionName)
                    .build();
            log.info("Chroma 向量库连接成功, collection: {}", collectionName);
            return store;
        } catch (Exception e) {
            log.warn("Chroma 连接失败 ({}), 回退到 InMemoryEmbeddingStore", e.getMessage());
            log.warn("如需使用 Chroma，请先在另一个终端执行: chroma run --path ./chroma-data");
            return new InMemoryEmbeddingStore<>();
        }
    }
}
