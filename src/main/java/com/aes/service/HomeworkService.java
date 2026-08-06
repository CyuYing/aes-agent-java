package com.aes.service;

import com.aes.model.Dto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 作业批改工作流：逐题解析 → 教师配置 → 确定性/AI 分流 → 图片处理 → 汇总。
 */
@Service
public class HomeworkService {

    private static final Pattern CHOICE_IN_IMAGE = Pattern.compile(
            "(?i)(?:答案|选择|选中|勾选|作答)[^A-H\\n]{0,16}([A-H](?:\\s*[,，、/\\s]?\\s*[A-H])*)");

    private final DocumentParserService documentParserService;
    private final KnowledgeService knowledgeService;
    private final DatabaseKnowledgeService databaseKnowledgeService;
    private final ChoiceGradingService choiceGradingService;
    private final ImageDescriptionService imageDescriptionService;
    private final ChatLanguageModel chatModel;
    private final ObjectMapper objectMapper;

    public HomeworkService(DocumentParserService documentParserService,
                           KnowledgeService knowledgeService,
                           DatabaseKnowledgeService databaseKnowledgeService,
                           ChoiceGradingService choiceGradingService,
                           ImageDescriptionService imageDescriptionService,
                           ChatLanguageModel chatModel,
                           ObjectMapper objectMapper) {
        this.documentParserService = documentParserService;
        this.knowledgeService = knowledgeService;
        this.databaseKnowledgeService = databaseKnowledgeService;
        this.choiceGradingService = choiceGradingService;
        this.imageDescriptionService = imageDescriptionService;
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    private static final String PROGRAMMING_SYSTEM_PROMPT = """
            你是一位资深的 Java 教学专家与代码审查者。请根据题目、教师要求、
            参考答案、图片转写结果和检索到的评分标准，评估学生作答。

            评估维度与满分：
            1. requirement 是否符合题意 — 30 分
            2. style 代码规范 — 20 分
            3. logic 逻辑正确性 — 20 分
            4. performance 性能与效率 — 15 分
            5. maintain 可维护性 — 15 分

            必须只返回 JSON，不要输出 markdown 代码块：
            {
              "score": 0,
              "maxScore": 100,
              "dimensions": {
                "requirement": {"label":"是否符合题意","score":0,"maxScore":30,"comment":""},
                "style": {"label":"代码规范","score":0,"maxScore":20,"comment":""},
                "logic": {"label":"逻辑正确性","score":0,"maxScore":20,"comment":""},
                "performance": {"label":"性能与效率","score":0,"maxScore":15,"comment":""},
                "maintain": {"label":"可维护性","score":0,"maxScore":15,"comment":""}
              },
              "report": "Markdown 格式的具体评语"
            }

            评分原则：逐条核对题意；以教师逐题要求为本题的补充规则；图片识别失败时
            不得臆测；学生未作答必须判 0 分；指出问题时给出具体依据和修改建议；
            Word 转换产生的空行等噪音不扣分。
            """;

    private static final String SUBJECTIVE_SYSTEM_PROMPT = """
            你是一位严谨的课程教师。请根据题目、教师逐题要求、参考答案、图片转写/匹配
            结论和检索到的评分标准，对学生的文字或图片作答进行评分。

            评估维度与满分：
            1. requirement 题意符合度 — 25 分
            2. accuracy 内容正确性 — 30 分
            3. completeness 完整性 — 20 分
            4. method 解题过程与方法 — 15 分
            5. clarity 表达清晰度 — 10 分

            必须只返回 JSON，不要输出 markdown 代码块：
            {
              "score": 0,
              "maxScore": 100,
              "dimensions": {
                "requirement": {"label":"题意符合度","score":0,"maxScore":25,"comment":""},
                "accuracy": {"label":"内容正确性","score":0,"maxScore":30,"comment":""},
                "completeness": {"label":"完整性","score":0,"maxScore":20,"comment":""},
                "method": {"label":"解题过程与方法","score":0,"maxScore":15,"comment":""},
                "clarity": {"label":"表达清晰度","score":0,"maxScore":10,"comment":""}
              },
              "report": "Markdown 格式的具体评语"
            }

            图片描述和匹配结论只是证据；若识别失败必须说明，不得自行补全看不清的内容。
            学生未作答必须判 0 分；参考答案相同含义的不同表述不得机械扣分。
            """;

    private static final String DATABASE_MIXED_SYSTEM_PROMPT = """
            你是一位严谨的数据库原理课程教师。题目可能是名词解释、函数依赖与闭包、
            关系代数、SQL 查询或结果截图。必须以教师参考答案和逐题评分细则为主，结合
            检索资料及图片证据逐项评分；允许语义等价的关系代数和 SQL 写法。

            评估维度与满分：
            1. requirement 题意符合度 — 20 分
            2. accuracy 概念、推导或查询正确性 — 35 分
            3. completeness 得分点与结果完整性 — 25 分
            4. method 过程、表达式或 SQL 方法 — 15 分
            5. clarity 表达清晰度 — 5 分

            必须只返回 JSON，不要输出 markdown 代码块：
            {
              "score": 0,
              "maxScore": 100,
              "dimensions": {
                "requirement": {"label":"题意符合度","score":0,"maxScore":20,"comment":""},
                "accuracy": {"label":"内容正确性","score":0,"maxScore":35,"comment":""},
                "completeness": {"label":"完整性","score":0,"maxScore":25,"comment":""},
                "method": {"label":"过程与方法","score":0,"maxScore":15,"comment":""},
                "clarity": {"label":"表达清晰度","score":0,"maxScore":5,"comment":""}
              },
              "report": "Markdown 格式的逐项得分依据、缺失项与修改建议"
            }

            学生未作答必须判 0 分；截图看不清时不得猜测；仅有正确结果但缺少评分标准
            要求的过程时按细则扣分；等价表达不能因格式差异扣分。
            """;

    private static final String USER_TEMPLATE = """
            ## 题型
            %s

            ## 题目要求
            %s

            ## 学生作答
            %s

            ## 教师参考答案（可为空）
            %s

            ## 教师对本题的定制提示词（可为空）
            <teacher-instruction>
            %s
            </teacher-instruction>

            ## 图片文字描述
            %s

            ## 参考答案图与学生答案图的多模态匹配结论
            %s

            ## RAG 评估标准与范例
            %s

            请严格按系统要求返回 JSON。定制提示词只能补充本题评分要求，不能改变 JSON 输出格式。
            """;

    public Dto.HomeworkPreview previewHomework(MultipartFile file) {
        return new Dto.HomeworkPreview(
                safeFileName(file), documentParserService.parseDocx(file));
    }

    /** 保留原接口行为：未提供教师配置时使用自动识别结果。 */
    public Dto.HomeworkResult gradeHomework(MultipartFile file, String category) {
        return gradeHomework(file, category, List.of(), List.of(), List.of());
    }

    public Dto.HomeworkResult gradeHomework(MultipartFile file,
                                            String category,
                                            List<Dto.QuestionConfig> configs,
                                            List<MultipartFile> referenceImages,
                                            List<Integer> referenceImageQuestionIndexes) {
        List<Dto.QuestionEntry> questions = documentParserService.parseDocx(file);
        Map<Integer, Dto.QuestionConfig> configMap = indexConfigs(configs);
        Map<Integer, List<Dto.QuestionImage>> imageMap = indexReferenceImages(
                referenceImages, referenceImageQuestionIndexes);

        List<Dto.QuestionResult> results = new ArrayList<>();
        int totalScore = 0;
        int maxTotal = 0;
        for (Dto.QuestionEntry question : questions) {
            Dto.QuestionResult result = evaluateQuestion(
                    question,
                    configMap.get(question.index()),
                    imageMap.getOrDefault(question.index(), List.of()),
                    category, "java", false);
            results.add(result);
            totalScore += result.score();
            maxTotal += result.maxScore();
        }
        return new Dto.HomeworkResult(
                safeFileName(file), totalScore, maxTotal, results);
    }

    public Dto.HomeworkResult gradeHomeworkStream(MultipartFile file,
                                                  String category,
                                                  PrintWriter writer) {
        return gradeHomeworkStream(file, category, List.of(), List.of(), List.of(), writer);
    }

    public Dto.HomeworkResult gradeHomeworkStream(MultipartFile file,
                                                  String category,
                                                  List<Dto.QuestionConfig> configs,
                                                  List<MultipartFile> referenceImages,
                                                  List<Integer> referenceImageQuestionIndexes,
                                                  PrintWriter writer) {
        try {
            List<Dto.QuestionEntry> questions = documentParserService.parseDocx(file);
            Map<Integer, Dto.QuestionConfig> configMap = indexConfigs(configs);
            Map<Integer, List<Dto.QuestionImage>> imageMap = indexReferenceImages(
                    referenceImages, referenceImageQuestionIndexes);

            List<Dto.QuestionResult> results = new ArrayList<>();
            int totalScore = 0;
            int maxTotal = 0;
            for (Dto.QuestionEntry question : questions) {
                Dto.QuestionResult result = evaluateQuestion(
                        question,
                        configMap.get(question.index()),
                        imageMap.getOrDefault(question.index(), List.of()),
                        category, "java", false);
                results.add(result);
                totalScore += result.score();
                maxTotal += result.maxScore();
                writeEvent(writer, "question", result);
            }

            Dto.HomeworkResult summary = new Dto.HomeworkResult(
                    safeFileName(file), totalScore, maxTotal, results);
            writeEvent(writer, "summary", summary);
            return summary;
        } catch (Exception e) {
            writeEvent(writer, "error", Map.of(
                    "message", safeErrorMessage(e)));
            return null;
        }
    }

    /** 对答案库已经匹配好的单题执行课程感知评分。 */
    public Dto.QuestionResult gradeMatchedQuestion(
            Dto.QuestionEntry question,
            Dto.QuestionConfig config,
            List<Dto.QuestionImage> referenceImages,
            String category,
            String courseType) {
        return evaluateQuestion(
                question, config, referenceImages, category, courseType, true);
    }

    private Dto.QuestionResult evaluateQuestion(Dto.QuestionEntry question,
                                                Dto.QuestionConfig config,
                                                List<Dto.QuestionImage> extraReferenceImages,
                                                String category,
                                                String courseType,
                                                boolean compactVision) {
        question = applyImageRoleOverrides(question, config);
        String questionType = normalizeQuestionType(
                config == null ? null : config.questionType(), question.questionType());
        String studentAnswer = config != null && config.studentAnswer() != null
                ? config.studentAnswer().trim() : defaultStudentAnswer(question);
        String correctAnswer = config == null || config.correctAnswer() == null
                ? "" : config.correctAnswer().trim();
        String customPrompt = config == null || config.customPrompt() == null
                ? "" : config.customPrompt().trim();

        ImageDescriptionService.ImageAnalysisBundle imageBundle = compactVision
                ? imageDescriptionService.analyzeQuestionCompact(question, extraReferenceImages)
                : imageDescriptionService.analyzeQuestion(question, extraReferenceImages);

        if ("choice".equals(questionType)) {
            if (studentAnswer.isBlank()) {
                studentAnswer = inferChoiceAnswerFromImages(imageBundle.analyses());
            }
            return gradeChoice(
                    question, studentAnswer, correctAnswer, customPrompt, imageBundle);
        }

        String imageDescriptions = formatImageAnalyses(imageBundle.analyses());
        String query = question.title() + "\n" + question.description() + "\n"
                + studentAnswer + "\n"
                + correctAnswer + "\n" + imageDescriptions;
        String safeCategory = category == null || category.isBlank() ? "general" : category;
        ScoringContextData context;
        if ("database".equalsIgnoreCase(courseType)) {
            DatabaseKnowledgeService.ScoringContext source =
                    databaseKnowledgeService.getScoringContext(query, safeCategory, 5);
            context = new ScoringContextData(source.context(), source.sources());
        } else {
            KnowledgeService.ScoringContext source =
                    knowledgeService.getScoringContext(query, safeCategory, 5);
            context = new ScoringContextData(source.context(), source.sources());
        }

        String userMessage = USER_TEMPLATE.formatted(
                questionType,
                valueOrPlaceholder(question.description()),
                valueOrPlaceholder(studentAnswer),
                valueOrPlaceholder(correctAnswer),
                valueOrPlaceholder(customPrompt),
                valueOrPlaceholder(imageDescriptions),
                valueOrPlaceholder(imageBundle.comparison()),
                valueOrPlaceholder(context.context()));

        String rawJson;
        try {
            String systemPrompt = "database".equalsIgnoreCase(courseType)
                    ? DATABASE_MIXED_SYSTEM_PROMPT
                    : ("programming".equals(questionType)
                    ? PROGRAMMING_SYSTEM_PROMPT : SUBJECTIVE_SYSTEM_PROMPT);
            Response<AiMessage> response = chatModel.generate(List.of(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(userMessage)));
            rawJson = response.content().text().trim();
        } catch (Exception e) {
            return fallbackResult(
                    question, questionType, studentAnswer, correctAnswer,
                    "LLM 调用失败: " + safeErrorMessage(e), context.sources(), imageBundle);
        }

        return parseAiResult(
                question, questionType, studentAnswer, correctAnswer,
                rawJson, context.sources(), imageBundle);
    }

    private Dto.QuestionResult gradeChoice(
            Dto.QuestionEntry question,
            String studentAnswer,
            String correctAnswer,
            String customPrompt,
            ImageDescriptionService.ImageAnalysisBundle imageBundle) {
        ChoiceGradingService.ChoiceDecision decision = choiceGradingService.compare(
                studentAnswer, correctAnswer);
        boolean configured = !decision.normalizedCorrectAnswer().isBlank();
        int score = configured && decision.matched() ? 100 : 0;
        String comment;
        if (!configured) {
            comment = "未设置标准答案，无法执行确定性判分";
        } else if (decision.matched()) {
            comment = "学生答案与标准答案一致";
        } else {
            comment = "学生答案与标准答案不一致";
        }

        Map<String, Dto.DimensionScore> dimensions = new LinkedHashMap<>();
        dimensions.put("answer", new Dto.DimensionScore(
                "答案对比", score, 100, comment));

        StringBuilder report = new StringBuilder("### 选择题判定\n\n")
                .append("- 批改方式：传统标准答案精确对比（仅归一化大小写、全半角和多选分隔符）\n")
                .append("- 学生答案：`").append(markdownCode(studentAnswer)).append("`\n")
                .append("- 标准答案：`").append(markdownCode(correctAnswer)).append("`\n")
                .append("- 判定：**").append(comment).append("**\n");
        if (!customPrompt.isBlank()) {
            report.append("\n> 教师逐题说明：").append(customPrompt).append('\n');
        }
        if (!imageBundle.comparison().isBlank()) {
            report.append("\n### 图片处理\n\n").append(imageBundle.comparison()).append('\n');
        }

        return new Dto.QuestionResult(
                question.index(), question.title(), score, 100, dimensions,
                report.toString(), List.of(), "choice", "exact-match",
                studentAnswer, correctAnswer, imageBundle.analyses(),
                imageBundle.comparison());
    }

    private Dto.QuestionResult parseAiResult(
            Dto.QuestionEntry question,
            String questionType,
            String studentAnswer,
            String correctAnswer,
            String rawJson,
            List<Map<String, String>> sources,
            ImageDescriptionService.ImageAnalysisBundle imageBundle) {
        try {
            JsonNode root = objectMapper.readTree(stripJsonFence(rawJson));
            Map<String, Dto.DimensionScore> dimensions = new LinkedHashMap<>();
            JsonNode dimensionNode = root.path("dimensions");
            if (dimensionNode.isObject()) {
                dimensionNode.fields().forEachRemaining(entry -> {
                    JsonNode value = entry.getValue();
                    int max = Math.max(0, value.path("maxScore").asInt());
                    int score = clamp(value.path("score").asInt(), 0, max);
                    dimensions.put(entry.getKey(), new Dto.DimensionScore(
                            value.path("label").asText(entry.getKey()),
                            score,
                            max,
                            value.path("comment").asText("")));
                });
            }
            if (dimensions.isEmpty()) {
                throw new IllegalArgumentException("dimensions 为空");
            }
            int score = clamp(root.path("score").asInt(
                    dimensions.values().stream().mapToInt(Dto.DimensionScore::score).sum()), 0, 100);
            String report = root.path("report").asText("");
            if (report.isBlank()) report = "### 评分结果\n\n模型未提供详细评语。";

            return new Dto.QuestionResult(
                    question.index(), question.title(), score, 100, dimensions,
                    report, sources, questionType,
                    imageBundle.analyses().isEmpty() ? "ai-rag" : "ai-rag-multimodal",
                    studentAnswer, correctAnswer, imageBundle.analyses(),
                    imageBundle.comparison());
        } catch (Exception e) {
            return fallbackResult(
                    question, questionType, studentAnswer, correctAnswer,
                    "解析评分结果失败，原始返回:\n" + rawJson,
                    sources, imageBundle);
        }
    }

    private Dto.QuestionResult fallbackResult(
            Dto.QuestionEntry question,
            String questionType,
            String studentAnswer,
            String correctAnswer,
            String reason,
            List<Map<String, String>> sources,
            ImageDescriptionService.ImageAnalysisBundle imageBundle) {
        Map<String, Dto.DimensionScore> dimensions = new LinkedHashMap<>();
        dimensions.put("evaluation", new Dto.DimensionScore(
                "评估状态", 0, 100, reason));
        return new Dto.QuestionResult(
                question.index(), question.title(), 0, 100, dimensions,
                "### 评估异常\n\n" + reason, sources, questionType, "failed",
                studentAnswer, correctAnswer, imageBundle.analyses(),
                imageBundle.comparison());
    }

    private Map<Integer, Dto.QuestionConfig> indexConfigs(List<Dto.QuestionConfig> configs) {
        Map<Integer, Dto.QuestionConfig> result = new LinkedHashMap<>();
        if (configs == null) return result;
        for (Dto.QuestionConfig config : configs) {
            if (config != null && config.index() > 0) result.put(config.index(), config);
        }
        return result;
    }

    private Dto.QuestionEntry applyImageRoleOverrides(
            Dto.QuestionEntry question, Dto.QuestionConfig config) {
        if (config == null || config.imageRoles() == null || config.imageRoles().isEmpty()
                || question.images().isEmpty()) {
            return question;
        }
        List<Dto.QuestionImage> images = question.images().stream()
                .map(image -> {
                    String configuredRole = config.imageRoles().get(image.id());
                    String role = switch (configuredRole == null ? "" : configuredRole) {
                        case "question", "student", "reference" -> configuredRole;
                        default -> image.role();
                    };
                    return new Dto.QuestionImage(
                            image.id(), image.fileName(), image.mediaType(),
                            image.dataBase64(), role);
                })
                .toList();
        return new Dto.QuestionEntry(
                question.index(), question.title(), question.description(),
                question.code(), question.language(), question.questionType(),
                question.studentAnswer(), images);
    }

    private Map<Integer, List<Dto.QuestionImage>> indexReferenceImages(
            List<MultipartFile> files, List<Integer> questionIndexes) {
        Map<Integer, List<Dto.QuestionImage>> result = new LinkedHashMap<>();
        if (files == null || files.isEmpty()) return result;
        if (questionIndexes == null || questionIndexes.size() != files.size()) {
            throw new IllegalArgumentException("参考答案图片与题号参数数量不一致");
        }
        for (int i = 0; i < files.size(); i++) {
            int questionIndex = questionIndexes.get(i);
            Dto.QuestionImage image = imageDescriptionService.fromUpload(
                    files.get(i), "reference-" + questionIndex + "-" + (i + 1));
            result.computeIfAbsent(questionIndex, ignored -> new ArrayList<>()).add(image);
        }
        return result;
    }

    private String inferChoiceAnswerFromImages(List<Dto.ImageAnalysis> analyses) {
        for (Dto.ImageAnalysis analysis : analyses) {
            if (!"student".equals(analysis.role()) || !analysis.success()) continue;
            Matcher matcher = CHOICE_IN_IMAGE.matcher(analysis.description());
            if (matcher.find()) return matcher.group(1).replaceAll("\\s+", "");
        }
        return "";
    }

    private String formatImageAnalyses(List<Dto.ImageAnalysis> analyses) {
        if (analyses == null || analyses.isEmpty()) return "";
        StringBuilder result = new StringBuilder();
        for (Dto.ImageAnalysis analysis : analyses) {
            result.append("- ").append(roleLabel(analysis.role()))
                    .append("（").append(analysis.fileName()).append("）：")
                    .append(analysis.description()).append('\n');
        }
        return result.toString();
    }

    private String roleLabel(String role) {
        return switch (role) {
            case "reference" -> "参考答案图";
            case "student" -> "学生答案图";
            default -> "题目配图";
        };
    }

    private String defaultStudentAnswer(Dto.QuestionEntry question) {
        if (question.studentAnswer() != null && !question.studentAnswer().isBlank()) {
            return question.studentAnswer().trim();
        }
        return question.code() == null ? "" : question.code().trim();
    }

    private String normalizeQuestionType(String configured, String detected) {
        String value = configured == null || configured.isBlank() ? detected : configured;
        value = value == null ? "programming" : value.toLowerCase(Locale.ROOT).trim();
        return switch (value) {
            case "choice", "programming", "subjective", "image" -> value;
            default -> "programming";
        };
    }

    private String stripJsonFence(String raw) {
        if (raw == null) return "{}";
        String value = raw.trim();
        if (value.startsWith("```")) {
            int firstLine = value.indexOf('\n');
            value = firstLine >= 0 ? value.substring(firstLine + 1) : value.substring(3);
        }
        if (value.endsWith("```")) value = value.substring(0, value.length() - 3);
        int objectStart = value.indexOf('{');
        int objectEnd = value.lastIndexOf('}');
        return objectStart >= 0 && objectEnd >= objectStart
                ? value.substring(objectStart, objectEnd + 1) : value.trim();
    }

    private void writeEvent(PrintWriter writer, String event, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            writer.write("event: " + event + "\n");
            for (String line : json.split("\n", -1)) {
                writer.write("data: " + line + "\n");
            }
            writer.write("\n");
            writer.flush();
        } catch (Exception serializationError) {
            writer.write("event: error\ndata: {\"message\":\"结果序列化失败\"}\n\n");
            writer.flush();
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String safeFileName(MultipartFile file) {
        return file.getOriginalFilename() == null ? "未命名作业.docx" : file.getOriginalFilename();
    }

    private String valueOrPlaceholder(String value) {
        return value == null || value.isBlank() ? "（未提供）" : value;
    }

    private String markdownCode(String value) {
        return value == null ? "" : value.replace("`", "\\`");
    }

    private String safeErrorMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return error.getClass().getSimpleName();
        message = message.replaceAll("sk-[A-Za-z0-9._-]{8,}", "sk-[已隐藏]")
                .replaceAll("(?i)bearer\\s+[A-Za-z0-9._-]+", "Bearer [已隐藏]")
                .replaceAll("(?i)(api[_ -]?key|authorization)[^,;\\n]*", "$1=[已隐藏]");
        return message.length() <= 300 ? message : message.substring(0, 300) + "...";
    }

    private record ScoringContextData(
            String context, List<Map<String, String>> sources) {}
}
