package com.aes.service;

import com.aes.model.Dto;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 教师参考答案的结构化仓库。JSON 负责稳定的题号、分值和答案匹配，原始 DOCX
 * 同时复制到课程知识库，供 RAG 提供评分语境与可追溯来源。
 */
@Service
public class AnswerKeyService {

    private static final Logger log = LoggerFactory.getLogger(AnswerKeyService.class);
    private static final Pattern SCORE_PATTERN = Pattern.compile(
            "(?:(?:本题|满分|总分)\\s*[：:]?\\s*)?(\\d{1,3})\\s*分");
    private static final Pattern RUBRIC_PATTERN = Pattern.compile(
            "[\\{｛]([^\\{\\}｛｝]*?\\d{1,3}\\s*分[^\\{\\}｛｝]*?)[\\}｝]",
            Pattern.DOTALL);

    private final AssignmentDocumentService assignmentDocumentService;
    private final KnowledgeService knowledgeService;
    private final DatabaseKnowledgeService databaseKnowledgeService;
    private final ObjectMapper objectMapper;
    private final Map<String, Dto.AnswerKeyProfile> profiles = new ConcurrentHashMap<>();

    @Value("${aes.answer-keys.path:${AES_ANSWER_KEYS_PATH:data/answer_keys}}")
    private String answerKeysPath;

    @Value("${aes.java.knowledge-base.path:${aes.knowledge-base.path:data/java_knowledge_base}}")
    private String javaKnowledgePath;

    @Value("${aes.database.knowledge-base.path:data/database_knowledge_base}")
    private String databaseKnowledgePath;

    public AnswerKeyService(AssignmentDocumentService assignmentDocumentService,
                            KnowledgeService knowledgeService,
                            DatabaseKnowledgeService databaseKnowledgeService,
                            ObjectMapper objectMapper) {
        this.assignmentDocumentService = assignmentDocumentService;
        this.knowledgeService = knowledgeService;
        this.databaseKnowledgeService = databaseKnowledgeService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        loadProfiles();
    }

    public synchronized Dto.AnswerKeyProfile importAnswerKey(
            MultipartFile file, String requestedCourseType, String requestedName) {
        try {
            byte[] bytes = file.getBytes();
            String hash = sha256(bytes);
            AssignmentDocumentService.ParsedAssignment parsed =
                    assignmentDocumentService.parse(file);
            if (parsed.questions().isEmpty()) {
                throw new IllegalArgumentException("参考答案文档中没有识别到题目");
            }

            String courseType = normalizeCourseType(requestedCourseType);
            if ("auto".equals(courseType)) {
                courseType = assignmentDocumentService.detectCourseType(file, parsed.questions());
            }
            String id = courseType + "-" + hash.substring(0, 16);
            Dto.AnswerKeyProfile existing = profiles.get(id);
            if (existing != null) return existing;

            List<Integer> extractedScores = parsed.questions().stream()
                    .map(question -> extractScore(question.description()))
                    .toList();
            ScoreAllocation allocation = allocateScores(extractedScores);
            List<Dto.AnswerKeyQuestion> questions = new ArrayList<>();
            for (int i = 0; i < parsed.questions().size(); i++) {
                Dto.QuestionEntry source = parsed.questions().get(i);
                String referenceAnswer = firstNonBlank(source.studentAnswer(), source.code());
                List<Dto.QuestionImage> referenceImages = relabelReferenceImages(
                        source.index(), source.images());
                questions.add(new Dto.AnswerKeyQuestion(
                        source.index(), source.title(), source.description(), referenceAnswer,
                        source.questionType(), allocation.scores().get(i),
                        allocation.inferred().get(i), extractRubric(source.description()),
                        referenceImages));
            }

            String safeSourceName = safeFileName(file.getOriginalFilename());
            String profileName = requestedName == null || requestedName.isBlank()
                    ? defaultProfileName(safeSourceName) : requestedName.trim();
            int maxScore = questions.stream().mapToInt(Dto.AnswerKeyQuestion::maxScore).sum();
            Dto.AnswerKeyProfile profile = new Dto.AnswerKeyProfile(
                    id, profileName, courseType, safeSourceName, hash,
                    Instant.now(), maxScore, questions);

            writeProfile(profile);
            writeSourceDocument(profile, bytes);
            profiles.put(id, profile);
            addToRetrievalKnowledgeBase(profile, bytes);
            return profile;
        } catch (IOException e) {
            throw new IllegalStateException("保存参考答案失败: " + e.getMessage(), e);
        }
    }

