package com.aes.service;

import com.aes.model.Dto;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * “答案库 + 多份学生作业”的一键工作流。确定性匹配负责找对题，模型只负责
 * 语义评分；最终成绩严格缩放到教师答案文档声明的逐题分值。
 */
@Service
public class BatchGradingService {

    private static final int MAX_BATCH_FILES = 50;
    private static final Pattern ARABIC_ORDINAL = Pattern.compile(
            "(?:第\\s*|题目\\s*|题\\s*)(\\d+)\\s*题?", Pattern.CASE_INSENSITIVE);

    private final AnswerKeyService answerKeyService;
    private final AssignmentDocumentService assignmentDocumentService;
    private final HomeworkService homeworkService;
    private final GradingRecordService gradingRecordService;

    public BatchGradingService(AnswerKeyService answerKeyService,
                               AssignmentDocumentService assignmentDocumentService,
                               HomeworkService homeworkService,
                               GradingRecordService gradingRecordService) {
        this.answerKeyService = answerKeyService;
        this.assignmentDocumentService = assignmentDocumentService;
        this.homeworkService = homeworkService;
        this.gradingRecordService = gradingRecordService;
    }

    public Dto.BatchPreview preview(String answerKeyId, List<MultipartFile> files) {
        return preview(answerKeyId, files, false);
    }

    public Dto.BatchPreview preview(String answerKeyId, List<MultipartFile> files,
                                    boolean aiQuestionRecognition) {
        Dto.AnswerKeyProfile key = answerKeyService.getRequired(answerKeyId);
        requireHundredPointKey(key);
        List<PreparedFile> prepared = prepareFiles(key, files, aiQuestionRecognition);
        return new Dto.BatchPreview(
                answerKeyService.summary(key),
                prepared.stream().map(PreparedFile::preview).toList());
    }

    public Dto.BatchGradingResult grade(
            String answerKeyId,
            List<MultipartFile> files,
            String actor,
            ProgressSink progress) {
        return grade(answerKeyId, files, actor, false, progress);
    }

    public Dto.BatchGradingResult grade(
            String answerKeyId,
            List<MultipartFile> files,
            String actor,
            boolean aiQuestionRecognition,
            ProgressSink progress) {
        Dto.AnswerKeyProfile key = answerKeyService.getRequired(answerKeyId);
        requireHundredPointKey(key);
        List<PreparedFile> prepared = prepareFiles(key, files, aiQuestionRecognition);
        ProgressSink sink = progress == null ? (event, payload) -> {} : progress;
        Dto.BatchPreview preview = new Dto.BatchPreview(
                answerKeyService.summary(key),
                prepared.stream().map(PreparedFile::preview).toList());
        sink.emit("preview", preview);

        List<Dto.BatchFileResult> fileResults = new ArrayList<>();
        int scoreSum = 0;
        for (int fileIndex = 0; fileIndex < prepared.size(); fileIndex++) {
            PreparedFile file = prepared.get(fileIndex);
            sink.emit("file-start", Map.of(
                    "fileIndex", fileIndex,
                    "fileName", file.assignment().fileName(),
                    "student", file.assignment().student(),
                    "matchedCount", file.matches().size(),
                    "questionCount", key.questions().size()));

            Map<Integer, MatchedQuestion> byReference = new HashMap<>();
            for (MatchedQuestion match : file.matches()) {
                byReference.put(match.reference().index(), match);
            }

            List<Dto.QuestionResult> questions = new ArrayList<>();
            for (Dto.AnswerKeyQuestion reference : key.questions()) {
                MatchedQuestion match = byReference.get(reference.index());
                Dto.QuestionResult result;
                if (match == null) {
                    result = missingResult(reference, "未在学生文档中找到对应题号");
                } else if (!hasStudentEvidence(match.student())) {
                    result = missingResult(reference, "学生未作答");
                } else {
                    result = gradeMatchedQuestion(key, reference, match);
                }
                questions.add(result);
                sink.emit("question", new Dto.BatchQuestionEvent(
                        fileIndex, file.assignment().fileName(),
                        file.assignment().student(), result));
            }

            int totalScore = questions.stream().mapToInt(Dto.QuestionResult::score).sum();
            int maxScore = questions.stream().mapToInt(Dto.QuestionResult::maxScore).sum();
            Dto.HomeworkResult grading = new Dto.HomeworkResult(
                    file.assignment().fileName(), totalScore, maxScore, questions);
            GradingRecordService.RecordSummary record = gradingRecordService.saveHomework(
                    key.courseType(), grading, actor, file.assignment().student());
            Dto.BatchFileResult fileResult = new Dto.BatchFileResult(
                    file.assignment().fileName(), file.assignment().student(),
                    file.matches().size(), key.questions().size(), record.id(), grading);
            fileResults.add(fileResult);
            scoreSum += totalScore;
            sink.emit("file-result", fileResult);
        }

        double average = fileResults.isEmpty()
                ? 0.0 : Math.round(scoreSum * 100.0 / fileResults.size()) / 100.0;
        Dto.BatchGradingResult result = new Dto.BatchGradingResult(
                answerKeyService.summary(key), List.copyOf(fileResults), average, key.maxScore());
        sink.emit("summary", result);
        return result;
    }

