package com.aes.controller;

import com.aes.model.Dto;
import com.aes.service.DatabaseHomeworkService;
import com.aes.service.DatabaseKnowledgeService;
import com.aes.service.DocumentParserService;
import com.aes.service.HomeworkService;
import com.aes.service.KnowledgeService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AesController {

    private final KnowledgeService knowledgeService;
    private final DocumentParserService documentParserService;
    private final HomeworkService homeworkService;
    private final DatabaseKnowledgeService databaseKnowledgeService;
    private final DatabaseHomeworkService databaseHomeworkService;

    public AesController(KnowledgeService knowledgeService,
                         DocumentParserService documentParserService,
                         HomeworkService homeworkService,
                         DatabaseKnowledgeService databaseKnowledgeService,
                         DatabaseHomeworkService databaseHomeworkService) {
        this.knowledgeService = knowledgeService;
        this.documentParserService = documentParserService;
        this.homeworkService = homeworkService;
        this.databaseKnowledgeService = databaseKnowledgeService;
        this.databaseHomeworkService = databaseHomeworkService;
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

    @GetMapping("/database/knowledge/stats")
    public Dto.KnowledgeStats getDatabaseKnowledgeStats() {
        return new Dto.KnowledgeStats(
                databaseKnowledgeService.getChunkCount(),
                databaseKnowledgeService.getFileCount(),
                databaseKnowledgeService.getFileList(),
                databaseKnowledgeService.getMetadataList()
        );
    }

    @PostMapping("/database/knowledge/sync")
    public Map<String, Object> syncDatabaseKnowledge() {
        databaseKnowledgeService.syncKnowledgeBase();
        return Map.of(
                "success", true,
                "chunkCount", databaseKnowledgeService.getChunkCount(),
                "fileCount", databaseKnowledgeService.getFileCount()
        );
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

    // ================================================================
    // 数据库作业文档批改
    // ================================================================

    @PostMapping("/database/homework/grade")
    public Dto.DatabaseHomeworkResult gradeDatabaseHomework(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "general") String category) {
        return databaseHomeworkService.gradeHomework(file, category);
    }

    @PostMapping("/database/homework/grade/stream")
    public void gradeDatabaseHomeworkStream(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "general") String category,
            HttpServletResponse response) throws Exception {

        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");

        PrintWriter writer = response.getWriter();
        databaseHomeworkService.gradeHomeworkStream(file, category, writer);
        writer.close();
    }
}
