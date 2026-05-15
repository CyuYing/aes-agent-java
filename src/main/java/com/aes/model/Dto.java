package com.aes.model;

import java.util.List;
import java.util.Map;

/**
 * 所有 DTO / Record 集中定义，减少文件分散。
 */
public final class Dto {

    private Dto() {}

    // ================================================================
    // 评分请求 / 响应
    // ================================================================
    public static class ScoreRequest {
        private String content;
        private String category = "general";

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }

        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
    }

    public static class ScoreResponse {
        private String report;
        private List<Map<String, String>> sources;

        public ScoreResponse(String report, List<Map<String, String>> sources) {
            this.report = report;
            this.sources = sources;
        }

        public String getReport() { return report; }
        public List<Map<String, String>> getSources() { return sources; }
    }

    // ================================================================
    // 知识库状态
    // ================================================================
    public static class KnowledgeStats {
        private int chunkCount;
        private int fileCount;
        private List<String> files;
        private List<Map<String, String>> metadata;

        public KnowledgeStats(int chunkCount, int fileCount, List<String> files, List<Map<String, String>> metadata) {
            this.chunkCount = chunkCount;
            this.fileCount = fileCount;
            this.files = files;
            this.metadata = metadata;
        }

        public int getChunkCount() { return chunkCount; }
        public int getFileCount() { return fileCount; }
        public List<String> getFiles() { return files; }
        public List<Map<String, String>> getMetadata() { return metadata; }
    }

    // ================================================================
    // 作业批改相关
    // ================================================================

    /**
     * 从 Word 中解析出的一道编程题
     */
    public record QuestionEntry(
            int index,
            String title,
            String description,
            String code,
            String language
    ) {}

    /**
     * 单道题的批改结果
     */
    public record QuestionResult(
            int index,
            String title,
            int score,
            int maxScore,
            Map<String, DimensionScore> dimensions,
            String report,
            List<Map<String, String>> sources
    ) {}

    /**
     * 维度得分详情
     */
    public record DimensionScore(
            String label,
            int score,
            int maxScore,
            String comment
    ) {}

    /**
     * 整份作业的批改结果
     */
    public record HomeworkResult(
            String fileName,
            int totalScore,
            int maxTotalScore,
            List<QuestionResult> questions
    ) {}
}
