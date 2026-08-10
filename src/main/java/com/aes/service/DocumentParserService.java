package com.aes.service;

import com.aes.model.Dto;
import org.apache.poi.ooxml.POIXMLDocumentPart;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Word 作业解析器。按题号保留每一道题，并把 DOCX 内嵌图片放回所属题目，
 * 为题目预览、选择题精确批改和多模态评分提供结构化输入。
 */
@Service
public class DocumentParserService {

    private final AiQuestionRecognitionService aiQuestionRecognitionService;

    private static final String NUMBER_TOKEN = "[零〇一二三四五六七八九十百\\d]+";

    /** 明确题号，例如“【第 2 题】”“题目三”。 */
    private static final Pattern EXPLICIT_HEADING = Pattern.compile(
            "(?m)^[\\t ]*(?<marker>(?:【\\s*)?(?:第\\s*" + NUMBER_TOKEN
                    + "\\s*题|题目\\s*" + NUMBER_TOKEN + "|题\\s*" + NUMBER_TOKEN
                    + ")(?:\\s*】)?)[\\t ]*(?<label>[^\\n]*)$");

    /** 简写题号，例如“1.”“2、”“（3）”“一、”。 */
    private static final Pattern SIMPLE_HEADING = Pattern.compile(
            "(?m)^[\\t ]*(?<marker>(?:[（(]\\s*" + NUMBER_TOKEN
                    + "\\s*[）)]|" + NUMBER_TOKEN + "[.．、)）]))[\\t ]*(?<label>[^\\n]*)$");