    private Dto.QuestionResult gradeMatchedQuestion(
            Dto.AnswerKeyProfile key,
            Dto.AnswerKeyQuestion reference,
            MatchedQuestion match) {
        String studentAnswer = studentAnswer(match.student());
        if (!reference.referenceAnswer().isBlank()
                && normalizeAnswer(reference.referenceAnswer()).equals(normalizeAnswer(studentAnswer))
                && match.student().images().isEmpty()) {
            return exactReferenceResult(reference, studentAnswer, match);
        }

        String type = normalizeQuestionType(reference.questionType(), match.student().questionType());
        Dto.QuestionEntry merged = new Dto.QuestionEntry(
                reference.index(), reference.title(), reference.description(),
                match.student().code(), "database".equals(key.courseType()) ? "sql" : "java",
                type, studentAnswer, match.student().images());
        String rubric = reference.rubric().isBlank()
                ? "按参考答案逐项给分"
                : reference.rubric();
        String instruction = "本题原始满分为 " + reference.maxScore()
                + " 分。教师评分细则：" + rubric
                + "。先按百分制输出，系统会严格换算到本题原始分值。"
                + "答案库匹配方式：" + match.method() + "。";
        Dto.QuestionConfig config = new Dto.QuestionConfig(
                reference.index(), type, reference.referenceAnswer(), instruction,
                studentAnswer, Map.of());
        Dto.QuestionResult percentage = homeworkService.gradeMatchedQuestion(
                merged, config, reference.referenceImages(),
                "database".equals(key.courseType()) ? "database" : "general",
                key.courseType());
        return scaleResult(percentage, reference.maxScore(), match);
    }

    private Dto.QuestionResult exactReferenceResult(
            Dto.AnswerKeyQuestion reference, String studentAnswer, MatchedQuestion match) {
        Map<String, Dto.DimensionScore> dimensions = Map.of(
                "reference_match", new Dto.DimensionScore(
                        "参考答案一致性", reference.maxScore(), reference.maxScore(),
                        "学生文字/代码与结构化参考答案一致"));
        String report = "### 本题得分：" + reference.maxScore() + "/" + reference.maxScore()
                + "\n\n- 答案库匹配：" + match.method()
                + "\n- 判定方式：标准答案确定性对比\n- 结论：作答与参考答案一致。";
        return new Dto.QuestionResult(
                reference.index(), reference.title(), reference.maxScore(), reference.maxScore(),
                dimensions, report, List.of(), reference.questionType(),
                "answer-key-exact", studentAnswer, reference.referenceAnswer(), List.of(), "");
    }

    private Dto.QuestionResult missingResult(Dto.AnswerKeyQuestion reference, String reason) {
        Map<String, Dto.DimensionScore> dimensions = Map.of(
                "completion", new Dto.DimensionScore(
                        "作答完整性", 0, reference.maxScore(), reason));
        return new Dto.QuestionResult(
                reference.index(), reference.title(), 0, reference.maxScore(), dimensions,
                "### 本题得分：0/" + reference.maxScore() + "\n\n" + reason + "。",
                List.of(), reference.questionType(), "answer-key-missing",
                "", reference.referenceAnswer(), List.of(), "");
    }