    public List<Dto.AnswerKeySummary> list(String courseType) {
        String normalized = courseType == null || courseType.isBlank()
                ? "" : normalizeCourseType(courseType);
        return profiles.values().stream()
                .filter(profile -> normalized.isBlank() || "auto".equals(normalized)
                        || profile.courseType().equals(normalized))
                .sorted(Comparator.comparing(Dto.AnswerKeyProfile::createdAt).reversed())
                .map(this::summary)
                .toList();
    }

    public Dto.AnswerKeyProfile getRequired(String id) {
        if (id == null || !id.matches("(?:java|database)-[a-f0-9]{16}")) {
            throw new IllegalArgumentException("答案库 ID 格式不正确");
        }
        Dto.AnswerKeyProfile profile = profiles.get(id);
        if (profile == null) throw new IllegalArgumentException("答案库不存在: " + id);
        return profile;
    }

    /** 教师可校正自动识别/补齐的逐题分值，但整份作业必须严格等于 100 分。 */
    public synchronized Dto.AnswerKeyProfile updateScores(
            String id, Dto.AnswerKeyScoreUpdate request) {
        Dto.AnswerKeyProfile current = getRequired(id);
        if (request == null || request.scores() == null || request.scores().isEmpty()) {
            throw new IllegalArgumentException("请提供至少一道题的分值");
        }
        Map<Integer, Integer> updates = new LinkedHashMap<>();
        Set<Integer> validIndexes = current.questions().stream()
                .map(Dto.AnswerKeyQuestion::index)
                .collect(java.util.stream.Collectors.toSet());
        for (Dto.AnswerKeyScoreItem item : request.scores()) {
            if (item == null || !validIndexes.contains(item.index())) {
                throw new IllegalArgumentException("包含答案库中不存在的题号");
            }
            if (item.maxScore() <= 0 || item.maxScore() > 100) {
                throw new IllegalArgumentException("每道题分值必须在 1 到 100 之间");
            }
            if (updates.put(item.index(), item.maxScore()) != null) {
                throw new IllegalArgumentException("题号重复: " + item.index());
            }
        }

        List<Dto.AnswerKeyQuestion> questions = current.questions().stream()
                .map(question -> new Dto.AnswerKeyQuestion(
                        question.index(), question.title(), question.description(),
                        question.referenceAnswer(), question.questionType(),
                        updates.getOrDefault(question.index(), question.maxScore()),
                        updates.containsKey(question.index()) ? false : question.scoreInferred(),
                        question.rubric(), question.referenceImages()))
                .toList();
        int total = questions.stream().mapToInt(Dto.AnswerKeyQuestion::maxScore).sum();
        if (total != 100) {
            throw new IllegalArgumentException("整份作业分值合计必须为 100，当前为 " + total);
        }
        Dto.AnswerKeyProfile updated = new Dto.AnswerKeyProfile(
                current.id(), current.name(), current.courseType(), current.sourceFileName(),
                current.sha256(), current.createdAt(), 100, questions);
        try {
            writeProfile(updated);
            profiles.put(updated.id(), updated);
            return updated;
        } catch (IOException e) {
            throw new IllegalStateException("保存逐题分值失败: " + e.getMessage(), e);
        }
    }

    public Dto.AnswerKeySummary summary(Dto.AnswerKeyProfile profile) {
        List<Dto.AnswerKeyQuestionSummary> questions = profile.questions().stream()
                .map(question -> new Dto.AnswerKeyQuestionSummary(
                        question.index(), question.title(), question.questionType(),
                        question.maxScore(), question.scoreInferred(), question.rubric(),
                        question.referenceImages().size()))
                .toList();
        return new Dto.AnswerKeySummary(
                profile.id(), profile.name(), profile.courseType(), profile.sourceFileName(),
                profile.createdAt(), profile.maxScore(), profile.questions().size(), questions);
    }

