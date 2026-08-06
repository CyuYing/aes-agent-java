package com.aes.service;

import com.aes.model.Dto;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 读取一份课程作业的题目与首页身份信息。题目解析仍由统一的
 * {@link DocumentParserService} 完成，这里只补充批量归档所需的元数据。
 */
@Service
public class AssignmentDocumentService {

    private final DocumentParserService documentParserService;

    public AssignmentDocumentService(DocumentParserService documentParserService) {
        this.documentParserService = documentParserService;
    }

    public ParsedAssignment parse(MultipartFile file) {
        List<Dto.QuestionEntry> questions = documentParserService.parseDocx(file);
        Map<String, String> metadata = extractMetadata(file);
        String fileName = safeFileName(file);
        Dto.StudentIdentity student = identity(metadata, fileName);
        return new ParsedAssignment(fileName, student, questions, metadata);
    }

    /** 只读取身份信息，供单份批改归档使用，不重复解析题目。 */
    public Dto.StudentIdentity parseIdentity(MultipartFile file) {
        return identity(extractMetadata(file), safeFileName(file));
    }

    public String detectCourseType(MultipartFile file, List<Dto.QuestionEntry> questions) {
        StringBuilder evidence = new StringBuilder(safeFileName(file)).append('\n');
        if (questions != null) {
            for (Dto.QuestionEntry question : questions) {
                evidence.append(question.title()).append('\n')
                        .append(question.description()).append('\n')
                        .append(question.studentAnswer()).append('\n');
            }
        }
        String text = evidence.toString().toLowerCase(Locale.ROOT);
        int databaseScore = keywordScore(text,
                "db-", "数据库", "sql", "select ", "关系模式", "函数依赖", "属性集", "闭包");
        int javaScore = keywordScore(text,
                "java-", "java作业", "public class", "abstract class", "system.out", "继承", "多态");
        return databaseScore > javaScore ? "database" : "java";
    }

    private Map<String, String> extractMetadata(MultipartFile file) {
        Map<String, String> result = new LinkedHashMap<>();
        try (InputStream input = file.getInputStream();
             XWPFDocument document = new XWPFDocument(input)) {
            for (XWPFTable table : document.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    List<XWPFTableCell> cells = row.getTableCells();
                    if (cells.size() < 2) continue;
                    String key = normalizeLabel(cells.get(0).getText());
                    String value = clean(cells.get(1).getText());
                    if (isMetadataLabel(key) && !value.isBlank()) {
                        result.putIfAbsent(key, value);
                    }
                }
            }
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String line = clean(paragraph.getText());
                int separator = firstSeparator(line);
                if (separator <= 0) continue;
                String key = normalizeLabel(line.substring(0, separator));
                String value = clean(line.substring(separator + 1));
                if (isMetadataLabel(key) && !value.isBlank()) {
                    result.putIfAbsent(key, value);
                }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("读取作业首页信息失败: " + e.getMessage(), e);
        }
        return result;
    }

    private int firstSeparator(String value) {
        int chinese = value.indexOf('：');
        int ascii = value.indexOf(':');
        if (chinese < 0) return ascii;
        if (ascii < 0) return chinese;
        return Math.min(chinese, ascii);
    }

    private boolean isMetadataLabel(String key) {
        return "姓名".equals(key) || "学号".equals(key)
                || "班级".equals(key) || "作业号".equals(key);
    }

    private String normalizeLabel(String value) {
        String label = clean(value).replaceAll("[：:\\s]", "");
        if (label.contains("姓名")) return "姓名";
        if (label.contains("学号")) return "学号";
        if (label.contains("班级")) return "班级";
        if (label.contains("作业号")) return "作业号";
        return label;
    }

    private String clean(String value) {
        return value == null ? "" : value.replace('\u00a0', ' ')
                .replaceAll("\\s+", " ").trim();
    }

    private String value(Map<String, String> metadata, String key, String fallback) {
        String value = metadata.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private Dto.StudentIdentity identity(Map<String, String> metadata, String fileName) {
        return new Dto.StudentIdentity(
                value(metadata, "姓名", stripExtension(fileName)),
                value(metadata, "学号", ""),
                value(metadata, "班级", ""),
                value(metadata, "作业号", ""));
    }

    private int keywordScore(String text, String... keywords) {
        int score = 0;
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) score++;
        }
        return score;
    }

    private String safeFileName(MultipartFile file) {
        String value = file.getOriginalFilename();
        return value == null || value.isBlank()
                ? "未命名作业.docx" : Path.of(value).getFileName().toString();
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    public record ParsedAssignment(
            String fileName,
            Dto.StudentIdentity student,
            List<Dto.QuestionEntry> questions,
            Map<String, String> metadata
    ) {}
}
