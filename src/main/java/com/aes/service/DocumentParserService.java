package com.aes.service;

import com.aes.model.Dto;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Word 作业文档解析器：提取多道编程题（题目描述 + 代码）
 */
@Service
public class DocumentParserService {

    // 匹配常见题号格式：
    // "【第2题】" / "第2题" / "第 2 题" / "题目1" / "题 1" / "1." / "1、" / "一、" / "(1)"
    private static final Pattern QUESTION_SPLITTER = Pattern.compile(
            "(?:^|\\n\\s*)(?:【?第\\s*[一二三四五六七八九十\\d]+\\s*题】?|【?题目\\s*[\\d一二三四五六七八九十]+】?|【?题\\s*[\\d一二三四五六七八九十]+】?|[\\d一二三四五六七八九十]+[\\.．、\\)）]|\\([\\d一二三四五六七八九十]+\\))\\s*",
            Pattern.MULTILINE | Pattern.UNICODE_CHARACTER_CLASS
    );

    // 从第一行中提取题号部分（如 "【第2题】"）
    private static final Pattern TITLE_EXTRACTOR = Pattern.compile(
            "^【?第\\s*[\\d一二三四五六七八九十]+\\s*题】?"
    );

    // 匹配 markdown 风格代码块 ```java ... ```
    private static final Pattern CODE_BLOCK = Pattern.compile(
            "```(?:\\w*)?\\n(.*?)```",
            Pattern.DOTALL
    );

    // 匹配 Java 特征行（用于无代码块标记时的兜底识别）
    private static final Pattern JAVA_MARKER = Pattern.compile(
            "(?:^|\\n)(import\\s+java|package\\s+\\w+|public\\s+(?:class|interface|enum)|class\\s+\\w+|public\\s+static\\s+void\\s+main)"
    );

    // 匹配 "答：" / "答案：" / "解答：" 等标记，作为代码开始的辅助信号
    private static final Pattern ANSWER_MARKER = Pattern.compile(
            "(?:^|\\n)(?:答[：:]|[解答案][析答][：:]|[Rr]ef(?:erence)?[：:]|[Cc]ode[：:])\\s*\\n",
            Pattern.MULTILINE
    );

    // 常见学生头信息关键词（用于 fallback 时主动过滤）
    private static final Pattern STUDENT_HEADER = Pattern.compile(
            "^(?:JV|Java)?作业|作业号|作业[一二三四五六七八九十\\d]+|班级|姓名|学号|课程|教师|日期|专业",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS
    );

    /**
     * 解析上传的 docx 文件，返回题目列表
     */
    public List<Dto.QuestionEntry> parseDocx(MultipartFile file) {
        String text;
        try (InputStream is = file.getInputStream()) {
            Tika tika = new Tika();
            text = tika.parseToString(is);
        } catch (Exception e) {
            throw new RuntimeException("文档解析失败: " + e.getMessage(), e);
        }
        return parseText(text);
    }

    /**
     * 从纯文本中切分题目与代码
     */
    public List<Dto.QuestionEntry> parseText(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        // 标准化换行
        text = text.replace("\r\n", "\n").replace('\r', '\n');

        // 1. 按题号切分
        List<String> rawBlocks = splitByQuestionNumber(text);

        List<Dto.QuestionEntry> result = new ArrayList<>();
        int index = 1;
        for (String block : rawBlocks) {
            Dto.QuestionEntry entry = extractQuestion(block, index++);
            if (entry != null) {
                result.add(entry);
            }
        }
        return result;
    }

