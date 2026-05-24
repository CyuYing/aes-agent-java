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

@Service
public class DatabaseHomeworkService {

    private final DatabaseDocumentParserService documentParserService;
    private final DatabaseExecutionService databaseExecutionService;
    private final DatabaseKnowledgeService knowledgeService;
    private final ChatLanguageModel chatModel;

    public DatabaseHomeworkService(DatabaseDocumentParserService documentParserService,
                                   DatabaseExecutionService databaseExecutionService,
                                   DatabaseKnowledgeService knowledgeService,
                                   ChatLanguageModel chatModel) {
        this.documentParserService = documentParserService;
        this.databaseExecutionService = databaseExecutionService;
        this.knowledgeService = knowledgeService;
        this.chatModel = chatModel;
    }

    private static final String DATABASE_SYSTEM_PROMPT = """
            你是一位资深数据库课程教师与 SQL 审查专家，擅长 MySQL 风格 SQL、关系模型、查询优化与数据库作业评分。
            你的任务是根据题目要求、学生提交的 SQL、系统实际执行证据和数据库评分标准，给出客观、具体、可复核的评分。

            ## 评分维度与满分
            1. 题意与执行结果正确性 — 35 分
            2. SQL 规范与可读性 — 15 分
            3. 关系建模与查询逻辑 — 25 分
            4. 性能与索引意识 — 15 分
            5. 安全性与可维护性 — 10 分
            总分：100 分

            ## 输出格式（必须严格返回 JSON，不要包含 markdown 代码块标记）
            {
              "score": <总分>,
              "maxScore": 100,
              "dimensions": {
                "requirement": { "label": "题意与执行结果正确性", "score": <得分>, "maxScore": 35, "comment": "<具体评语>" },
                "style":       { "label": "SQL 规范与可读性",     "score": <得分>, "maxScore": 15, "comment": "<具体评语>" },
                "logic":       { "label": "关系建模与查询逻辑",   "score": <得分>, "maxScore": 25, "comment": "<具体评语>" },
                "performance": { "label": "性能与索引意识",       "score": <得分>, "maxScore": 15, "comment": "<具体评语>" },
                "security":    { "label": "安全性与可维护性",     "score": <得分>, "maxScore": 10, "comment": "<具体评语>" }
              },
              "report": "<Markdown 格式的详细评语，包含总体评价、执行结果分析、逐条问题和改进建议。>"
            }

            ## 评分原则
            1. 优先参考系统实际执行证据：语法错误、运行错误、查询结果为空或字段不符，都必须反映在评分中。
            2. H2 使用 MySQL 兼容模式执行，若只是方言细节差异，请在评语中说明，不要把所有分数扣光。
            3. 如果 SQL 被安全策略拦截，必须重点评价其安全风险。
            4. 评分要结合题目要求和检索到的数据库知识库标准，避免只凭主观印象。
            5. 评语必须具体到 SQL 片段、执行错误或结果字段，不能只写笼统结论。
            """;

    private static final String DATABASE_USER_TEMPLATE = """
            ## 题目要求

            %s

            ## 初始化 SQL / 测试数据

            ```sql
            %s
            ```

            ## 学生提交的 SQL

            ```sql
            %s
            ```

            ## 系统执行证据

            %s

            ## 数据库评分标准与参考资料

            %s

            请严格按照系统角色设定的 JSON 格式返回数据库作业评分结果。""";

    public Dto.DatabaseHomeworkResult gradeHomework(MultipartFile file, String category) {
        String fileName = file.getOriginalFilename();
        List<Dto.DatabaseQuestionEntry> questions = documentParserService.parseDocx(file);

        List<Dto.DatabaseQuestionResult> results = new ArrayList<>();
        int totalScore = 0;
        int maxTotal = 0;

        for (Dto.DatabaseQuestionEntry q : questions) {
            Dto.DatabaseQuestionResult r = evaluateQuestion(q, category);
            results.add(r);
            totalScore += r.score();
            maxTotal += r.maxScore();
        }

        return new Dto.DatabaseHomeworkResult(fileName, totalScore, maxTotal, results);
    }