    private static final Pattern ANY_HEADING_AT_START = Pattern.compile(
            "^(?<marker>(?:(?:【\\s*)?(?:第\\s*" + NUMBER_TOKEN
                    + "\\s*题|题目\\s*" + NUMBER_TOKEN + "|题\\s*" + NUMBER_TOKEN
                    + ")(?:\\s*】)?|(?:[（(]\\s*" + NUMBER_TOKEN + "\\s*[）)]|"
                    + NUMBER_TOKEN + "[.．、)）])))[\\t ]*(?<label>[^\\n]*)",
            Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern CODE_BLOCK = Pattern.compile(
            "```(?:[a-zA-Z0-9_+-]*)?[\\t ]*\\n(.*?)```", Pattern.DOTALL);

    private static final Pattern JAVA_MARKER = Pattern.compile(
            "(?m)^[\\t ]*(?:import\\s+java|package\\s+[\\w.]+|public\\s+"
                    + "(?:class|interface|enum|record)|class\\s+\\w+|"
                    + "public\\s+static\\s+void\\s+main)\\b");

    private static final Pattern STUDENT_ANSWER_MARKER = Pattern.compile(
            "(?im)^[\\t ]*(?:学生答案|学生作答|我的答案|我的作答|作答|答)\\s*[：:][\\t ]*");

    private static final Pattern REFERENCE_ANSWER_MARKER = Pattern.compile(
            "(?im)^[\\t ]*(?:标准答案|参考答案|正确答案|教师答案|答案图)\\s*[：:][\\t ]*");

    private static final Pattern CHOICE_OPTION = Pattern.compile(
            "(?im)^[\\t ]*[A-H][.．、:：)）][\\t ]*\\S+");

    private static final Pattern CHOICE_ANSWER = Pattern.compile(
            "(?i)(?:答案(?:是|为)?|选择|选)?[\\t ]*([A-H](?:[\\t ]*[,，、/\\s]?[\\t ]*[A-H])*)");

    private static final Pattern IMAGE_MARKER = Pattern.compile(
            "\\[\\[AES_IMAGE:([a-zA-Z0-9_-]+)]]");

    /** 同时覆盖 DrawingML 的 r:embed 与旧式 VML 的 r:id。 */
    private static final Pattern IMAGE_RELATION = Pattern.compile(
            "(?:r:embed|r:id|r:link)=\"([^\"]+)\"");

    private static final Pattern ROLE_MARKER = Pattern.compile(
            "(?i)(标准答案|参考答案|正确答案|教师答案|答案图|学生答案|学生作答|"
                    + "我的答案|我的作答|作答|答\\s*[：:]|题目图片|题图|如图|下图)");

    private static final Pattern STUDENT_HEADER = Pattern.compile(
            "^(?:JV|Java)?作业|作业号|作业[一二三四五六七八九十\\d]+|"
                    + "班级|姓名|学号|课程|教师|日期|专业",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    /** 供不启动 Spring 的解析器单元测试使用，默认只走本地规则。 */
    public DocumentParserService() {
        this.aiQuestionRecognitionService = null;
    }

    @Autowired
    public DocumentParserService(AiQuestionRecognitionService aiQuestionRecognitionService) {
        this.aiQuestionRecognitionService = aiQuestionRecognitionService;
    }

    /** 解析 DOCX，同时保留段落/表格顺序和内嵌图片。 */
    public List<Dto.QuestionEntry> parseDocx(MultipartFile file) {
        return parseDocxDetailed(file, false, null).questions();
    }

    public ParsedQuestions parseDocxDetailed(MultipartFile file,
                                             boolean aiRecognition,
                                             Integer expectedQuestionCount) {
        try (InputStream input = file.getInputStream();
             XWPFDocument document = new XWPFDocument(input)) {
            ParsedDocument parsed = extractDocument(document);
            return parseDocumentDetailed(parsed.text(), parsed.images(),
                    aiRecognition, expectedQuestionCount);
        } catch (Exception e) {
            throw new RuntimeException("文档解析失败: " + e.getMessage(), e);
        }
    }

    /** 从纯文本切题，供测试和非 DOCX 数据复用。 */
    public List<Dto.QuestionEntry> parseText(String text) {
        return parseDocument(text, Map.of());
    }

    public ParsedQuestions parseTextDetailed(String text,
                                             boolean aiRecognition,
                                             Integer expectedQuestionCount) {
        return parseDocumentDetailed(text, Map.of(), aiRecognition, expectedQuestionCount);
    }

    private List<Dto.QuestionEntry> parseDocument(
            String text, Map<String, Dto.QuestionImage> imageMap) {
        return parseDocumentDetailed(text, imageMap, false, null).questions();
    }

    private ParsedQuestions parseDocumentDetailed(
            String text,
            Map<String, Dto.QuestionImage> imageMap,
            boolean aiRecognition,
            Integer expectedQuestionCount) {
        if (text == null || text.isBlank()) {
            Dto.QuestionRecognitionInfo info = new Dto.QuestionRecognitionInfo(
                    aiRecognition, false, aiRecognition ? "rule-fallback" : "rule",
                    "文档内容为空，未识别到题目", 0, 0, aiRecognition ? 0.0 : 1.0);
            return new ParsedQuestions(List.of(), info);
        }

        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        List<String> ruleBlocks = splitByQuestionNumber(normalized);
        List<String> blocks = ruleBlocks;
        Dto.QuestionRecognitionInfo recognition = new Dto.QuestionRecognitionInfo(
                false, false, "rule", "已使用本地规则识别题目",
                ruleBlocks.size(), ruleBlocks.size(), 1.0);
        if (aiRecognition) {
            if (aiQuestionRecognitionService == null) {
                recognition = new Dto.QuestionRecognitionInfo(
                        true, false, "rule-fallback",
                        "AI 复核服务未启用，已自动使用本地规则",
                        ruleBlocks.size(), ruleBlocks.size(), 0.0);
            } else {
                AiQuestionRecognitionService.Refinement refinement =
                        aiQuestionRecognitionService.refine(
                                normalized, ruleBlocks, expectedQuestionCount);
                blocks = refinement.blocks();
                recognition = refinement.recognition();
            }
        }

        List<Dto.QuestionEntry> result = new ArrayList<>();
        int index = 1;
        for (String block : blocks) {
            Dto.QuestionEntry entry = extractQuestion(block, index, imageMap);
            if (entry != null) {
                result.add(entry);
                index++;
            }
        }
        recognition = new Dto.QuestionRecognitionInfo(
                recognition.requested(), recognition.aiUsed(), recognition.method(),
                recognition.message(), recognition.ruleQuestionCount(), result.size(),
                recognition.confidence());
        return new ParsedQuestions(List.copyOf(result), recognition);
    }

    private ParsedDocument extractDocument(XWPFDocument document) {
        StringBuilder text = new StringBuilder();
        Map<String, Dto.QuestionImage> images = new LinkedHashMap<>();
        int[] sequence = {1};
        appendBodyElements(document.getBodyElements(), text, images, sequence);
        return new ParsedDocument(text.toString(), images);
    }

    private void appendBodyElements(List<IBodyElement> elements,
                                    StringBuilder text,
                                    Map<String, Dto.QuestionImage> images,
                                    int[] sequence) {
        for (IBodyElement element : elements) {
            if (element instanceof XWPFParagraph paragraph) {
                appendParagraph(paragraph, text, images, sequence);
            } else if (element instanceof XWPFTable table) {
                appendTable(table, text, images, sequence);
            }
        }
    }

    private void appendTable(XWPFTable table,
                             StringBuilder text,
                             Map<String, Dto.QuestionImage> images,
                             int[] sequence) {
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                appendBodyElements(cell.getBodyElements(), text, images, sequence);
            }
        }
    }

