package com.aes.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.springframework.stereotype.Service;

import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
public class ScoringService {

    private final StreamingChatLanguageModel streamingChatModel;
    private final KnowledgeService knowledgeService;

    public ScoringService(StreamingChatLanguageModel streamingChatModel,
                          KnowledgeService knowledgeService) {
        this.streamingChatModel = streamingChatModel;
        this.knowledgeService = knowledgeService;
    }

    // ================================================================
    // Prompt 常量（原 ScoringPrompts.java 合并至此）
    // ================================================================
    private static final String SYSTEM_PROMPT = """
            你是一位资深的 Java 技术专家和代码审查者，拥有 15 年企业级 Java 开发与代码评审经验。
            你的任务是根据提供的"评估标准"和"参考范例"，对提交的 Java 代码进行全面、专业、细致的评估。

            ## 评估原则
            1. 严格依据检索到的"评估标准"逐条对照，在评语中明确引用标准的原文。
            2. 将提交代码与"参考范例"进行对比分析，指出差距和优势。
            3. 评价要具体，不可笼统。每指出一个问题，必须附带代码行号或代码片段，并给出修改建议。
            4. 评估采用百分制，从以下四个维度综合打分。

            ## 输出格式要求
            请严格按照以下格式输出：

            ### 一、总体评价
            [用 2-3 句话概括这段代码的整体水平和主要问题]

            ### 二、分维度评分
            | 维度 | 满分 | 得分 | 评语 |
            |------|------|------|------|
            | 代码规范（命名、格式、注释） | 25 | X | [具体评价，引用评估标准原文] |
            | 逻辑正确性（业务逻辑、边界处理） | 25 | X | [具体评价] |
            | 性能与效率（算法复杂度、资源使用） | 25 | X | [具体评价] |
            | 可维护性（设计模式、解耦、可测试性） | 25 | X | [具体评价] |
            | **总分** | **100** | **X** | |

            ### 三、具体问题清单
            逐条列出发现的问题：
            - [行号/位置] [问题描述] → [修改建议]
            - ...

            ### 四、参考范例对比分析
            [如果有相关参考范例，进行对比分析，说明与范例的差距或优势]

            ### 五、改进建议
            1. [具体可操作的改进建议]
            2. ...
            """;

    private static final String USER_TEMPLATE = """
            ## 参考评估标准与范例

            %s

            ## 待评估的 Java 代码

            ```
            %s
            ```

            请按照你的系统角色设定，对以上 Java 代码进行全面评估。必须引用评估标准原文，并与参考范例进行对比。""";

    // ================================================================
    // 流式评分（SSE）
    // ================================================================
    public void scoreStream(String content,
                            String category,
                            PrintWriter writer) throws Exception {

        // 1. RAG 检索
        KnowledgeService.ScoringContext ctx = knowledgeService.getScoringContext(
                content, category != null ? category : "general", 10);

        // 2. 构建消息
        String userMessage = String.format(USER_TEMPLATE, ctx.context(), content);

        List<dev.langchain4j.data.message.ChatMessage> messages = List.of(
                SystemMessage.from(SYSTEM_PROMPT),
                UserMessage.from(userMessage)
        );

        // 3. 流式调用 LLM
        CompletableFuture<Void> future = new CompletableFuture<>();

        streamingChatModel.generate(messages, new StreamingResponseHandler<AiMessage>() {
            @Override
            public void onNext(String token) {
                writer.write("data: " + token.replace("\n", "\ndata: ") + "\n\n");
                writer.flush();
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                StringBuilder sourcesJson = new StringBuilder("[");
                for (int i = 0; i < ctx.sources().size(); i++) {
                    Map<String, String> s = ctx.sources().get(i);
                    if (i > 0) sourcesJson.append(",");
                    sourcesJson.append(String.format(
                            "{\"source\":\"%s\",\"type\":\"%s\",\"text\":\"%s\"}",
                            escapeJson(s.get("source")),
                            escapeJson(s.get("type")),
                            escapeJson(s.get("text"))
                    ));
                }
                sourcesJson.append("]");
                writer.write("event: sources\ndata: " + sourcesJson + "\n\n");
                writer.write("event: done\ndata: [DONE]\n\n");
                writer.flush();
                future.complete(null);
            }

            @Override
            public void onError(Throwable error) {
                writer.write("event: error\ndata: "
                        + error.getMessage().replace("\n", " ") + "\n\n");
                writer.flush();
                future.completeExceptionally(error);
            }
        });

        future.get(120, TimeUnit.SECONDS);
    }

    // ================================================================
    // 同步评分（返回 RAG 上下文）
    // ================================================================
    public KnowledgeService.ScoringContext scoreSync(String content, String category) {
        return knowledgeService.getScoringContext(
                content, category != null ? category : "general", 10);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
