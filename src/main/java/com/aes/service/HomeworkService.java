package com.aes.service;

import com.aes.model.Dto;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 作业批改工作流：解析文档 → RAG 检索 → LLM 评估 → 汇总结果
 */
@Service
public class HomeworkService {

    private final DocumentParserService documentParserService;
    private final KnowledgeService knowledgeService;
    private final ChatLanguageModel chatModel;

    public HomeworkService(DocumentParserService documentParserService,
                           KnowledgeService knowledgeService,
                           ChatLanguageModel chatModel) {
        this.documentParserService = documentParserService;
        this.knowledgeService = knowledgeService;
        this.chatModel = chatModel;
    }

    // ================================================================
    // Prompt
    // ================================================================
    private static final String HOMEWORK_SYSTEM_PROMPT = """
            你是一位资深的 Java 教学专家与代码审查者，拥有 15 年企业级 Java 开发与教学经验。
            你的任务是：根据"题目要求"和"评估标准"，判断学生的 Java 代码是否满足题意，并给出全面的质量评估。

            ## 评估维度与满分
            1. 是否符合题意（功能完整性、边界条件、输入输出要求） — 30 分
            2. 代码规范（命名、格式、注释、代码风格） — 20 分
            3. 逻辑正确性（业务逻辑、异常处理、边界处理） — 20 分
            4. 性能与效率（算法复杂度、资源使用、避免冗余） — 15 分
            5. 可维护性（设计模式、解耦、可测试性、可读性） — 15 分
            总分：100 分

            ## 输出格式（必须严格返回 JSON，不要包含 markdown 代码块标记）
            {
              "score": <总分>,
              "maxScore": 100,
              "dimensions": {
                "requirement": { "label": "是否符合题意", "score": <得分>, "maxScore": 30, "comment": "<具体评语>" },
                "style":       { "label": "代码规范",     "score": <得分>, "maxScore": 20, "comment": "<具体评语>" },
                "logic":       { "label": "逻辑正确性",   "score": <得分>, "maxScore": 20, "comment": "<具体评语>" },
                "performance": { "label": "性能与效率",   "score": <得分>, "maxScore": 15, "comment": "<具体评语>" },
                "maintain":    { "label": "可维护性",     "score": <得分>, "maxScore": 15, "comment": "<具体评语>" }
              },
              "report": "<Markdown 格式的详细评语，包含：总体评价、逐条问题、改进建议。可包含代码片段。>"
            }

            ## 评估原则
            1. 严格对照题目要求，逐条检查代码是否实现了要求的功能。
            2. 参考检索到的"评估标准"进行评分，在评语中引用标准原文。
            3. 指出问题时必须附带具体代码片段或行号，并给出修改建议。
            4. 如果代码完全无法满足题目要求，请在"是否符合题意"维度给出低分，并在报告中详细说明缺失的功能点。
            5. 评语必须具体，不可笼统。
            6. 学生代码可能来自 Word 文档自动转换，行尾空格、多余空行属于格式噪音，请勿因此扣分。请聚焦代码逻辑、命名规范、功能完整性与设计质量本身。
            """;

    private static final String HOMEWORK_USER_TEMPLATE = """
            ## 题目要求

            %s

            ## 学生提交的代码

            ```java
            %s
            ```

            ## 参考评估标准与范例

            %s

            请严格按照系统角色设定的 JSON 格式返回评估结果。""";

    // ================================================================
    // 同步批改
    // ================================================================
    public Dto.HomeworkResult gradeHomework(MultipartFile file, String category) {
        String fileName = file.getOriginalFilename();
        List<Dto.QuestionEntry> questions = documentParserService.parseDocx(file);

        List<Dto.QuestionResult> results = new ArrayList<>();
        int totalScore = 0;
        int maxTotal = 0;

        for (Dto.QuestionEntry q : questions) {
            Dto.QuestionResult r = evaluateQuestion(q, category);
            results.add(r);
            totalScore += r.score();
            maxTotal += r.maxScore();
        }

        return new Dto.HomeworkResult(fileName, totalScore, maxTotal, results);
    }