    private void appendParagraph(XWPFParagraph paragraph,
                                 StringBuilder target,
                                 Map<String, Dto.QuestionImage> images,
                                 int[] sequence) {
        StringBuilder line = new StringBuilder();
        List<XWPFRun> runs = paragraph.getRuns();
        if (runs.isEmpty()) {
            line.append(paragraph.getText());
        } else {
            for (XWPFRun run : runs) {
                String runText = run.text();
                if (runText != null) {
                    line.append(runText);
                }
                Map<String, XWPFPictureData> runPictures = new LinkedHashMap<>();
                for (XWPFPicture picture : run.getEmbeddedPictures()) {
                    XWPFPictureData data = picture.getPictureData();
                    if (data != null) runPictures.put(pictureKey(data), data);
                }

                // Apache POI 的 getEmbeddedPictures() 不包含旧版 Word 的 w:pict/v:imagedata。
                // 从当前 run 的关系 ID 补取图片，并与 DrawingML 结果按媒体部件去重。
                Matcher relationMatcher = IMAGE_RELATION.matcher(run.getCTR().xmlText());
                while (relationMatcher.find()) {
                    POIXMLDocumentPart relation = paragraph.getPart()
                            .getRelationById(relationMatcher.group(1));
                    if (relation instanceof XWPFPictureData data) {
                        runPictures.putIfAbsent(pictureKey(data), data);
                    }
                }
                for (XWPFPictureData data : runPictures.values()) {
                    appendPicture(data, line, images, sequence);
                }
            }
        }
        target.append(line).append('\n');
    }

    private void appendPicture(XWPFPictureData data,
                               StringBuilder line,
                               Map<String, Dto.QuestionImage> images,
                               int[] sequence) {
        if (data.getData() == null) return;
        String id = "img-" + sequence[0]++;
        String mediaType = data.getPackagePart().getContentType();
        if (mediaType == null || mediaType.isBlank()) {
            mediaType = mediaTypeFromFileName(data.getFileName());
        }
        Dto.QuestionImage image = new Dto.QuestionImage(
                id,
                data.getFileName() == null ? id : data.getFileName(),
                mediaType,
                Base64.getEncoder().encodeToString(data.getData()),
                "question");
        images.put(id, image);
        if (!line.isEmpty() && line.charAt(line.length() - 1) != '\n') {
            line.append('\n');
        }
        line.append("[[AES_IMAGE:").append(id).append("]]\n");
    }

    private String pictureKey(XWPFPictureData data) {
        return data.getPackagePart().getPartName().getName();
    }

    private String mediaTypeFromFileName(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/png";
    }

    private List<String> splitByQuestionNumber(String text) {
        List<Boundary> explicit = findBoundaries(EXPLICIT_HEADING, text, true);
        List<Boundary> selected;
        if (!explicit.isEmpty()) {
            selected = explicit;
        } else {
            List<Boundary> simple = findBoundaries(SIMPLE_HEADING, text, false);
            selected = bestSequentialRun(simple);
        }

        if (selected.isEmpty()) {
            return List.of(stripStudentHeader(text.trim()));
        }

        selected = new ArrayList<>(selected);
        selected.sort(Comparator.comparingInt(Boundary::start));
        List<String> blocks = new ArrayList<>();
        for (int i = 0; i < selected.size(); i++) {
            int from = selected.get(i).start();
            int to = i + 1 < selected.size() ? selected.get(i + 1).start() : text.length();
            String block = text.substring(from, to).trim();
            if (!block.isBlank()) {
                blocks.add(block);
            }
        }
        return blocks.isEmpty() ? List.of(stripStudentHeader(text.trim())) : blocks;
    }

    private List<Boundary> findBoundaries(Pattern pattern, String text, boolean explicit) {
        Matcher matcher = pattern.matcher(text);
        List<Boundary> result = new ArrayList<>();
        while (matcher.find()) {
            String marker = matcher.group("marker");
            String label = matcher.group("label");
            if (!explicit && looksLikeCodeOrOption(label)) {
                continue;
            }
            result.add(new Boundary(
                    matcher.start(), marker, parseOrdinal(marker), explicit));
        }
        return result;
    }

    private boolean looksLikeCodeOrOption(String label) {
        if (label == null) return false;
        String value = label.trim();
        return value.startsWith("//") || value.startsWith("/*")
                || value.startsWith("public ") || value.startsWith("private ")
                || value.startsWith("protected ") || value.startsWith("return ")
                || value.startsWith("System.") || value.startsWith("case ");
    }

