package com.aes.model;

import java.time.Instant;
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
     * Word 中的一张内嵌图片。role 取 question / student / reference。
     */
    public record QuestionImage(
            String id,
            String fileName,
            String mediaType,
            String dataBase64,
            String role
    ) {}

    /**
     * 从 Word 中解析出的一道题。questionType 取 programming / choice /
     * subjective / image，studentAnswer 保存非代码类题目的学生作答。
     */
    public record QuestionEntry(
            int index,
            String title,
            String description,
            String code,
            String language,
            String questionType,
            String studentAnswer,
            List<QuestionImage> images
    ) {
        public QuestionEntry {
            images = images == null ? List.of() : List.copyOf(images);
        }

        /** 保留原有调用方式，默认按编程题处理。 */
        public QuestionEntry(int index, String title, String description,
                             String code, String language) {
            this(index, title, description, code, language,
                    "programming", code, List.of());
        }
    }

    /** 教师在题目预览页为单题设置的批改参数。 */
    public record QuestionConfig(
            int index,
            String questionType,
            String correctAnswer,
            String customPrompt,
            String studentAnswer,
            Map<String, String> imageRoles
    ) {}

    /**
     * 题目边界识别的可审计状态。AI 只复核题目起始行，不改写原文；任何异常都会
     * 回退到本地规则。
     */
    public record QuestionRecognitionInfo(
            boolean requested,
            boolean aiUsed,
            String method,
            String message,
            int ruleQuestionCount,
            int finalQuestionCount,
            double confidence
    ) {}

    /** 上传文档后的逐题解析预览。 */
    public record HomeworkPreview(
            String fileName,
            String courseType,
            List<QuestionEntry> questions,
            QuestionRecognitionInfo recognition
    ) {
        public HomeworkPreview(String fileName, String courseType,
                               List<QuestionEntry> questions) {
            this(fileName, courseType, questions, new QuestionRecognitionInfo(
                    false, false, "rule", "已使用本地规则识别题目", questions.size(),
                    questions.size(), 1.0));
        }

        public HomeworkPreview(String fileName, List<QuestionEntry> questions) {
            this(fileName, "java", questions);
        }
    }

    /** 图片经多模态模型转写后的结果，不再回传原始图片数据。 */
    public record ImageAnalysis(
            String id,
            String fileName,
            String role,
            String description,
            boolean success
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
            List<Map<String, String>> sources,
            String questionType,
            String gradingMethod,
            String studentAnswer,
            String correctAnswer,
            List<ImageAnalysis> imageAnalyses,
            String imageComparison
    ) {
        public QuestionResult {
            imageAnalyses = imageAnalyses == null ? List.of() : List.copyOf(imageAnalyses);
        }

        /** 兼容原有的纯文本/代码评分结果构造方式。 */
        public QuestionResult(int index, String title, int score, int maxScore,
                              Map<String, DimensionScore> dimensions, String report,
                              List<Map<String, String>> sources) {
            this(index, title, score, maxScore, dimensions, report, sources,
                    "programming", "ai", "", "", List.of(), "");
        }
    }

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
    // 结构化答案库与批量批改
    // ================================================================

    /** 从作业首页表格提取的学生身份信息。 */
    public record StudentIdentity(
            String name,
            String studentId,
            String className,
            String assignmentNo
    ) {}

    /** 答案库中的一道标准题，分值与参考图均从教师答案文档中提取。 */
    public record AnswerKeyQuestion(
            int index,
            String title,
            String description,
            String referenceAnswer,
            String questionType,
            int maxScore,
            boolean scoreInferred,
            String rubric,
            List<QuestionImage> referenceImages
    ) {
        public AnswerKeyQuestion {
            referenceImages = referenceImages == null
                    ? List.of() : List.copyOf(referenceImages);
        }
    }

    /** 持久化到 data/answer_keys 的完整答案库。 */
    public record AnswerKeyProfile(
            String id,
            String name,
            String courseType,
            String sourceFileName,
            String sha256,
            Instant createdAt,
            int maxScore,
            List<AnswerKeyQuestion> questions
    ) {
        public AnswerKeyProfile {
            questions = questions == null ? List.of() : List.copyOf(questions);
        }
    }

    /** 列表接口只返回轻量摘要，避免把参考图片 Base64 重复传到浏览器。 */
    public record AnswerKeyQuestionSummary(
            int index,
            String title,
            String questionType,
            int maxScore,
            boolean scoreInferred,
            String rubric,
            int referenceImageCount
    ) {}

    public record AnswerKeyScoreItem(int index, int maxScore) {}

    public record AnswerKeyScoreUpdate(List<AnswerKeyScoreItem> scores) {}

    public record AnswerKeySummary(
            String id,
            String name,
            String courseType,
            String sourceFileName,
            Instant createdAt,
            int maxScore,
            int questionCount,
            List<AnswerKeyQuestionSummary> questions
    ) {}

    /** 学生题目与答案库题目的匹配证据。 */
    public record QuestionMatch(
            int studentQuestionIndex,
            int referenceQuestionIndex,
            String method,
            double confidence,
            String title
    ) {}

    public record BatchFilePreview(
            String fileName,
            StudentIdentity student,
            int questionCount,
            int matchedCount,
            List<QuestionMatch> matches,
            List<String> warnings,
            QuestionRecognitionInfo recognition
    ) {
        public BatchFilePreview(String fileName, StudentIdentity student,
                                int questionCount, int matchedCount,
                                List<QuestionMatch> matches, List<String> warnings) {
            this(fileName, student, questionCount, matchedCount, matches, warnings,
                    new QuestionRecognitionInfo(false, false, "rule",
                            "已使用本地规则识别题目", questionCount,
                            questionCount, 1.0));
        }
    }

    public record BatchPreview(
            AnswerKeySummary answerKey,
            List<BatchFilePreview> files
    ) {}

    public record BatchFileResult(
            String fileName,
            StudentIdentity student,
            int matchedCount,
            int questionCount,
            String recordId,
            HomeworkResult grading
    ) {}

    public record BatchGradingResult(
            AnswerKeySummary answerKey,
            List<BatchFileResult> files,
            double averageScore,
            int maxScore
    ) {}

    /** SSE 单题事件，携带所属学生，前端可稳定归组。 */
    public record BatchQuestionEvent(
            int fileIndex,
            String fileName,
            StudentIdentity student,
            QuestionResult question
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
