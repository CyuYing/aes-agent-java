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

@Service
public class DatabaseDocumentParserService {

    private static final Pattern QUESTION_SPLITTER = Pattern.compile(
            "(?:^|\\n\\s*)(?:【?第\\s*[一二三四五六七八九十百\\d]+\\s*题】?|题目\\s*[\\d一二三四五六七八九十百]+|[\\d一二三四五六七八九十百]+[\\.、)）]|\\([\\d一二三四五六七八九十百]+\\))\\s*",
            Pattern.MULTILINE | Pattern.UNICODE_CHARACTER_CLASS
    );

    private static final Pattern TITLE_EXTRACTOR = Pattern.compile(
            "^【?第\\s*[\\d一二三四五六七八九十百]+\\s*题】?"
    );

    private static final Pattern CODE_BLOCK = Pattern.compile(
            "```(?:sql|mysql|h2|\\w*)?\\s*\\n(.*?)```",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );

    private static final Pattern SETUP_MARKER = Pattern.compile(
            "(初始化\\s*SQL|建表\\s*SQL|测试数据|准备数据|数据准备|表结构|数据库脚本|schema|setup|DDL)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS
    );

    private static final Pattern ANSWER_MARKER = Pattern.compile(
            "(学生\\s*SQL|学生答案|答案\\s*SQL|作答\\s*SQL|提交\\s*SQL|查询\\s*SQL|SQL\\s*答案|答案|解答|answer|solution)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS
    );

    private static final Pattern SQL_START = Pattern.compile(
            "(?is)(?:^|\\n)\\s*(WITH|SELECT|CREATE|INSERT|UPDATE|DELETE|ALTER|DROP)\\b"
    );

    private static final Pattern STUDENT_HEADER = Pattern.compile(
            "^(?:DB|SQL|数据库)?作业|作业号|班级|姓名|学号|课程|教师|日期|专业",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS
    );

    public List<Dto.DatabaseQuestionEntry> parseDocx(MultipartFile file) {
        String text;
        try (InputStream is = file.getInputStream()) {
            Tika tika = new Tika();
            text = tika.parseToString(is);
        } catch (Exception e) {
            throw new RuntimeException("数据库作业文档解析失败: " + e.getMessage(), e);
        }
        return parseText(text);
    }