    /**
     * 简写题号容易与题内编号混淆，因此选择最长的连续编号序列。
     * 只有一个候选时仍保留，确保单题作业不会被丢弃。
     */
    private List<Boundary> bestSequentialRun(List<Boundary> candidates) {
        if (candidates.size() <= 1) return candidates;
        List<Boundary> best = List.of();
        for (int start = 0; start < candidates.size(); start++) {
            List<Boundary> run = new ArrayList<>();
            run.add(candidates.get(start));
            for (int i = start + 1; i < candidates.size(); i++) {
                Boundary previous = run.get(run.size() - 1);
                Boundary next = candidates.get(i);
                if (previous.ordinal() > 0 && next.ordinal() == previous.ordinal() + 1) {
                    run.add(next);
                } else {
                    break;
                }
            }
            if (run.size() > best.size()) {
                best = List.copyOf(run);
            }
        }
        return best.size() >= 2 ? best : List.of(candidates.get(0));
    }

    private Dto.QuestionEntry extractQuestion(
            String block, int index, Map<String, Dto.QuestionImage> imageMap) {
        if (block == null || block.isBlank()) return null;

        String trimmed = block.trim();
        Matcher heading = ANY_HEADING_AT_START.matcher(trimmed);
        String title = "第" + index + "题";
        String content = trimmed;
        boolean hasExplicitHeading = false;
        if (heading.find()) {
            hasExplicitHeading = true;
            String label = heading.group("label") == null ? "" : heading.group("label").trim();
            title = heading.group("marker").trim() + (label.isBlank() ? "" : " " + label);
            content = trimmed.substring(heading.end()).trim();
        }

        List<Dto.QuestionImage> images = extractImages(content, imageMap);
        String cleanContent = cleanBlankLines(IMAGE_MARKER.matcher(content).replaceAll("\n"));
        AnswerSection answerSection = splitAnswerSection(cleanContent);
        String promptText = answerSection.prompt();
        String answerText = answerSection.answer();

        boolean choice = isChoiceQuestion(title + "\n" + promptText);
        String code = "";
        String studentAnswer = answerText;
        String description = promptText;

        if (choice) {
            studentAnswer = extractChoiceAnswer(answerText);
        } else if (!answerText.isBlank()) {
            String answerCode = extractCodeBlocks(answerText);
            if (!answerCode.isBlank()) {
                code = answerCode;
                studentAnswer = code;
            } else if (JAVA_MARKER.matcher(answerText).find()) {
                code = normalizeCode(answerText);
                studentAnswer = code;
            }
        } else {
            String blockCode = extractCodeBlocks(cleanContent);
            if (!blockCode.isBlank()) {
                code = blockCode;
                description = cleanBlankLines(CODE_BLOCK.matcher(cleanContent).replaceAll("\n"));
                studentAnswer = code;
            } else {
                CodeSplit split = splitAtJavaCode(cleanContent);
                if (!split.code().isBlank()) {
                    description = split.description();
                    code = split.code();
                    studentAnswer = code;
                }
            }
        }

        code = normalizeCode(code);
        description = cleanBlankLines(description);
        studentAnswer = cleanBlankLines(studentAnswer);
        String questionType = detectQuestionType(choice, code, studentAnswer, images);

        // 明确出现题号但学生留空时也必须保留该题，否则批量批改会把“未作答”
        // 错误地当成“题目不存在”，并导致后续题号与答案库错位。
        if (!hasExplicitHeading && description.isBlank() && code.isBlank()
                && studentAnswer.isBlank() && images.isEmpty()) {
            return null;
        }

        return new Dto.QuestionEntry(
                index,
                title,
                description,
                code,
                "java",
                questionType,
                studentAnswer,
                images);
    }

    private List<Dto.QuestionImage> extractImages(
            String content, Map<String, Dto.QuestionImage> imageMap) {
        List<Dto.QuestionImage> result = new ArrayList<>();
        Matcher matcher = IMAGE_MARKER.matcher(content);
        while (matcher.find()) {
            Dto.QuestionImage source = imageMap.get(matcher.group(1));
            if (source == null) continue;
            String role = inferImageRole(content.substring(0, matcher.start()));
            result.add(new Dto.QuestionImage(
                    source.id(), source.fileName(), source.mediaType(),
                    source.dataBase64(), role));
        }
        return result;
    }