    // ================================================================
    // 流式批改（SSE，逐题推送）
    // ================================================================
    public void gradeHomeworkStream(MultipartFile file, String category, PrintWriter writer) {
        String fileName = file.getOriginalFilename();
        List<Dto.QuestionEntry> questions = documentParserService.parseDocx(file);

        List<Dto.QuestionResult> results = new ArrayList<>();
        int totalScore = 0;
        int maxTotal = 0;

        for (Dto.QuestionEntry q : questions) {
            Dto.QuestionResult r = evaluateQuestion(q, category);
            results.add(r);
            totalScore += r.score();
            maxTotal += r.maxScore();

            // 推送单题结果（JSON）
            String json = toJson(r);
            writer.write("event: question\ndata: " + json.replace("\n", "\ndata: ") + "\n\n");
            writer.flush();
        }

        // 推送汇总
        Dto.HomeworkResult summary = new Dto.HomeworkResult(fileName, totalScore, maxTotal, results);
        String summaryJson = toJson(summary);
        writer.write("event: summary\ndata: " + summaryJson.replace("\n", "\ndata: ") + "\n\n");
        writer.write("event: done\ndata: [DONE]\n\n");
        writer.flush();
    }

    // ================================================================
    // 单题评估
    // ================================================================
    private Dto.QuestionResult evaluateQuestion(Dto.QuestionEntry question, String category) {
        // 1. RAG 检索
        String query = question.description() + "\n" + question.code();
        KnowledgeService.ScoringContext ctx = knowledgeService.getScoringContext(
                query, category != null ? category : "general", 5);

        // 2. 构建 Prompt
        String userMessage = String.format(HOMEWORK_USER_TEMPLATE,
                question.description(), question.code(), ctx.context());

        // 3. 调用 LLM
        String rawJson;
        try {
            Response<AiMessage> response = chatModel.generate(
                    List.of(SystemMessage.from(HOMEWORK_SYSTEM_PROMPT),
                            UserMessage.from(userMessage)));
            rawJson = response.content().text().trim();
        } catch (Exception e) {
            return fallbackResult(question, "LLM 调用失败: " + e.getMessage(), ctx.sources());
        }

        // 4. 解析 JSON
        return parseResult(question, rawJson, ctx.sources());
    }

    // ================================================================
    // JSON 解析（轻量级，不引入额外库）
    // ================================================================
    private Dto.QuestionResult parseResult(Dto.QuestionEntry question, String rawJson, List<Map<String, String>> sources) {
        // 去掉可能的 markdown 代码块包裹
        String json = rawJson;
        if (json.startsWith("```json")) {
            json = json.substring(7);
        }
        if (json.startsWith("```")) {
            json = json.substring(3);
        }
        if (json.endsWith("```")) {
            json = json.substring(0, json.length() - 3);
        }
        json = json.trim();

        try {
            int score = extractInt(json, "\"score\"\\s*:\\s*(-?\\d+)");
            int maxScore = extractInt(json, "\"maxScore\"\\s*:\\s*(\\d+)");

            Map<String, Dto.DimensionScore> dims = Map.of(
                    "requirement", extractDimension(json, "requirement"),
                    "style",       extractDimension(json, "style"),
                    "logic",       extractDimension(json, "logic"),
                    "performance", extractDimension(json, "performance"),
                    "maintain",    extractDimension(json, "maintain")
            );

            String report = extractString(json, "\"report\"\\s*:\\s*\"");

            return new Dto.QuestionResult(
                    question.index(), question.title(),
                    score, maxScore, dims, report, sources);
        } catch (Exception e) {
            return fallbackResult(question, "解析评分结果失败，原始返回:\n" + rawJson, sources);
        }
    }

