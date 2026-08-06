package com.aes.service;

import com.aes.model.Dto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 图片多模态处理：先把题图、参考答案图和学生作答图转写为文字，
 * 再把两组答案图一并交给视觉模型直接比对。
 */
@Service
public class ImageDescriptionService {

    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    private static final int MAX_IMAGES_PER_QUESTION = 8;
    private static final Set<String> SUPPORTED_MEDIA_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp");

    private final ChatLanguageModel visionModel;
    private final boolean visionEnabled;
    private final ObjectMapper objectMapper;

    public ImageDescriptionService(
            @Qualifier("visionChatModel") ChatLanguageModel visionModel,
            @Value("${vision.enabled:false}") boolean visionEnabled,
            ObjectMapper objectMapper) {
        this.visionModel = visionModel;
        this.visionEnabled = visionEnabled;
        this.objectMapper = objectMapper;
    }

    public Dto.QuestionImage fromUpload(MultipartFile file, String id) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("参考答案图片为空");
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("参考答案图片不能超过 5MB: " + file.getOriginalFilename());
        }
        String mediaType = normalizeMediaType(file.getContentType(), file.getOriginalFilename());
        if (!SUPPORTED_MEDIA_TYPES.contains(mediaType)) {
            throw new IllegalArgumentException("不支持的图片格式: " + mediaType);
        }
        try {
            return new Dto.QuestionImage(
                    id,
                    file.getOriginalFilename() == null ? id : file.getOriginalFilename(),
                    mediaType,
                    Base64.getEncoder().encodeToString(file.getBytes()),
                    "reference");
        } catch (IOException e) {
            throw new IllegalArgumentException("读取参考答案图片失败: " + e.getMessage(), e);
        }
    }

    public ImageAnalysisBundle analyzeQuestion(Dto.QuestionEntry question,
                                               List<Dto.QuestionImage> extraReferenceImages) {
        List<Dto.QuestionImage> allImages = new ArrayList<>(question.images());
        if (extraReferenceImages != null) allImages.addAll(extraReferenceImages);

        List<Dto.QuestionImage> limited = distinctImages(allImages).stream()
                .limit(MAX_IMAGES_PER_QUESTION)
                .toList();
        List<Dto.ImageAnalysis> analyses = new ArrayList<>();
        for (Dto.QuestionImage image : limited) {
            analyses.add(describe(image, question.description()));
        }

        List<Dto.QuestionImage> referenceImages = limited.stream()
                .filter(image -> "reference".equals(image.role()))
                .toList();
        List<Dto.QuestionImage> studentImages = limited.stream()
                .filter(image -> "student".equals(image.role()))
                .toList();

        String comparison = "";
        if (!referenceImages.isEmpty() && !studentImages.isEmpty()) {
            comparison = compareImages(
                    referenceImages, studentImages, analyses, question.description());
        } else if (!studentImages.isEmpty()) {
            comparison = "已提取学生答案图片；本题未提供参考答案图片，将依据题意和图片文字描述评分。";
        } else if (!referenceImages.isEmpty()) {
            comparison = "已提取参考答案图片，但学生作答中未识别到答案图片。";
        }

        if (allImages.size() > MAX_IMAGES_PER_QUESTION) {
            comparison += (comparison.isBlank() ? "" : "\n")
                    + "图片数量超过单题上限，仅处理前 " + MAX_IMAGES_PER_QUESTION + " 张。";
        }
        return new ImageAnalysisBundle(List.copyOf(analyses), comparison);
    }

    /**
     * 批量答案库场景的一次调用版本：同一道题的题图、参考图和学生图在一个
     * 多模态请求中联合转写并比较，避免逐图调用造成重复费用与参考图重复处理。
     */
    public ImageAnalysisBundle analyzeQuestionCompact(
            Dto.QuestionEntry question, List<Dto.QuestionImage> extraReferenceImages) {
        List<Dto.QuestionImage> allImages = new ArrayList<>(question.images());
        if (extraReferenceImages != null) allImages.addAll(extraReferenceImages);
        List<Dto.QuestionImage> distinct = distinctImages(allImages);
        List<Dto.QuestionImage> limited = distinct.stream()
                .limit(MAX_IMAGES_PER_QUESTION)
                .toList();
        if (limited.isEmpty()) return new ImageAnalysisBundle(List.of(), "");

        if (!visionEnabled) {
            List<Dto.ImageAnalysis> disabled = limited.stream()
                    .map(image -> new Dto.ImageAnalysis(
                            image.id(), image.fileName(), image.role(),
                            "多模态图片处理未启用。请配置百炼视觉模型后重试。", false))
                    .toList();
            return new ImageAnalysisBundle(disabled, "未执行图片联合核验：多模态处理未启用。");
        }

        List<Content> contents = new ArrayList<>();
        contents.add(TextContent.from("""
                你正在核验课程作业图片。系统已把同题图片去重，并可能将它们放大后
                纵向拼成一张联系表；每个分区顶部的 #序号 与下面的位置说明一一对应。
                图片角色含义：
                question=题目配图，reference=教师参考答案图，student=学生答案图。

                请忠实转写每张图中的文字、公式、关系代数符号、代码、表格列名与数据行，
                再结合题意比较学生证据与参考证据。看不清的内容必须明确标注，禁止猜测。
                只返回 JSON，不要使用 Markdown 代码块：
                {
                  "images":[{"position":1,"description":"客观转写"}],
                  "comparison":"相同点、缺失项、错误项和匹配度（高/中/低）"
                }

                题目上下文：%s
                """.formatted(abbreviate(question.description(), 2200))));

        List<Dto.QuestionImage> supported = new ArrayList<>();
        int position = 1;
        for (Dto.QuestionImage image : limited) {
            String mediaType = normalizeMediaType(image.mediaType(), image.fileName());
            if (image.dataBase64() == null || image.dataBase64().isBlank()
                    || !SUPPORTED_MEDIA_TYPES.contains(mediaType)) {
                continue;
            }
            supported.add(image);
            contents.add(TextContent.from("位置 " + position++ + "，角色 "
                    + image.role() + "，文件 " + image.fileName()));
        }
        if (supported.isEmpty()) {
            return new ImageAnalysisBundle(
                    limited.stream().map(image -> failed(image, "图片格式或数据不可用")).toList(),
                    "没有可发送给视觉模型的有效图片。");
        }

        ContactSheet contactSheet = createContactSheet(supported);
        if (contactSheet != null) {
            contents.add(TextContent.from("下面是一张按位置顺序排列并放大的图片联系表："));
            contents.add(ImageContent.from(contactSheet.dataBase64(), "image/png"));
        } else {
            // 极少数 JVM 不支持的图片编码回退为逐图发送，保证功能不因拼图失败而中断。
            for (Dto.QuestionImage image : supported) {
                contents.add(ImageContent.from(
                        image.dataBase64(), normalizeMediaType(image.mediaType(), image.fileName())));
            }
        }

        try {
            Response<AiMessage> response = visionModel.generate(
                    List.of(UserMessage.from(contents)));
            String raw = response.content().text();
            if (raw == null || raw.isBlank()) {
                throw new IllegalStateException("视觉模型返回了空结果");
            }
            JsonNode root = objectMapper.readTree(stripJsonFence(raw));
            Map<Integer, String> descriptions = new LinkedHashMap<>();
            for (JsonNode item : root.path("images")) {
                int itemPosition = item.path("position").asInt(-1);
                String description = item.path("description").asText("").trim();
                if (itemPosition > 0 && !description.isBlank()) {
                    descriptions.put(itemPosition, description);
                }
            }
            List<Dto.ImageAnalysis> analyses = new ArrayList<>();
            for (int i = 0; i < supported.size(); i++) {
                Dto.QuestionImage image = supported.get(i);
                String description = descriptions.getOrDefault(i + 1, "联合分析未单独返回该图的转写。");
                analyses.add(new Dto.ImageAnalysis(
                        image.id(), image.fileName(), image.role(), description,
                        descriptions.containsKey(i + 1)));
            }
            String comparison = root.path("comparison").asText("").trim();
            if (comparison.isBlank()) comparison = "视觉模型完成了图片转写，但未返回明确的对比结论。";
            if (distinct.size() > MAX_IMAGES_PER_QUESTION) {
                comparison += "\n图片数量超过上限，仅联合核验前 " + MAX_IMAGES_PER_QUESTION + " 张。";
            }
            return new ImageAnalysisBundle(List.copyOf(analyses), comparison);
        } catch (Exception error) {
            String reason = "图片联合分析失败: " + safeMessage(error);
            return new ImageAnalysisBundle(
                    supported.stream().map(image -> failed(image, reason)).toList(), reason);
        }
    }

    private Dto.ImageAnalysis describe(Dto.QuestionImage image, String questionContext) {
        if (!visionEnabled) {
            return new Dto.ImageAnalysis(
                    image.id(), image.fileName(), image.role(),
                    "多模态图片处理未启用。请配置 VISION_API_KEY、VISION_BASE_URL、VISION_MODEL，并设置 VISION_ENABLED=true。",
                    false);
        }
        if (image.dataBase64() == null || image.dataBase64().isBlank()) {
            return failed(image, "图片数据为空");
        }
        String mediaType = normalizeMediaType(image.mediaType(), image.fileName());
        if (!SUPPORTED_MEDIA_TYPES.contains(mediaType)) {
            return failed(image, "视觉模型暂不支持该图片格式: " + mediaType);
        }

        String roleLabel = switch (image.role()) {
            case "reference" -> "教师提供的参考答案图";
            case "student" -> "学生答案图";
            default -> "题目配图";
        };
        String prompt = """
                你正在为作业批改转写图片。这是一张%s。
                请忠实、完整地描述图片：
                1. 转写所有可辨认文字、代码、公式和数值；
                2. 描述图表、流程图、界面、连线、相对位置和关键视觉关系；
                3. 不要猜测看不清的内容，无法辨认处明确标注；
                4. 只输出客观描述，不评分。

                题目上下文：%s
                """.formatted(roleLabel, abbreviate(questionContext, 1500));

        try {
            UserMessage message = UserMessage.from(List.of(
                    TextContent.from(prompt),
                    ImageContent.from(image.dataBase64(), mediaType)));
            Response<AiMessage> response = visionModel.generate(List.of(message));
            String description = response.content().text();
            if (description == null || description.isBlank()) {
                return failed(image, "视觉模型返回了空描述");
            }
            return new Dto.ImageAnalysis(
                    image.id(), image.fileName(), image.role(), description.trim(), true);
        } catch (Exception e) {
            return failed(image, "图片描述失败: " + safeMessage(e));
        }
    }

    private String compareImages(List<Dto.QuestionImage> references,
                                 List<Dto.QuestionImage> students,
                                 List<Dto.ImageAnalysis> analyses,
                                 String questionContext) {
        if (!visionEnabled) {
            return "未执行参考图与学生图的多模态匹配：图片处理未启用。";
        }

        List<Content> contents = new ArrayList<>();
        contents.add(TextContent.from("""
                你正在比较教师参考答案图与学生答案图。请结合题意和随后图片，
                逐项指出相同点、缺失项、错误项及关键差异，并给出“匹配度：高/中/低”。
                不要仅凭版式或颜色判定，优先比较文字、代码、公式、结构和语义。

                题目上下文：%s

                已生成的图片文字描述：
                %s
                """.formatted(
                abbreviate(questionContext, 1500), formatAnalyses(analyses))));

        appendImages(contents, "参考答案图", references);
        appendImages(contents, "学生答案图", students);

        try {
            Response<AiMessage> response = visionModel.generate(
                    List.of(UserMessage.from(contents)));
            String result = response.content().text();
            return result == null || result.isBlank()
                    ? "视觉模型未返回图片匹配结论。" : result.trim();
        } catch (Exception e) {
            return "参考图与学生图匹配失败: " + safeMessage(e);
        }
    }

    private void appendImages(List<Content> contents,
                              String label,
                              List<Dto.QuestionImage> images) {
        int index = 1;
        for (Dto.QuestionImage image : images) {
            String mediaType = normalizeMediaType(image.mediaType(), image.fileName());
            if (image.dataBase64() == null || image.dataBase64().isBlank()
                    || !SUPPORTED_MEDIA_TYPES.contains(mediaType)) {
                continue;
            }
            contents.add(TextContent.from(label + " " + index++ + "：" + image.fileName()));
            contents.add(ImageContent.from(image.dataBase64(), mediaType));
        }
    }

    /**
     * DOCX 常会把公式符号保存成十几像素的小 PNG，也可能在多个绘图节点重复引用
     * 同一媒体对象。先按角色和内容去重，再把图片放大拼成一个 PNG，可同时避免
     * 多图请求延迟和低分辨率图片被视觉服务拒绝。
     */
    private List<Dto.QuestionImage> distinctImages(List<Dto.QuestionImage> images) {
        Map<String, Dto.QuestionImage> unique = new LinkedHashMap<>();
        for (Dto.QuestionImage image : images) {
            if (image == null) continue;
            String key = String.valueOf(image.role()) + '\u0000'
                    + String.valueOf(image.mediaType()) + '\u0000'
                    + String.valueOf(image.dataBase64());
            unique.putIfAbsent(key, image);
        }
        return List.copyOf(unique.values());
    }

    private ContactSheet createContactSheet(List<Dto.QuestionImage> images) {
        if (images.isEmpty()) return null;
        try {
            List<BufferedImage> decoded = new ArrayList<>();
            for (Dto.QuestionImage image : images) {
                byte[] bytes = Base64.getDecoder().decode(image.dataBase64());
                BufferedImage value = ImageIO.read(new ByteArrayInputStream(bytes));
                if (value == null) return null;
                decoded.add(value);
            }

            int width = 1024;
            int tileHeight = 286;
            BufferedImage sheet = new BufferedImage(
                    width, tileHeight * decoded.size(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = sheet.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
                graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
                for (int index = 0; index < decoded.size(); index++) {
                    int top = index * tileHeight;
                    graphics.setColor(index % 2 == 0
                            ? new Color(245, 247, 250) : Color.WHITE);
                    graphics.fillRect(0, top, width, tileHeight);
                    graphics.setColor(new Color(31, 41, 55));
                    Dto.QuestionImage image = images.get(index);
                    graphics.drawString("#" + (index + 1) + "  [" + image.role() + "]  "
                            + abbreviate(image.fileName(), 50), 24, top + 30);

                    BufferedImage source = decoded.get(index);
                    double scale = Math.min(960.0 / Math.max(source.getWidth(), 1),
                            224.0 / Math.max(source.getHeight(), 1));
                    int drawWidth = Math.max(1, (int) Math.round(source.getWidth() * scale));
                    int drawHeight = Math.max(1, (int) Math.round(source.getHeight() * scale));
                    int x = (width - drawWidth) / 2;
                    int y = top + 45 + (224 - drawHeight) / 2;
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            scale >= 1.0
                                    ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                                    : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    graphics.drawImage(source, x, y, drawWidth, drawHeight, null);
                    graphics.setColor(new Color(209, 213, 219));
                    graphics.drawLine(0, top + tileHeight - 1, width, top + tileHeight - 1);
                }
            } finally {
                graphics.dispose();
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(sheet, "png", output)) return null;
            return new ContactSheet(
                    List.copyOf(images), Base64.getEncoder().encodeToString(output.toByteArray()));
        } catch (Exception error) {
            return null;
        }
    }

    private String formatAnalyses(List<Dto.ImageAnalysis> analyses) {
        StringBuilder result = new StringBuilder();
        for (Dto.ImageAnalysis analysis : analyses) {
            result.append("- ").append(analysis.role()).append(" / ")
                    .append(analysis.fileName()).append("：")
                    .append(abbreviate(analysis.description(), 1200)).append('\n');
        }
        return result.toString();
    }

    private Dto.ImageAnalysis failed(Dto.QuestionImage image, String reason) {
        return new Dto.ImageAnalysis(
                image.id(), image.fileName(), image.role(), reason, false);
    }

    private String normalizeMediaType(String mediaType, String fileName) {
        if (mediaType != null && mediaType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            String normalized = mediaType.toLowerCase(Locale.ROOT);
            return "image/jpg".equals(normalized) ? "image/jpeg" : normalized;
        }
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp")) return "image/bmp";
        return "image/png";
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return error.getClass().getSimpleName();
        message = message.replaceAll("sk-[A-Za-z0-9._-]{8,}", "sk-[已隐藏]")
                .replaceAll("(?i)bearer\\s+[A-Za-z0-9._-]+", "Bearer [已隐藏]")
                .replaceAll("(?i)(api[_ -]?key|authorization)[^,;\\n]*", "$1=[已隐藏]");
        return abbreviate(message, 240);
    }

    private String stripJsonFence(String raw) {
        String value = raw == null ? "{}" : raw.trim();
        if (value.startsWith("```")) {
            int firstLine = value.indexOf('\n');
            value = firstLine >= 0 ? value.substring(firstLine + 1) : value.substring(3);
        }
        if (value.endsWith("```")) value = value.substring(0, value.length() - 3);
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        return start >= 0 && end >= start ? value.substring(start, end + 1) : value;
    }

    public record ImageAnalysisBundle(
            List<Dto.ImageAnalysis> analyses,
            String comparison
    ) {}

    private record ContactSheet(List<Dto.QuestionImage> images, String dataBase64) {}
}