    private Dto.QuestionResult scaleResult(
            Dto.QuestionResult source, int maxScore, MatchedQuestion match) {
        int score = clamp((int) Math.round(source.score() * maxScore / 100.0), 0, maxScore);
        Map<String, Dto.DimensionScore> dimensions = new LinkedHashMap<>();
        for (Map.Entry<String, Dto.DimensionScore> entry : source.dimensions().entrySet()) {
            Dto.DimensionScore value = entry.getValue();
            int scaledMax = Math.max(1, (int) Math.round(value.maxScore() * maxScore / 100.0));
            int scaledScore = value.maxScore() <= 0 ? 0
                    : clamp((int) Math.round(value.score() * scaledMax / (double) value.maxScore()),
                    0, scaledMax);
            dimensions.put(entry.getKey(), new Dto.DimensionScore(
                    value.label(), scaledScore, scaledMax, value.comment()));
        }
        String report = "### 本题得分：" + score + "/" + maxScore
                + "\n\n> 答案库匹配：" + match.method()
                + "（置信度 " + Math.round(match.confidence() * 100) + "%）\n\n"
                + source.report();
        return new Dto.QuestionResult(
                source.index(), source.title(), score, maxScore, dimensions, report,
                source.sources(), source.questionType(), "answer-key-" + source.gradingMethod(),
                source.studentAnswer(), source.correctAnswer(), source.imageAnalyses(),
                source.imageComparison());
    }

