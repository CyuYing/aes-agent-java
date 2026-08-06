package com.aes.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeSyncServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void javaKnowledgeSyncReplacesExistingIndexInsteadOfAppending() throws Exception {
        Files.writeString(tempDir.resolve("Java评分标准.txt"),
                "构造方法重载要求参数列表不同，代码应保持清晰。\n");
        Path referenceDir = Files.createDirectories(tempDir.resolve("reference_answers"));
        Files.writeString(referenceDir.resolve("Java参考答案.txt"),
                "参考答案：构造方法可通过参数个数或类型完成重载。\n");
        var service = new KnowledgeService(
                deterministicEmbeddingModel(), new InMemoryEmbeddingStore<>());
        ReflectionTestUtils.setField(service, "knowledgeBasePath", tempDir.toString());

        service.syncKnowledgeBase();
        int firstCount = service.getChunkCount();
        service.syncKnowledgeBase();

        assertThat(firstCount).isPositive();
        assertThat(service.getChunkCount()).isEqualTo(firstCount);
        assertThat(service.getFileCount()).isEqualTo(2);
        assertThat(service.getFileList()).contains("reference_answers/Java参考答案.txt");
    }

    @Test
    void databaseKnowledgeSyncReplacesExistingIndexInsteadOfAppending() throws Exception {
        Files.writeString(tempDir.resolve("数据库评分标准.txt"),
                "多表查询应使用清晰的 JOIN 条件并避免笛卡尔积。\n");
        Path referenceDir = Files.createDirectories(tempDir.resolve("reference_answers"));
        Files.writeString(referenceDir.resolve("数据库参考答案.txt"),
                "参考答案：连接查询应明确连接条件并核对结果集。\n");
        var service = new DatabaseKnowledgeService(
                deterministicEmbeddingModel(), new InMemoryEmbeddingStore<>());
        ReflectionTestUtils.setField(service, "knowledgeBasePath", tempDir.toString());

        service.syncKnowledgeBase();
        int firstCount = service.getChunkCount();
        service.syncKnowledgeBase();

        assertThat(firstCount).isPositive();
        assertThat(service.getChunkCount()).isEqualTo(firstCount);
        assertThat(service.getFileCount()).isEqualTo(2);
        assertThat(service.getFileList()).contains("reference_answers/数据库参考答案.txt");
    }

    private EmbeddingModel deterministicEmbeddingModel() {
        return segments -> Response.from(segments.stream()
                .map(segment -> Embedding.from(new float[]{1.0f, 0.0f, 0.0f}))
                .toList());
    }
}
