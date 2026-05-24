package com.aes.model;

import java.util.List;
import java.util.Map;

/**
 * 所有 DTO / Record 集中定义，减少文件分散。
 */
public final class Dto {

    private Dto() {}

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

    // ================================================================
    // 数据库作业批改相关
    // ================================================================

    /**
     * 从 Word 中解析出的一道数据库作业题
     */
    public record DatabaseQuestionEntry(
            int index,
            String title,
            String description,
            String setupSql,
            String answerSql
    ) {}

    /**
     * SQL 执行结果汇总
     */
    public record SqlExecutionResult(
            boolean success,
            List<SqlStatementResult> statements,
            String errorSummary
    ) {}

    /**
     * 单条 SQL 语句执行结果
     */
    public record SqlStatementResult(
            String sql,
            boolean success,
            int updateCount,
            List<String> columns,
            List<Map<String, String>> rows,
            String error
    ) {}

    /**
     * 单道数据库题的批改结果
     */
    public record DatabaseQuestionResult(
            int index,
            String title,
            int score,
            int maxScore,
            Map<String, DimensionScore> dimensions,
            String report,
            SqlExecutionResult execution,
            List<Map<String, String>> sources
    ) {}

    /**
     * 整份数据库作业的批改结果
     */
    public record DatabaseHomeworkResult(
            String fileName,
            int totalScore,
            int maxTotalScore,
            List<DatabaseQuestionResult> questions
    ) {}
}
