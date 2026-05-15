package com.aes.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class KnowledgeService {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    @Value("${aes.knowledge-base.path:data/knowledge_base}")
    private String knowledgeBasePath;

    private final Map<String, Map<String, String>> fileMetadataMap = new LinkedHashMap<>();

    public KnowledgeService(EmbeddingModel embeddingModel,
                            EmbeddingStore<TextSegment> embeddingStore) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
    }

    // ================================================================
    // 初始化：启动时自动构建索引
    // ================================================================
    @PostConstruct
    public void init() {
        syncKnowledgeBase();
    }

    // ================================================================
    // 文档加载
    // ================================================================
    public List<Document> loadDocuments() throws IOException {
        List<Document> documents = new ArrayList<>();
        Path kbPath = resolveKnowledgeBasePath();

        if (!Files.isDirectory(kbPath)) {
            Files.createDirectories(kbPath);
            return documents;
        }

        try (var files = Files.list(kbPath)) {
            List<Path> fileList = files
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();

            for (Path file : fileList) {
                String filename = file.getFileName().toString();
                String ext = filename.toLowerCase();

                try {
                    Document doc;
                    if (ext.endsWith(".pdf")) {
                        doc = FileSystemDocumentLoader.loadDocument(
                                file, new ApachePdfBoxDocumentParser());
                    } else if (ext.endsWith(".docx") || ext.endsWith(".doc")) {
                        doc = FileSystemDocumentLoader.loadDocument(
                                file, new ApacheTikaDocumentParser());
                    } else if (ext.endsWith(".txt")) {
                        doc = FileSystemDocumentLoader.loadDocument(
                                file, new ApacheTikaDocumentParser());
                    } else {
                        continue;
                    }

                    // 注入元数据
                    Map<String, String> meta = extractMetadata(filename);
                    for (var entry : meta.entrySet()) {
                        doc.metadata().put(entry.getKey(), entry.getValue());
                    }
                    doc.metadata().put("source", filename);

                    documents.add(doc);
                    fileMetadataMap.put(filename, meta);
                } catch (Exception e) {
                    System.err.println("[KnowledgeService] 加载失败: "
                            + filename + " - " + e.getMessage());
                }
            }
        }

        return documents;
    }

    // ================================================================
    // 元数据提取（从文件名）
    // ================================================================
    static Map<String, String> extractMetadata(String filename) {
        Map<String, String> meta = new LinkedHashMap<>();

        // 文档类型标签
        if (containsAny(filename, "评估标准", "评分标准", "规范", "标准", "standard", "rubric", "criteria")) {
            meta.put("type", "standard");
        } else if (containsAny(filename, "参考", "范例", "优秀代码", "示例", "sample", "reference", "example")) {
            meta.put("type", "reference");
        }

        // Java 相关标签
        if (containsAny(filename, "代码规范", "编码规范", "style", "convention")) {
            meta.put("category", "code-style");
        } else if (containsAny(filename, "算法", "algorithm", "数据结构")) {
            meta.put("category", "algorithm");
        } else if (containsAny(filename, "设计模式", "design-pattern", "架构")) {
            meta.put("category", "design");
        } else if (containsAny(filename, "性能", "performance", "优化", "optimization")) {
            meta.put("category", "performance");
        } else if (containsAny(filename, "测试", "test", "TDD", "单元测试")) {
            meta.put("category", "testing");
        } else if (containsAny(filename, "安全", "security", "漏洞")) {
            meta.put("category", "security");
        }

        // Java 版本标签
        if (containsAny(filename, "Java8", "Java 8", "java8")) {
            meta.put("java", "8");
        } else if (containsAny(filename, "Java11", "Java 11", "java11")) {
            meta.put("java", "11");
        } else if (containsAny(filename, "Java17", "Java 17", "java17")) {
            meta.put("java", "17");
        } else if (containsAny(filename, "Java21", "Java 21", "java21")) {
            meta.put("java", "21");
        }

        return meta;
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    // ================================================================
    // 索引构建
    // ================================================================
    public int buildIndex(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return 0;
        }

        var ingestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(DocumentSplitters.recursive(500, 50))
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build();

        ingestor.ingest(documents);
        return getChunkCount();
    }

    // ================================================================
    // 检索
    // ================================================================
    public List<EmbeddingMatch<TextSegment>> retrieve(String query,
                                                       Map<String, String> metadataFilter,
                                                       int k) {
        // embed() 返回 Response<Embedding>，需 .content() 取实际向量
        Embedding queryEmbedding = embeddingModel.embed(query).content();

        var requestBuilder = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(k)
                .minScore(0.3);

        // 构建元数据过滤条件
        if (metadataFilter != null && !metadataFilter.isEmpty()) {
            List<Filter> filters = metadataFilter.entrySet().stream()
                    .map(e -> (Filter) new IsEqualTo(e.getKey(), e.getValue()))
                    .collect(Collectors.toList());

            // 多个条件 AND 连接 — 显式调用静态方法避免方法引用歧义
            Filter combined = null;
            for (Filter f : filters) {
                combined = (combined == null) ? f : Filter.and(combined, f);
            }

            if (combined != null) {
                requestBuilder.filter(combined);
            }
        }

        // search() 返回 EmbeddingSearchResult，需 .matches() 取列表
        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(requestBuilder.build());
        return result.matches();
    }

    // ================================================================
    // 获取评估上下文
    // ================================================================
    public ScoringContext getScoringContext(String codeContent,
                                            String category, int k) {
        int halfK = Math.max(k / 2, 1);

        Map<String, String> standardFilter = new LinkedHashMap<>();
        standardFilter.put("type", "standard");
        var standardResults = retrieve(codeContent, standardFilter, halfK);

        Map<String, String> referenceFilter = new LinkedHashMap<>();
        referenceFilter.put("type", "reference");
        var referenceResults = retrieve(codeContent, referenceFilter, halfK);

        List<EmbeddingMatch<TextSegment>> allMatches = new ArrayList<>();
        allMatches.addAll(standardResults);
        allMatches.addAll(referenceResults);

        StringBuilder context = new StringBuilder();
        List<Map<String, String>> sources = new ArrayList<>();

        for (EmbeddingMatch<TextSegment> match : allMatches) {
            TextSegment seg = match.embedded();
            String source = seg.metadata().getString("source");
            String type = seg.metadata().getString("type");
            String label = "standard".equals(type) ? "📋 评估标准" : "📝 参考范例";

            context.append("\n[").append(label)
                    .append(" | 来源: ").append(source).append("]\n");
            context.append(seg.text()).append("\n");
            context.append("---\n");

            Map<String, String> srcInfo = new LinkedHashMap<>();
            srcInfo.put("source", source);
            srcInfo.put("type", type);
            srcInfo.put("text", seg.text().length() > 500
                    ? seg.text().substring(0, 500) + "..." : seg.text());
            sources.add(srcInfo);
        }

        return new ScoringContext(context.toString(), sources);
    }

    // ================================================================
    // 知识库状态
    // ================================================================
    public int getChunkCount() {
        Response<Embedding> qe = embeddingModel.embed("统计查询");
        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(qe.content())
                        .maxResults(1000)
                        .minScore(0.0)
                        .build()
        );
        return result.matches().size();
    }

    public int getFileCount() {
        return fileMetadataMap.size();
    }

    public List<String> getFileList() {
        return new ArrayList<>(fileMetadataMap.keySet());
    }

    public List<Map<String, String>> getMetadataList() {
        List<Map<String, String>> list = new ArrayList<>();
        for (var entry : fileMetadataMap.entrySet()) {
            Map<String, String> m = new LinkedHashMap<>(entry.getValue());
            m.put("source", entry.getKey());
            list.add(m);
        }
        return list;
    }

    // ================================================================
    // 重建索引
    // ================================================================
    public void syncKnowledgeBase() {
        try {
            fileMetadataMap.clear();
            List<Document> docs = loadDocuments();
            if (!docs.isEmpty()) {
                buildIndex(docs);
                System.out.println("[KnowledgeService] 索引构建完成: "
                        + docs.size() + " 个文档, " + getChunkCount() + " 个片段");
            } else {
                System.out.println("[KnowledgeService] 知识库为空");
            }
        } catch (IOException e) {
            System.err.println("[KnowledgeService] 同步失败: " + e.getMessage());
        }
    }

    private Path resolveKnowledgeBasePath() {
        Path path = Paths.get(knowledgeBasePath);
        if (!path.isAbsolute()) {
            Path cwd = Paths.get("").toAbsolutePath();
            // 优先在项目目录内查找，再回退到父目录（兼容旧布局）
            path = cwd.resolve(knowledgeBasePath).normalize();
            if (!Files.isDirectory(path)) {
                Path parent = cwd.getParent();
                if (parent != null) {
                    path = parent.resolve(knowledgeBasePath).normalize();
                }
            }
        }
        System.out.println("[KnowledgeService] 知识库路径: " + path.toAbsolutePath());
        return path;
    }

    // ================================================================
    // 内部类
    // ================================================================
    public record ScoringContext(String context, List<Map<String, String>> sources) {}
}