    private List<PreparedFile> prepareFiles(
            Dto.AnswerKeyProfile key, List<MultipartFile> files,
            boolean aiQuestionRecognition) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("请至少上传一份学生作业");
        }
        if (files.size() > MAX_BATCH_FILES) {
            throw new IllegalArgumentException("单批最多上传 " + MAX_BATCH_FILES + " 份学生作业");
        }
        List<PreparedFile> result = new ArrayList<>();
        for (MultipartFile file : files) {
            AssignmentDocumentService.ParsedAssignment assignment =
                    assignmentDocumentService.parse(
                            file, aiQuestionRecognition, key.questions().size());
            List<MatchedQuestion> matches = matchQuestions(key.questions(), assignment.questions());
            List<String> warnings = new ArrayList<>();
            if (assignment.questions().isEmpty()) warnings.add("未识别到任何题目");
            if (matches.size() < key.questions().size()) {
                Set<Integer> matched = matches.stream()
                        .map(item -> item.reference().index())
                        .collect(LinkedHashSet::new, Set::add, Set::addAll);
                List<Integer> missing = key.questions().stream()
                        .map(Dto.AnswerKeyQuestion::index)
                        .filter(index -> !matched.contains(index)).toList();
                warnings.add("缺少答案库题号：" + missing);
            }
            if (assignment.questions().size() > matches.size()) {
                warnings.add("有 " + (assignment.questions().size() - matches.size())
                        + " 道学生题目未匹配，将不计入本次成绩");
            }
            List<Dto.QuestionMatch> publicMatches = matches.stream()
                    .map(match -> new Dto.QuestionMatch(
                            match.student().index(), match.reference().index(), match.method(),
                            match.confidence(), match.reference().title()))
                    .toList();
            Dto.BatchFilePreview preview = new Dto.BatchFilePreview(
                    assignment.fileName(), assignment.student(), assignment.questions().size(),
                    matches.size(), publicMatches, List.copyOf(warnings),
                    assignment.recognition());
            result.add(new PreparedFile(assignment, matches, preview));
        }
        return List.copyOf(result);
    }

    private List<MatchedQuestion> matchQuestions(
            List<Dto.AnswerKeyQuestion> references,
            List<Dto.QuestionEntry> students) {
        List<MatchedQuestion> result = new ArrayList<>();
        Set<Integer> usedStudents = new HashSet<>();

        // 第一层：文档明确题号，置信度最高。
        for (Dto.AnswerKeyQuestion reference : references) {
            int referenceOrdinal = ordinal(reference.title(), reference.index());
            for (Dto.QuestionEntry student : students) {
                if (usedStudents.contains(student.index())) continue;
                int studentOrdinal = ordinal(student.title(), student.index());
                if (studentOrdinal == referenceOrdinal) {
                    result.add(new MatchedQuestion(
                            reference, student, "题号精确匹配", 1.0));
                    usedStudents.add(student.index());
                    break;
                }
            }
        }

        Set<Integer> matchedReferences = result.stream()
                .map(item -> item.reference().index())
                .collect(HashSet::new, Set::add, Set::addAll);

        // 第二层：题干字符二元组相似度，适配题目被重新排序的文档。
        for (Dto.AnswerKeyQuestion reference : references) {
            if (matchedReferences.contains(reference.index())) continue;
            Dto.QuestionEntry best = null;
            double bestScore = 0.0;
            for (Dto.QuestionEntry student : students) {
                if (usedStudents.contains(student.index())) continue;
                double score = similarity(
                        reference.title() + " " + reference.description(),
                        student.title() + " " + student.description());
                if (score > bestScore) {
                    best = student;
                    bestScore = score;
                }
            }
            if (best != null && bestScore >= 0.42) {
                result.add(new MatchedQuestion(
                        reference, best, "题干相似度匹配", Math.min(0.95, bestScore)));
                usedStudents.add(best.index());
                matchedReferences.add(reference.index());
            }
        }

        result.sort(Comparator.comparingInt(item -> item.reference().index()));
        return List.copyOf(result);
    }

    private double similarity(String first, String second) {
        Set<String> left = bigrams(normalizeQuestion(first));
        Set<String> right = bigrams(normalizeQuestion(second));
        if (left.isEmpty() || right.isEmpty()) return 0.0;
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        return union.isEmpty() ? 0.0 : intersection.size() / (double) union.size();
    }

    private Set<String> bigrams(String value) {
        Set<String> result = new HashSet<>();
        if (value.length() == 1) result.add(value);
        for (int i = 0; i + 1 < value.length(); i++) {
            result.add(value.substring(i, i + 2));
        }
        return result;
    }

    private String normalizeQuestion(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\{｛][^\\{\\}｛｝]*?分[^\\{\\}｛｝]*?[\\}｝]", "")
                .replaceAll("(?:【?第\\s*\\d+\\s*题】?|题目\\s*\\d+)", "")
                .replaceAll("[^\\p{IsHan}a-z0-9]+", "");
    }

    private int ordinal(String title, int fallback) {
        if (title != null) {
            Matcher matcher = ARABIC_ORDINAL.matcher(title);
            if (matcher.find()) {
                try { return Integer.parseInt(matcher.group(1)); }
                catch (NumberFormatException ignored) { }
            }
        }
        return fallback;
    }

    private boolean hasStudentEvidence(Dto.QuestionEntry question) {
        return !studentAnswer(question).isBlank() || (question.images() != null
                && !question.images().isEmpty());
    }

    private String studentAnswer(Dto.QuestionEntry question) {
        if (question.studentAnswer() != null && !question.studentAnswer().isBlank()) {
            return question.studentAnswer().trim();
        }
        return question.code() == null ? "" : question.code().trim();
    }

    private String normalizeAnswer(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replace('，', ',').replace('；', ';').replace('：', ':')
                .replaceAll("\\s+", "").trim();
    }

    private String normalizeQuestionType(String reference, String student) {
        String value = reference == null || reference.isBlank() ? student : reference;
        if (value == null) return "subjective";
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "programming", "choice", "subjective", "image" -> value.toLowerCase(Locale.ROOT);
            default -> "subjective";
        };
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void requireHundredPointKey(Dto.AnswerKeyProfile key) {
        int total = key.questions().stream().mapToInt(Dto.AnswerKeyQuestion::maxScore).sum();
        if (total != 100 || key.maxScore() != 100) {
            throw new IllegalArgumentException(
                    "答案库逐题分值合计必须为 100，当前为 " + total + "，请先校正分值");
        }
    }

    @FunctionalInterface
    public interface ProgressSink {
        void emit(String event, Object payload);
    }

    private record MatchedQuestion(
            Dto.AnswerKeyQuestion reference,
            Dto.QuestionEntry student,
            String method,
            double confidence
    ) {}

    private record PreparedFile(
            AssignmentDocumentService.ParsedAssignment assignment,
            List<MatchedQuestion> matches,
            Dto.BatchFilePreview preview
    ) {}
}
