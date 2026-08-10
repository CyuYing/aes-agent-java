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
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class DatabaseKnowledgeService {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    @Value("${aes.database.knowledge-base.path:data/database_knowledge_base}")
    private String knowledgeBasePath;

    private final Map<String, Map<String, String>> fileMetadataMap = new LinkedHashMap<>();

    public DatabaseKnowledgeService(EmbeddingModel embeddingModel,
                                    @Qualifier("databaseEmbeddingStore") EmbeddingStore<TextSegment> embeddingStore) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
    }

    @PostConstruct
    public void init() {
        try {
            syncKnowledgeBase();
        } catch (RuntimeException e) {
            System.err.println("[DatabaseKnowledgeService] 启动时索引构建失败: " + e.getMessage());
        }
    }

    public List<Document> loadDocuments() throws IOException {
        List<Document> documents = new ArrayList<>();
        Path kbPath = resolveKnowledgeBasePath();

        if (!Files.isDirectory(kbPath)) {
            Files.createDirectories(kbPath);
            return documents;
        }

        try (var files = Files.walk(kbPath)) {
            List<Path> fileList = files
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();

            for (Path file : fileList) {
                String filename = kbPath.relativize(file).toString().replace('\\', '/');
                String ext = filename.toLowerCase();

                try {
                    Document doc;
                    if (ext.endsWith(".pdf")) {
                        doc = FileSystemDocumentLoader.loadDocument(file, new ApachePdfBoxDocumentParser());
                    } else if (ext.endsWith(".docx") || ext.endsWith(".doc") || ext.endsWith(".txt")) {
                        doc = FileSystemDocumentLoader.loadDocument(file, new ApacheTikaDocumentParser());
                    } else {
                        continue;
                    }

                    Map<String, String> meta = extractMetadata(filename);
                    for (var entry : meta.entrySet()) {
                        doc.metadata().put(entry.getKey(), entry.getValue());
                    }
                    doc.metadata().put("source", filename);

                    documents.add(doc);
                    fileMetadataMap.put(filename, meta);
                } catch (Exception e) {
                    System.err.println("[DatabaseKnowledgeService] 加载失败: "
                            + filename + " - " + e.getMessage());
                }
            }
        }

        return documents;
    }

    static Map<String, String> extractMetadata(String filename) {
        Map<String, String> meta = new LinkedHashMap<>();

        if (containsAny(filename, "评估标准", "评分标准", "规范", "标准", "standard", "rubric", "criteria")) {
            meta.put("type", "standard");
        } else if (containsAny(filename, "参考", "范例", "示例", "样例", "sample", "reference", "example")) {
            meta.put("type", "reference");
        } else {
            meta.put("type", "standard");
        }

        if (containsAny(filename, "建表", "DDL", "schema", "table")) {
            meta.put("category", "ddl");
        } else if (containsAny(filename, "增删改", "DML", "insert", "update", "delete")) {
            meta.put("category", "dml");
        } else if (containsAny(filename, "查询", "select", "join", "聚合", "group")) {
            meta.put("category", "query");
        } else if (containsAny(filename, "事务", "transaction", "锁", "并发")) {
            meta.put("category", "transaction");
        } else if (containsAny(filename, "索引", "index", "性能", "优化")) {
            meta.put("category", "performance");
        } else if (containsAny(filename, "安全", "注入", "security")) {
            meta.put("category", "security");
        }

        return meta;
    }

    private static boolean containsAny(String text, String... keywords) {
        String lower = text.toLowerCase();
        for (String kw : keywords) {
            if (lower.contains(kw.toLowerCase())) return true;
        }
        return false;
    }

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

    public List<EmbeddingMatch<TextSegment>> retrieve(String query,
                                                       Map<String, String> metadataFilter,
                                                       int k) {
        Embedding queryEmbedding = embeddingModel.embed(query).content();

        var requestBuilder = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(k)
                .minScore(0.3);

        if (metadataFilter != null && !metadataFilter.isEmpty()) {
            List<Filter> filters = metadataFilter.entrySet().stream()
                    .map(e -> (Filter) new IsEqualTo(e.getKey(), e.getValue()))
                    .collect(Collectors.toList());

            Filter combined = null;
            for (Filter f : filters) {
                combined = (combined == null) ? f : Filter.and(combined, f);
            }

            if (combined != null) {
                requestBuilder.filter(combined);
            }
        }

        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(requestBuilder.build());
        return result.matches();
    }

    public ScoringContext getScoringContext(String sqlContent, String category, int k) {
        int halfK = Math.max(k / 2, 1);

        Map<String, String> standardFilter = new LinkedHashMap<>();
        standardFilter.put("type", "standard");
        var standardResults = retrieve(sqlContent, standardFilter, halfK);

        Map<String, String> referenceFilter = new LinkedHashMap<>();
        referenceFilter.put("type", "reference");
        var referenceResults = retrieve(sqlContent, referenceFilter, halfK);

        List<EmbeddingMatch<TextSegment>> allMatches = new ArrayList<>();
        allMatches.addAll(standardResults);
        allMatches.addAll(referenceResults);

        StringBuilder context = new StringBuilder();
        List<Map<String, String>> sources = new ArrayList<>();

        for (EmbeddingMatch<TextSegment> match : allMatches) {
            TextSegment seg = match.embedded();
            String source = seg.metadata().getString("source");
            String type = seg.metadata().getString("type");
            String label = "reference".equals(type) ? "数据库参考范例" : "数据库评估标准";

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

    public int getChunkCount() {
        Response<Embedding> qe = embeddingModel.embed("数据库知识库统计查询");
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

    public synchronized void syncKnowledgeBase() {
        try {
            fileMetadataMap.clear();
            List<Document> docs = loadDocuments();
            // 同步必须替换旧集合，避免重复点击后相同片段持续累加。
            embeddingStore.removeAll();
            if (!docs.isEmpty()) {
                buildIndex(docs);
                System.out.println("[DatabaseKnowledgeService] 索引构建完成: "
                        + docs.size() + " 个文档, " + getChunkCount() + " 个片段");
            } else {
                System.out.println("[DatabaseKnowledgeService] 知识库为空");
            }
        } catch (IOException e) {
            throw new IllegalStateException("数据库知识库文件读取失败: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("数据库知识库索引重建失败: " + e.getMessage(), e);
        }
    }

    private Path resolveKnowledgeBasePath() {
        Path path = Paths.get(knowledgeBasePath);
        if (!path.isAbsolute()) {
            Path cwd = Paths.get("").toAbsolutePath();
            path = cwd.resolve(knowledgeBasePath).normalize();
        }
        System.out.println("[DatabaseKnowledgeService] 知识库路径: " + path.toAbsolutePath());
        return path;
    }

    public record ScoringContext(String context, List<Map<String, String>> sources) {}
}