    public synchronized void delete(String id) {
        Dto.AnswerKeyProfile profile = getRequired(id);
        profiles.remove(id);
        try {
            Files.deleteIfExists(profilePath(id));
            Files.deleteIfExists(sourcePath(id));
            Path knowledgeRoot = knowledgeRoot(profile.courseType()).resolve("reference_answers");
            if (Files.isDirectory(knowledgeRoot)) {
                try (Stream<Path> files = Files.list(knowledgeRoot)) {
                    for (Path path : files.filter(p -> p.getFileName().toString().startsWith(id + "-"))
                            .toList()) {
                        Files.deleteIfExists(path);
                    }
                }
            }
            syncKnowledge(profile.courseType());
        } catch (IOException e) {
            throw new IllegalStateException("删除答案库失败: " + e.getMessage(), e);
        }
    }

    private void loadProfiles() {
        profiles.clear();
        Path directory = ensureDirectory();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(path -> {
                        try {
                            Dto.AnswerKeyProfile profile = objectMapper.readValue(
                                    path.toFile(), Dto.AnswerKeyProfile.class);
                            if (profile.id() != null && !profile.questions().isEmpty()) {
                                profiles.put(profile.id(), profile);
                            }
                        } catch (Exception error) {
                            log.warn("忽略损坏的答案库文件 {}: {}", path.getFileName(), error.getMessage());
                        }
                    });
        } catch (IOException e) {
            throw new IllegalStateException("读取答案库失败: " + e.getMessage(), e);
        }
    }

    private void writeProfile(Dto.AnswerKeyProfile profile) throws IOException {
        Path target = profilePath(profile.id());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), profile);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void writeSourceDocument(Dto.AnswerKeyProfile profile, byte[] bytes) throws IOException {
        Path path = sourcePath(profile.id());
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
    }

    private void addToRetrievalKnowledgeBase(Dto.AnswerKeyProfile profile, byte[] bytes) {
        try {
            Path referenceDirectory = knowledgeRoot(profile.courseType()).resolve("reference_answers");
            Files.createDirectories(referenceDirectory);
            Path target = referenceDirectory.resolve(
                    profile.id() + "-" + safeFileName(profile.sourceFileName())).normalize();
            if (!target.startsWith(referenceDirectory.toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("参考答案文件名不安全");
            }
            Files.write(target, bytes);
            syncKnowledge(profile.courseType());
        } catch (Exception error) {
            // 结构化答案库仍可正常评分；保留文件后允许教师在界面再次同步 RAG。
            log.warn("答案库已保存，但知识索引暂未同步: {}", error.getMessage());
        }
    }

    private void syncKnowledge(String courseType) {
        if ("database".equals(courseType)) databaseKnowledgeService.syncKnowledgeBase();
        else knowledgeService.syncKnowledgeBase();
    }

    private Path knowledgeRoot(String courseType) {
        String configured = "database".equals(courseType)
                ? databaseKnowledgePath : javaKnowledgePath;
        Path path = Path.of(configured);
        return (path.isAbsolute() ? path : Path.of("").toAbsolutePath().resolve(path))
                .normalize().toAbsolutePath();
    }

    private List<Dto.QuestionImage> relabelReferenceImages(
            int questionIndex, List<Dto.QuestionImage> images) {
        if (images == null || images.isEmpty()) return List.of();
        List<Dto.QuestionImage> result = new ArrayList<>();
        Set<String> seen = new java.util.LinkedHashSet<>();
        int sequence = 1;
        for (Dto.QuestionImage image : images) {
            String fingerprint = String.valueOf(image.mediaType()) + '\u0000'
                    + String.valueOf(image.dataBase64());
            if (!seen.add(fingerprint)) continue;
            result.add(new Dto.QuestionImage(
                    "ref-q" + questionIndex + "-img" + sequence++,
                    image.fileName(), image.mediaType(), image.dataBase64(), "reference"));
        }
        return List.copyOf(result);
    }

    private ScoreAllocation allocateScores(List<Integer> values) {
        int known = values.stream().filter(value -> value > 0).mapToInt(Integer::intValue).sum();
        int missing = (int) values.stream().filter(value -> value <= 0).count();
        if (missing == 0 && known == 100) {
            return new ScoreAllocation(List.copyOf(values),
                    values.stream().map(ignored -> false).toList());
        }
        if (missing > 0 && known < 100 && 100 - known >= missing) {
            int distributable = 100 - known;
            int base = distributable / missing;
            int remainder = distributable % missing;
            List<Integer> scores = new ArrayList<>();
            List<Boolean> inferred = new ArrayList<>();
            for (Integer value : values) {
                if (value > 0) {
                    scores.add(value);
                    inferred.add(false);
                } else {
                    scores.add(base + (remainder-- > 0 ? 1 : 0));
                    inferred.add(true);
                }
            }
            return new ScoreAllocation(List.copyOf(scores), List.copyOf(inferred));
        }

        // 未给分、显式分值合计异常或没有足够剩余分数时，按原权重归一到 100。
        List<Integer> weights = values.stream().map(value -> value > 0 ? value : 1).toList();
        int weightSum = weights.stream().mapToInt(Integer::intValue).sum();
        List<Integer> scores = new ArrayList<>();
        List<Double> fractions = new ArrayList<>();
        int allocated = 0;
        for (Integer weight : weights) {
            double raw = weight * 100.0 / weightSum;
            int floor = (int) Math.floor(raw);
            scores.add(floor);
            fractions.add(raw - floor);
            allocated += floor;
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) order.add(i);
        order.sort((left, right) -> Double.compare(fractions.get(right), fractions.get(left)));
        for (int i = 0; i < 100 - allocated; i++) {
            int index = order.get(i % order.size());
            scores.set(index, scores.get(index) + 1);
        }
        return new ScoreAllocation(List.copyOf(scores),
                values.stream().map(ignored -> true).toList());
    }

    private int extractScore(String description) {
        if (description == null) return 0;
        Matcher matcher = SCORE_PATTERN.matcher(description);
        if (!matcher.find()) return 0;
        int value = Integer.parseInt(matcher.group(1));
        return value > 0 && value <= 100 ? value : 0;
    }

    private String extractRubric(String description) {
        if (description == null) return "";
        Matcher matcher = RUBRIC_PATTERN.matcher(description);
        return matcher.find() ? matcher.group(1).replaceAll("\\s+", " ").trim() : "";
    }

    private String normalizeCourseType(String value) {
        String normalized = value == null || value.isBlank()
                ? "auto" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "auto", "java", "database" -> normalized;
            case "db", "sql" -> "database";
            default -> throw new IllegalArgumentException("课程类型仅支持 auto、java 或 database");
        };
    }

    private Path ensureDirectory() {
        try {
            Path path = Path.of(answerKeysPath);
            Path directory = (path.isAbsolute() ? path : Path.of("").toAbsolutePath().resolve(path))
                    .normalize().toAbsolutePath();
            Files.createDirectories(directory);
            return directory;
        } catch (IOException e) {
            throw new IllegalStateException("创建答案库目录失败: " + e.getMessage(), e);
        }
    }

    private Path profilePath(String id) {
        return ensureDirectory().resolve(id + ".json").normalize();
    }

    private Path sourcePath(String id) {
        return ensureDirectory().resolve("sources").resolve(id + ".docx").normalize();
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("无法计算参考答案指纹", e);
        }
    }

    private String safeFileName(String value) {
        String name = value == null || value.isBlank()
                ? "参考答案.docx" : Path.of(value).getFileName().toString();
        return name.replaceAll("[<>:\"/\\\\|?*]", "_");
    }

    private String defaultProfileName(String sourceFileName) {
        String value = sourceFileName.replaceFirst("(?i)\\.docx$", "")
                .replaceAll("[-_—]?(?:题目)?参考答案与评分标准$", "")
                .replaceAll("[-_—]?参考答案$", "")
                .trim();
        return value.isBlank() ? "课程作业答案库" : value;
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first.trim();
        return second == null ? "" : second.trim();
    }

    private record ScoreAllocation(List<Integer> scores, List<Boolean> inferred) {}
}