    private String inferImageRole(String prefix) {
        Matcher matcher = ROLE_MARKER.matcher(prefix);
        String last = "";
        while (matcher.find()) {
            last = matcher.group(1);
        }
        if (last.contains("标准") || last.contains("参考")
                || last.contains("正确") || last.contains("教师")
                || last.contains("答案图")) {
            return "reference";
        }
        if (last.contains("学生") || last.contains("我的")
                || last.contains("作答") || last.matches("(?i)答\\s*[：:]")) {
            return "student";
        }
        return "question";
    }

    private AnswerSection splitAnswerSection(String text) {
        Matcher student = STUDENT_ANSWER_MARKER.matcher(text);
        if (student.find()) {
            return new AnswerSection(
                    cleanBlankLines(text.substring(0, student.start())),
                    cleanBlankLines(text.substring(student.end())));
        }
        return new AnswerSection(text.trim(), "");
    }

    private boolean isChoiceQuestion(String text) {
        if (text.contains("选择题") || text.contains("单选") || text.contains("多选")) {
            return true;
        }
        Matcher matcher = CHOICE_OPTION.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
            if (count >= 2) return true;
        }
        return false;
    }

    private String extractChoiceAnswer(String answerText) {
        if (answerText == null || answerText.isBlank()) return "";
        String firstLine = answerText.lines().findFirst().orElse("").trim();
        Matcher matcher = CHOICE_ANSWER.matcher(firstLine);
        return matcher.find() ? matcher.group(1).replaceAll("\\s+", "") : firstLine;
    }

    private String extractCodeBlocks(String text) {
        if (text == null || text.isBlank()) return "";
        Matcher matcher = CODE_BLOCK.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            if (!result.isEmpty()) result.append("\n\n");
            result.append(matcher.group(1).trim());
        }
        return result.toString();
    }

    private CodeSplit splitAtJavaCode(String text) {
        Matcher java = JAVA_MARKER.matcher(text);
        if (!java.find()) return new CodeSplit(text.trim(), "");
        return new CodeSplit(
                cleanBlankLines(text.substring(0, java.start())),
                normalizeCode(text.substring(java.start())));
    }

    private String detectQuestionType(boolean choice,
                                      String code,
                                      String studentAnswer,
                                      List<Dto.QuestionImage> images) {
        if (choice) return "choice";
        if (code != null && !code.isBlank()) return "programming";
        boolean hasAnswerImage = images.stream()
                .anyMatch(image -> "student".equals(image.role())
                        || "reference".equals(image.role()));
        if (hasAnswerImage && (studentAnswer == null || studentAnswer.isBlank())) return "image";
        return "subjective";
    }

    private int parseOrdinal(String marker) {
        String token = marker.replaceAll("[【】第题目（()）.．、\\s]", "");
        if (token.matches("\\d+")) {
            try {
                return Integer.parseInt(token);
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return parseChineseNumber(token);
    }

    private int parseChineseNumber(String value) {
        if (value == null || value.isBlank()) return -1;
        if ("零".equals(value) || "〇".equals(value)) return 0;
        int total = 0;
        int current = 0;
        for (char c : value.toCharArray()) {
            if (c == '十') {
                total += (current == 0 ? 1 : current) * 10;
                current = 0;
            } else if (c == '百') {
                total += (current == 0 ? 1 : current) * 100;
                current = 0;
            } else {
                int digit = "零一二三四五六七八九".indexOf(c);
                if (digit < 0) return -1;
                current = digit;
            }
        }
        return total + current;
    }

    private String normalizeCode(String text) {
        if (text == null || text.isBlank()) return "";
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder result = new StringBuilder();
        boolean previousBlank = false;
        for (String raw : lines) {
            String line = raw.stripTrailing();
            boolean blank = line.isBlank();
            if (blank && previousBlank) continue;
            if (!result.isEmpty()) result.append('\n');
            result.append(blank ? "" : line);
            previousBlank = blank;
        }
        return result.toString().trim();
    }

    private String cleanBlankLines(String text) {
        if (text == null) return "";
        return text.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[\\t ]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
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
        if (cutIndex == 0 || cutIndex >= lines.length) return text;
        StringBuilder result = new StringBuilder();
        for (int i = cutIndex; i < lines.length; i++) {
            if (!result.isEmpty()) result.append('\n');
            result.append(lines[i]);
        }
        return result.toString().trim();
    }

    private record ParsedDocument(String text, Map<String, Dto.QuestionImage> images) {}
    public record ParsedQuestions(
            List<Dto.QuestionEntry> questions,
            Dto.QuestionRecognitionInfo recognition
    ) {}
    private record Boundary(int start, String marker, int ordinal, boolean explicit) {}
    private record AnswerSection(String prompt, String answer) {}
    private record CodeSplit(String description, String code) {}
}