    public List<Dto.DatabaseQuestionEntry> parseText(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        text = text.replace("\r\n", "\n").replace('\r', '\n');
        List<String> rawBlocks = splitByQuestionNumber(text);

        List<Dto.DatabaseQuestionEntry> result = new ArrayList<>();
        int index = 1;
        for (String block : rawBlocks) {
            Dto.DatabaseQuestionEntry entry = extractQuestion(block, index++);
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
            String single = text.trim();
            if (starts.size() == 1) {
                single = text.substring(starts.get(0)).trim();
            }
            return List.of(stripStudentHeader(single));
        }

        List<String> blocks = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            int from = starts.get(i);
            int to = (i + 1 < starts.size()) ? starts.get(i + 1) : text.length();
            blocks.add(text.substring(from, to).trim());
        }
        return blocks;
    }

    private Dto.DatabaseQuestionEntry extractQuestion(String block, int index) {
        if (block == null || block.isBlank()) return null;

        String firstLine = block.lines().findFirst().orElse("").trim();
        TitleDesc td = extractTitle(firstLine, block);
        SqlSections sections = extractSqlSections(td.remaining());

        if (sections.description().isBlank()
                && sections.setupSql().isBlank()
                && sections.answerSql().isBlank()) {
            return null;
        }

        return new Dto.DatabaseQuestionEntry(
                index,
                td.title(),
                cleanText(sections.description()),
                cleanSql(sections.setupSql()),
                cleanSql(sections.answerSql())
        );
    }

    private TitleDesc extractTitle(String firstLine, String block) {
        Matcher m = TITLE_EXTRACTOR.matcher(firstLine);
        if (m.find()) {
            String title = m.group().trim();
            String restOfFirstLine = firstLine.substring(m.end()).trim();
            String remaining = block.substring(firstLine.length()).trim();
            if (!restOfFirstLine.isEmpty()) {
                remaining = restOfFirstLine + "\n" + remaining;
            }
            return new TitleDesc(title, remaining);
        }

        String title = firstLine.length() > 60 ? firstLine.substring(0, 60) + "..." : firstLine;
        return new TitleDesc(title, block.substring(firstLine.length()).trim());
    }

    private SqlSections extractSqlSections(String text) {
        List<CodeBlock> blocks = findCodeBlocks(text);
        if (!blocks.isEmpty()) {
            StringBuilder setup = new StringBuilder();
            StringBuilder answer = new StringBuilder();

            for (int i = 0; i < blocks.size(); i++) {
                CodeBlock block = blocks.get(i);
                MarkerType markerType = nearestMarkerBefore(text, block.start());
                if (markerType == MarkerType.SETUP || (markerType == MarkerType.UNKNOWN && blocks.size() > 1 && i == 0)) {
                    appendSql(setup, block.sql());
                } else {
                    appendSql(answer, block.sql());
                }
            }

            String description = removeCodeBlocks(text);
            return new SqlSections(description, setup.toString(), answer.toString());
        }

        int setupStart = firstMarkerStart(SETUP_MARKER, text);
        int answerStart = firstMarkerStart(ANSWER_MARKER, text);

        if (answerStart >= 0) {
            int firstMarker = setupStart >= 0 ? Math.min(setupStart, answerStart) : answerStart;
            String description = text.substring(0, firstMarker).trim();
            String setup = "";
            if (setupStart >= 0 && setupStart < answerStart) {
                setup = text.substring(markerEnd(SETUP_MARKER, text, setupStart), answerStart).trim();
            }
            String answer = text.substring(markerEnd(ANSWER_MARKER, text, answerStart)).trim();
            return new SqlSections(description, setup, answer);
        }

        Matcher sqlStart = SQL_START.matcher(text);
        if (sqlStart.find()) {
            String description = text.substring(0, sqlStart.start()).trim();
            String answer = text.substring(sqlStart.start()).trim();
            return new SqlSections(description, "", answer);
        }

        return new SqlSections(text.trim(), "", "");
    }

    private List<CodeBlock> findCodeBlocks(String text) {
        List<CodeBlock> blocks = new ArrayList<>();
        Matcher m = CODE_BLOCK.matcher(text);
        while (m.find()) {
            blocks.add(new CodeBlock(m.start(), m.end(), m.group(1).trim()));
        }
        return blocks;
    }

    private MarkerType nearestMarkerBefore(String text, int position) {
        int from = Math.max(0, position - 160);
        String context = text.substring(from, position);
        int setup = lastMarkerStart(SETUP_MARKER, context);
        int answer = lastMarkerStart(ANSWER_MARKER, context);
        if (setup < 0 && answer < 0) return MarkerType.UNKNOWN;
        return setup > answer ? MarkerType.SETUP : MarkerType.ANSWER;
    }

    private int firstMarkerStart(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.start() : -1;
    }

    private int lastMarkerStart(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        int last = -1;
        while (m.find()) {
            last = m.start();
        }
        return last;
    }

    private int markerEnd(Pattern pattern, String text, int from) {
        Matcher m = pattern.matcher(text);
        if (m.find(from)) {
            return m.end();
        }
        return from;
    }

    private String removeCodeBlocks(String text) {
        String withoutBlocks = CODE_BLOCK.matcher(text).replaceAll("\n");
        return SETUP_MARKER.matcher(ANSWER_MARKER.matcher(withoutBlocks).replaceAll("")).replaceAll("").trim();
    }

    private void appendSql(StringBuilder target, String sql) {
        if (sql == null || sql.isBlank()) return;
        if (!target.isEmpty()) target.append("\n\n");
        target.append(sql.trim());
    }

    private String cleanSql(String text) {
        if (text == null) return "";
        return text.replaceAll("\\n{3,}", "\n\n").trim();
    }

    private String cleanText(String text) {
        if (text == null) return "";
        return text.replaceAll("\\n{3,}", "\n\n").trim();
    }

    private String stripStudentHeader(String text) {
        String[] lines = text.split("\n", -1);
        int cutIndex = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            if (STUDENT_HEADER.matcher(line).find()) {
                cutIndex = i + 1;
            } else if (i > 0 && STUDENT_HEADER.matcher(lines[i - 1].trim()).find()) {
                cutIndex = i + 1;
            } else {
                break;
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

    private enum MarkerType { SETUP, ANSWER, UNKNOWN }

    private record CodeBlock(int start, int end, String sql) {}
    private record SqlSections(String description, String setupSql, String answerSql) {}
    private record TitleDesc(String title, String remaining) {}
}