    private List<String> splitByQuestionNumber(String text) {
        Matcher m = QUESTION_SPLITTER.matcher(text);
        List<Integer> starts = new ArrayList<>();
        while (m.find()) {
            starts.add(m.start());
        }

        if (starts.size() < 2) {
            // 未识别到多个题号，整体视为一题，但尝试截断前面的头信息
            String single = text.trim();
            if (starts.size() == 1) {
                single = text.substring(starts.get(0)).trim();
            } else {
                // 尝试手动找到第一个题号位置
                Matcher first = QUESTION_SPLITTER.matcher(text);
                if (first.find()) {
                    single = text.substring(first.start()).trim();
                }
            }
            // fallback：主动过滤学生头信息
            single = stripStudentHeader(single);
            return List.of(single);
        }

        List<String> blocks = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            int from = starts.get(i);
            int to = (i + 1 < starts.size()) ? starts.get(i + 1) : text.length();
            blocks.add(text.substring(from, to).trim());
        }
        return blocks;
    }

    private Dto.QuestionEntry extractQuestion(String block, int index) {
        if (block == null || block.isBlank()) return null;

        // 提取标题（题号部分）与描述分离
        String firstLine = block.lines().findFirst().orElse("").trim();
        TitleDesc td = extractTitle(firstLine, block);
        String title = td.title();
        String remainingBlock = td.remaining();

        // 先尝试提取 markdown 代码块
        String code = extractCodeBlock(remainingBlock);
        String description;

        if (code != null && !code.isBlank()) {
            description = CODE_BLOCK.matcher(remainingBlock).replaceAll("\n[CODE]\n").trim();
            description = description.replace("[CODE]", "").replaceAll("\\n{3,}", "\n\n").trim();
        } else {
            // 无代码块标记，尝试按 Java 特征或 "答：" 标记切分
            CodeSplit split = splitByCodeMarkers(remainingBlock);
            code = split.code();
            description = split.description();
        }

        // 清理代码：折叠交替空行 + 去除行尾空格
        code = normalizeCode(code);
        description = cleanBlankLines(description);

        if ((description == null || description.isBlank()) && (code == null || code.isBlank())) {
            return null;
        }

        return new Dto.QuestionEntry(
                index,
                title,
                description != null ? description : "",
                code != null ? code : "",
                "java"
        );
    }

    /**
     * 从第一行提取题号作为标题，剩余文本并入 block 开头
     */
    private TitleDesc extractTitle(String firstLine, String block) {
        Matcher m = TITLE_EXTRACTOR.matcher(firstLine);
        if (m.find()) {
            String title = m.group().trim();
            String restOfFirstLine = firstLine.substring(m.end()).trim();
            // 将剩余文本拼回 block 开头
            String remaining = block.substring(firstLine.length()).trim();
            if (!restOfFirstLine.isEmpty()) {
                remaining = restOfFirstLine + "\n" + remaining;
            }
            return new TitleDesc(title, remaining);
        }
        // 未匹配到题号，整行作为标题
        String title = firstLine.length() > 60
                ? firstLine.substring(0, 60) + "..."
                : firstLine;
        return new TitleDesc(title, block.substring(firstLine.length()).trim());
    }

    private String extractCodeBlock(String text) {
        Matcher m = CODE_BLOCK.matcher(text);
        if (m.find()) {
            StringBuilder sb = new StringBuilder(m.group(1).trim());
            while (m.find()) {
                sb.append("\n\n").append(m.group(1).trim());
            }
            return sb.toString();
        }
        return null;
    }

    private CodeSplit splitByCodeMarkers(String text) {
        // 优先找 "答：" 类标记
        Matcher ans = ANSWER_MARKER.matcher(text);
        int candidateStart = -1;

        if (ans.find()) {
            candidateStart = ans.end();
            // 跳过 "答：" 后的空白/换行
            candidateStart = skipLeadingWhitespace(text, candidateStart);
        }

        Matcher java = JAVA_MARKER.matcher(text);
        if (java.find()) {
            int javaStart = java.start();
            if (candidateStart >= 0 && candidateStart <= javaStart + 20) {
                String desc = text.substring(0, candidateStart).trim();
                // 回退到 candidateStart 之前的换行，保留 "答：" 之前的描述完整性
                desc = text.substring(0, ans.start()).trim();
                String code = text.substring(candidateStart).trim();
                return new CodeSplit(desc, code);
            }
            String desc = text.substring(0, javaStart).trim();
            String code = text.substring(javaStart).trim();
            return new CodeSplit(desc, code);
        }

        if (candidateStart >= 0) {
            String desc = text.substring(0, ans.start()).trim();
            String code = text.substring(candidateStart).trim();
            return new CodeSplit(desc, code);
        }

        return new CodeSplit(text.trim(), "");
    }

    /**
     * 跳过字符串中从 pos 开始的空白字符（空格、制表符、换行）
     */
    private int skipLeadingWhitespace(String text, int pos) {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
        return pos;
    }

    /**
     * 折叠 docx 转换产生的交替空行，并逐行 trim()
     */
    private String normalizeCode(String text) {
        if (text == null || text.isBlank()) return "";
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue; // 去除所有空行（docx 转换产生的交替空行）
            }
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(line);
        }
        return sb.toString();
    }

    private String cleanBlankLines(String text) {
        if (text == null) return "";
        return text.replaceAll("\\n{3,}", "\n\n").trim();
    }

    /**
     * 主动过滤学生头信息（班级、姓名、学号等）
     */
    private String stripStudentHeader(String text) {
        String[] lines = text.split("\n", -1);
        int cutIndex = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            if (STUDENT_HEADER.matcher(line).find()) {
                cutIndex = i + 1;
            } else {
                // 遇到非头信息行，检查是否是头信息的值（如 "张三"、"j1111"）
                // 如果下一行也是头信息，继续；否则停止
                if (i + 1 < lines.length && STUDENT_HEADER.matcher(lines[i + 1].trim()).find()) {
                    cutIndex = i + 1;
                } else if (i > 0 && STUDENT_HEADER.matcher(lines[i - 1].trim()).find()) {
                    // 当前行是头信息的值（如姓名后面的"张三"）
                    cutIndex = i + 1;
                } else {
                    break;
                }
            }
        }
        if (cutIndex >= lines.length) return text;
        StringBuilder sb = new StringBuilder();
        for (int i = cutIndex; i < lines.length; i++) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(lines[i]);
        }
        return sb.toString().trim();
    }

    private record CodeSplit(String description, String code) {}
    private record TitleDesc(String title, String remaining) {}
}