    public void gradeHomeworkStream(MultipartFile file, String category, PrintWriter writer) {
        String fileName = file.getOriginalFilename();
        List<Dto.DatabaseQuestionEntry> questions = documentParserService.parseDocx(file);

        List<Dto.DatabaseQuestionResult> results = new ArrayList<>();
        int totalScore = 0;
        int maxTotal = 0;

        for (Dto.DatabaseQuestionEntry q : questions) {
            Dto.DatabaseQuestionResult r = evaluateQuestion(q, category);
            results.add(r);
            totalScore += r.score();
            maxTotal += r.maxScore();

            writer.write("event: question\ndata: " + toJson(r).replace("\n", "\ndata: ") + "\n\n");
            writer.flush();
        }

        Dto.DatabaseHomeworkResult summary = new Dto.DatabaseHomeworkResult(fileName, totalScore, maxTotal, results);
        writer.write("event: summary\ndata: " + toJson(summary).replace("\n", "\ndata: ") + "\n\n");
        writer.write("event: done\ndata: [DONE]\n\n");
        writer.flush();
    }

    private Dto.DatabaseQuestionResult evaluateQuestion(Dto.DatabaseQuestionEntry question, String category) {
        Dto.SqlExecutionResult execution = databaseExecutionService.execute(question.setupSql(), question.answerSql());
        String executionText = formatExecution(execution);
        String query = question.description() + "\n" + question.setupSql() + "\n" + question.answerSql() + "\n" + executionText;
        DatabaseKnowledgeService.ScoringContext ctx = knowledgeService.getScoringContext(
                query, category != null ? category : "general", 5);

        String userMessage = String.format(DATABASE_USER_TEMPLATE,
                question.description(),
                question.setupSql(),
                question.answerSql(),
                executionText,
                ctx.context());

        String rawJson;
        try {
            Response<AiMessage> response = chatModel.generate(
                    List.of(SystemMessage.from(DATABASE_SYSTEM_PROMPT),
                            UserMessage.from(userMessage)));
            rawJson = response.content().text().trim();
        } catch (Exception e) {
            return fallbackResult(question, execution, "LLM 调用失败: " + e.getMessage(), ctx.sources());
        }

        return parseResult(question, execution, rawJson, ctx.sources());
    }

    private String formatExecution(Dto.SqlExecutionResult execution) {
        StringBuilder sb = new StringBuilder();
        sb.append("执行整体结果: ").append(execution.success() ? "成功" : "失败").append("\n");
        if (execution.errorSummary() != null && !execution.errorSummary().isBlank()) {
            sb.append("错误汇总: ").append(execution.errorSummary()).append("\n");
        }
        int i = 1;
        for (Dto.SqlStatementResult statement : execution.statements()) {
            sb.append("\n### 语句 ").append(i++).append("\n");
            sb.append("SQL:\n").append(statement.sql()).append("\n");
            sb.append("结果: ").append(statement.success() ? "成功" : "失败").append("\n");
            if (!statement.success()) {
                sb.append("错误: ").append(statement.error()).append("\n");
            } else if (!statement.rows().isEmpty()) {
                sb.append("字段: ").append(statement.columns()).append("\n");
                sb.append("返回行: ").append(statement.rows()).append("\n");
            } else {
                sb.append("影响行数: ").append(statement.updateCount()).append("\n");
            }
        }
        return sb.toString();
    }

    private Dto.DatabaseQuestionResult parseResult(Dto.DatabaseQuestionEntry question,
                                                   Dto.SqlExecutionResult execution,
                                                   String rawJson,
                                                   List<Map<String, String>> sources) {
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
                    "security",    extractDimension(json, "security")
            );