    private Dto.DimensionScore extractDimension(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*\\{([^}]+)\\}";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(json);
        if (!m.find()) {
            return new Dto.DimensionScore("未知", 0, 0, "解析失败");
        }
        String block = m.group(1);
        String label = extractString(block, "\"label\"\\s*:\\s*\"");
        int score = extractInt(block, "\"score\"\\s*:\\s*(-?\\d+)");
        int maxScore = extractInt(block, "\"maxScore\"\\s*:\\s*(\\d+)");
        String comment = extractString(block, "\"comment\"\\s*:\\s*\"");
        return new Dto.DimensionScore(label, score, maxScore, comment);
    }

    private int extractInt(String text, String regex) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private String extractString(String text, String prefixRegex) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(prefixRegex).matcher(text);
        if (!m.find()) return "";
        int start = m.end();
        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                switch (c) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case '\\': sb.append('\\'); break;
                    case '"': sb.append('"'); break;
                    case '/': sb.append('/'); break;
                    default: sb.append(c); break;
                }
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') break;
            sb.append(c);
        }
        return sb.toString();
    }

    private Dto.QuestionResult fallbackResult(Dto.QuestionEntry q, String reason, List<Map<String, String>> sources) {
        Map<String, Dto.DimensionScore> dims = Map.of(
                "requirement", new Dto.DimensionScore("是否符合题意", 0, 30, reason),
                "style",       new Dto.DimensionScore("代码规范", 0, 20, reason),
                "logic",       new Dto.DimensionScore("逻辑正确性", 0, 20, reason),
                "performance", new Dto.DimensionScore("性能与效率", 0, 15, reason),
                "maintain",    new Dto.DimensionScore("可维护性", 0, 15, reason)
        );
        return new Dto.QuestionResult(
                q.index(), q.title(), 0, 100, dims,
                "### 评估异常\n\n" + reason, sources);
    }

    // ================================================================
    // 简易 JSON 序列化（手写，避免引入 Jackson 复杂配置）
    // ================================================================
    private String toJson(Object obj) {
        if (obj instanceof Dto.QuestionResult r) {
            StringBuilder dims = new StringBuilder();
            for (var e : r.dimensions().entrySet()) {
                if (!dims.isEmpty()) dims.append(",");
                Dto.DimensionScore d = e.getValue();
                dims.append(String.format("\"%s\":{\"label\":\"%s\",\"score\":%d,\"maxScore\":%d,\"comment\":\"%s\"}",
                        e.getKey(), esc(d.label()), d.score(), d.maxScore(), esc(d.comment())));
            }
            StringBuilder src = new StringBuilder("[");
            for (int i = 0; i < r.sources().size(); i++) {
                if (i > 0) src.append(",");
                Map<String, String> s = r.sources().get(i);
                src.append(String.format("{\"source\":\"%s\",\"type\":\"%s\",\"text\":\"%s\"}",
                        esc(s.get("source")), esc(s.get("type")), esc(s.getOrDefault("text", ""))));
            }
            src.append("]");
            return String.format(
                    "{\"index\":%d,\"title\":\"%s\",\"score\":%d,\"maxScore\":%d,\"dimensions\":{%s},\"report\":\"%s\",\"sources\":%s}",
                    r.index(), esc(r.title()), r.score(), r.maxScore(), dims, esc(r.report()), src);
        }
        if (obj instanceof Dto.HomeworkResult h) {
            StringBuilder qs = new StringBuilder("[");
            for (int i = 0; i < h.questions().size(); i++) {
                if (i > 0) qs.append(",");
                qs.append(toJson(h.questions().get(i)));
            }
            qs.append("]");
            return String.format(
                    "{\"fileName\":\"%s\",\"totalScore\":%d,\"maxTotalScore\":%d,\"questions\":%s}",
                    esc(h.fileName()), h.totalScore(), h.maxTotalScore(), qs);
        }
        return "{}";
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
