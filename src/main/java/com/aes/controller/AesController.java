package com.aes.controller;

import com.aes.model.Dto;
import com.aes.service.DocumentParserService;
import com.aes.service.HomeworkService;
import com.aes.service.KnowledgeService;
import com.aes.service.ScoringService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AesController {

    private final KnowledgeService knowledgeService;
    private final ScoringService scoringService;
    private final DocumentParserService documentParserService;
    private final HomeworkService homeworkService;

    public AesController(KnowledgeService knowledgeService,
                         ScoringService scoringService,
                         DocumentParserService documentParserService,
                         HomeworkService homeworkService) {
        this.knowledgeService = knowledgeService;
        this.scoringService = scoringService;
        this.documentParserService = documentParserService;
        this.homeworkService = homeworkService;
    }

    // ================================================================
    // 知识库管理
    // ================================================================
    @GetMapping("/knowledge/stats")
    public Dto.KnowledgeStats getKnowledgeStats() {
        return new Dto.KnowledgeStats(
                knowledgeService.getChunkCount(),
                knowledgeService.getFileCount(),
                knowledgeService.getFileList(),
                knowledgeService.getMetadataList()
        );
    }

    @PostMapping("/knowledge/sync")
    public Map<String, Object> syncKnowledge() {
        knowledgeService.syncKnowledgeBase();
        return Map.of(
                "success", true,
                "chunkCount", knowledgeService.getChunkCount(),
                "fileCount", knowledgeService.getFileCount()
        );
    }

    // ================================================================
    // 单代码评分
    // ================================================================
    @PostMapping("/score")
    public Map<String, Object> score(@RequestBody Dto.ScoreRequest request) {
        var ctx = scoringService.scoreSync(
                request.getContent(),
                request.getCategory()
        );
        return Map.of(
                "context", ctx.context(),
                "sources", ctx.sources()
        );
    }

    @GetMapping("/score/stream")
    public void scoreStream(
            @RequestParam String content,
            @RequestParam(defaultValue = "general") String category,
            HttpServletResponse response) throws Exception {

        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");

        PrintWriter writer = response.getWriter();
        scoringService.scoreStream(content, category, writer);
        writer.close();
    }

    @PostMapping("/score/stream")
    public void scoreStreamPost(
            @RequestBody Dto.ScoreRequest request,
            HttpServletResponse response) throws Exception {

        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");

        PrintWriter writer = response.getWriter();
        scoringService.scoreStream(
                request.getContent(),
                request.getCategory(),
                writer
        );
        writer.close();
    }

    // ================================================================
    // 作业文档批改
    // ================================================================

    /**
     * 同步批改整份作业文档（Multipart 上传 docx）
     */
    @PostMapping("/homework/grade")
    public Dto.HomeworkResult gradeHomework(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "general") String category) {
        return homeworkService.gradeHomework(file, category);
    }

    /**
     * 流式批改（SSE，逐题推送）
     */
    @PostMapping("/homework/grade/stream")
    public void gradeHomeworkStream(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "general") String category,
            HttpServletResponse response) throws Exception {

        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");

        PrintWriter writer = response.getWriter();
        homeworkService.gradeHomeworkStream(file, category, writer);
        writer.close();
    }
}