            String report = extractString(json, "\"report\"\\s*:\\s*\"");
            return new Dto.DatabaseQuestionResult(
                    question.index(), question.title(), score, maxScore, dims, report, execution, sources);
        } catch (Exception e) {
            return fallbackResult(question, execution, "解析评分结果失败，原始返回:\n" + rawJson, sources);
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

    private Dto.DatabaseQuestionResult fallbackResult(Dto.DatabaseQuestionEntry q,
                                                      Dto.SqlExecutionResult execution,
                                                      String reason,
                                                      List<Map<String, String>> sources) {
        Map<String, Dto.DimensionScore> dims = Map.of(
                "requirement", new Dto.DimensionScore("题意与执行结果正确性", execution.success() ? 20 : 0, 35, reason),
                "style",       new Dto.DimensionScore("SQL 规范与可读性", 0, 15, reason),
                "logic",       new Dto.DimensionScore("关系建模与查询逻辑", 0, 25, reason),
                "performance", new Dto.DimensionScore("性能与索引意识", 0, 15, reason),
                "security",    new Dto.DimensionScore("安全性与可维护性", 0, 10, reason)
        );
        int score = dims.values().stream().mapToInt(Dto.DimensionScore::score).sum();
        return new Dto.DatabaseQuestionResult(
                q.index(), q.title(), score, 100, dims,
                "### 评估异常\n\n" + reason + "\n\n### SQL 执行证据\n\n" + formatExecution(execution),
                execution,
                sources
        );
    }

    private String toJson(Object obj) {
        if (obj instanceof Dto.DatabaseQuestionResult r) {
            StringBuilder dims = new StringBuilder();
            for (var e : r.dimensions().entrySet()) {
                if (!dims.isEmpty()) dims.append(",");
                Dto.DimensionScore d = e.getValue();
                dims.append(String.format("\"%s\":{\"label\":\"%s\",\"score\":%d,\"maxScore\":%d,\"comment\":\"%s\"}",
                        e.getKey(), esc(d.label()), d.score(), d.maxScore(), esc(d.comment())));
            }
            return String.format(
                    "{\"index\":%d,\"title\":\"%s\",\"score\":%d,\"maxScore\":%d,\"dimensions\":{%s},\"report\":\"%s\",\"execution\":%s,\"sources\":%s}",
                    r.index(), esc(r.title()), r.score(), r.maxScore(), dims, esc(r.report()),
                    executionToJson(r.execution()), sourcesToJson(r.sources()));
        }
        if (obj instanceof Dto.DatabaseHomeworkResult h) {
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

    private String executionToJson(Dto.SqlExecutionResult execution) {
        StringBuilder statements = new StringBuilder("[");
        for (int i = 0; i < execution.statements().size(); i++) {
            if (i > 0) statements.append(",");
            Dto.SqlStatementResult s = execution.statements().get(i);
            statements.append(String.format(
                    "{\"sql\":\"%s\",\"success\":%s,\"updateCount\":%d,\"columns\":%s,\"rows\":%s,\"error\":\"%s\"}",
                    esc(s.sql()), s.success(), s.updateCount(), stringListToJson(s.columns()),
                    rowsToJson(s.rows()), esc(s.error())));
        }
        statements.append("]");
        return String.format("{\"success\":%s,\"statements\":%s,\"errorSummary\":\"%s\"}",
                execution.success(), statements, esc(execution.errorSummary()));
    }

    private String sourcesToJson(List<Map<String, String>> sources) {
        StringBuilder src = new StringBuilder("[");
        for (int i = 0; i < sources.size(); i++) {
            if (i > 0) src.append(",");
            Map<String, String> s = sources.get(i);
            src.append(String.format("{\"source\":\"%s\",\"type\":\"%s\",\"text\":\"%s\"}",
                    esc(s.get("source")), esc(s.get("type")), esc(s.getOrDefault("text", ""))));
        }
        src.append("]");
        return src.toString();
    }

    private String stringListToJson(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(esc(values.get(i))).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    private String rowsToJson(List<Map<String, String>> rows) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("{");
            int j = 0;
            for (var entry : rows.get(i).entrySet()) {
                if (j++ > 0) sb.append(",");
                sb.append("\"").append(esc(entry.getKey())).append("\":\"").append(esc(entry.getValue())).append("\"");
            }
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
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
