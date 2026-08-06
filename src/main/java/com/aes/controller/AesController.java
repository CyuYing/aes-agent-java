package com.aes.controller;

import com.aes.model.Dto;
import com.aes.service.AnswerKeyService;
import com.aes.service.AssignmentDocumentService;
import com.aes.service.BatchGradingService;
import com.aes.service.DatabaseHomeworkService;
import com.aes.service.DatabaseKnowledgeService;
import com.aes.service.DocumentParserService;
import com.aes.service.GradingRecordService;
import com.aes.service.HomeworkService;
import com.aes.service.KnowledgeService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.security.Principal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AesController {

    private final KnowledgeService knowledgeService;
    private final DocumentParserService documentParserService;
    private final HomeworkService homeworkService;
    private final DatabaseKnowledgeService databaseKnowledgeService;
    private final DatabaseHomeworkService databaseHomeworkService;
    private final GradingRecordService gradingRecordService;
    private final AnswerKeyService answerKeyService;
    private final BatchGradingService batchGradingService;
    private final AssignmentDocumentService assignmentDocumentService;
    private final ObjectMapper objectMapper;

    public AesController(KnowledgeService knowledgeService,
                         DocumentParserService documentParserService,
                         HomeworkService homeworkService,
                         DatabaseKnowledgeService databaseKnowledgeService,
                         DatabaseHomeworkService databaseHomeworkService,
                         GradingRecordService gradingRecordService,
                         AnswerKeyService answerKeyService,
                         BatchGradingService batchGradingService,
                         AssignmentDocumentService assignmentDocumentService,
                         ObjectMapper objectMapper) {
        this.knowledgeService = knowledgeService;
        this.documentParserService = documentParserService;
        this.homeworkService = homeworkService;
        this.databaseKnowledgeService = databaseKnowledgeService;
        this.databaseHomeworkService = databaseHomeworkService;
        this.gradingRecordService = gradingRecordService;
        this.answerKeyService = answerKeyService;
        this.batchGradingService = batchGradingService;
        this.assignmentDocumentService = assignmentDocumentService;
        this.objectMapper = objectMapper;
    }

    // ================================================================
    // 知识库管理
    // ================================================================
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "aes-agent");
    }

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
    // 结构化答案库与一键批量批改
    // ================================================================

    @GetMapping("/answer-keys")
    public List<Dto.AnswerKeySummary> listAnswerKeys(
            @RequestParam(defaultValue = "") String courseType) {
        return answerKeyService.list(courseType);
    }

    @PostMapping("/answer-keys")
    public Dto.AnswerKeySummary importAnswerKey(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "auto") String courseType,
            @RequestParam(defaultValue = "") String name) {
        validateDocx(file);
        return answerKeyService.summary(
                answerKeyService.importAnswerKey(file, courseType, name));
    }

    @PatchMapping("/answer-keys/{id}/scores")
    public Dto.AnswerKeySummary updateAnswerKeyScores(
            @PathVariable String id,
            @RequestBody Dto.AnswerKeyScoreUpdate request) {
        return answerKeyService.summary(answerKeyService.updateScores(id, request));
    }

    @DeleteMapping("/answer-keys/{id}")
    public Map<String, Object> deleteAnswerKey(@PathVariable String id) {
        answerKeyService.delete(id);
        return Map.of("success", true, "id", id);
    }

    @PostMapping("/batch/preview")
    public Dto.BatchPreview previewBatch(
            @RequestParam("answerKeyId") String answerKeyId,
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(defaultValue = "false") boolean aiQuestionRecognition) {
        validateDocxFiles(files);
        return batchGradingService.preview(
                answerKeyId, files, aiQuestionRecognition);
    }

    @PostMapping(value = "/batch/grade/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void gradeBatchStream(
            @RequestParam("answerKeyId") String answerKeyId,
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(defaultValue = "false") boolean aiQuestionRecognition,
            Principal principal,
            HttpServletResponse response) throws Exception {
        validateDocxFiles(files);
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");
        PrintWriter writer = response.getWriter();
        try {
            batchGradingService.grade(
                    answerKeyId, files, actor(principal), aiQuestionRecognition,
                    (event, payload) -> writeEvent(writer, event, payload));
            writeDone(writer);
        } catch (Exception error) {
            writeEvent(writer, "error", Map.of("message", safeMessage(error)));
        } finally {
            writer.close();
        }
    }

    // ================================================================
    // 作业文档批改
    // ================================================================

    /**
     * 先解析并逐题预览，教师可在正式批改前确认题型、标准答案和提示词。
     */
    @PostMapping("/homework/parse")
    public Dto.HomeworkPreview parseHomework(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "false") boolean aiQuestionRecognition) {
        validateDocx(file);
        Dto.HomeworkPreview preview = homeworkService.previewHomework(
                file, aiQuestionRecognition);
        String courseType = assignmentDocumentService.detectCourseType(
                file, preview.questions());
        return new Dto.HomeworkPreview(
                preview.fileName(), courseType, preview.questions(), preview.recognition());
    }

    /**
     * 同步批改整份作业文档（Multipart 上传 docx）
     */
    @PostMapping("/homework/grade")
    public Dto.HomeworkResult gradeHomework(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "general") String category,
            @RequestParam(defaultValue = "[]") String configs,
            @RequestParam(value = "referenceImages", required = false)
            List<MultipartFile> referenceImages,
            @RequestParam(value = "referenceImageQuestionIndexes", required = false)
            List<Integer> referenceImageQuestionIndexes,
            @RequestParam(defaultValue = "false") boolean aiQuestionRecognition,
            Principal principal) {
        validateDocx(file);
        Dto.HomeworkResult result = homeworkService.gradeHomework(
                file, category, parseQuestionConfigs(configs),
                emptyIfNull(referenceImages), emptyIfNull(referenceImageQuestionIndexes),
                aiQuestionRecognition);
        gradingRecordService.saveJava(result, actor(principal), safeIdentity(file));
        return result;
    }

    /**
     * 流式批改（SSE，逐题推送）
     */
    @PostMapping("/homework/grade/stream")
    public void gradeHomeworkStream(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "general") String category,
            @RequestParam(defaultValue = "[]") String configs,
            @RequestParam(value = "referenceImages", required = false)
            List<MultipartFile> referenceImages,
            @RequestParam(value = "referenceImageQuestionIndexes", required = false)
            List<Integer> referenceImageQuestionIndexes,
            @RequestParam(defaultValue = "false") boolean aiQuestionRecognition,
            Principal principal,
            HttpServletResponse response) throws Exception {
        validateDocx(file);
        List<Dto.QuestionConfig> parsedConfigs = parseQuestionConfigs(configs);
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");

        PrintWriter writer = response.getWriter();
        Dto.HomeworkResult result = homeworkService.gradeHomeworkStream(
                file, category, parsedConfigs,
                emptyIfNull(referenceImages), emptyIfNull(referenceImageQuestionIndexes),
                aiQuestionRecognition, writer);
        if (result != null) {
            try {
                gradingRecordService.saveJava(result, actor(principal), safeIdentity(file));
                writeDone(writer);
            } catch (RuntimeException error) {
                writeRecordSaveError(writer);
            }
        }
        writer.close();
    }

    // ================================================================
    // 数据库作业文档批改
    // ================================================================

    @PostMapping("/database/homework/grade")
    public Dto.DatabaseHomeworkResult gradeDatabaseHomework(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "general") String category,
            @RequestParam(defaultValue = "false") boolean aiQuestionRecognition,
            Principal principal) {
        validateDocx(file);
        Dto.DatabaseHomeworkResult result = databaseHomeworkService.gradeHomework(
                file, category, aiQuestionRecognition);
        gradingRecordService.saveDatabase(result, actor(principal), safeIdentity(file));
        return result;
    }

    @PostMapping("/database/homework/grade/stream")
    public void gradeDatabaseHomeworkStream(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "general") String category,
            @RequestParam(defaultValue = "false") boolean aiQuestionRecognition,
            Principal principal,
            HttpServletResponse response) throws Exception {

        validateDocx(file);
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");

        PrintWriter writer = response.getWriter();
        Dto.DatabaseHomeworkResult result =
                databaseHomeworkService.gradeHomeworkStream(
                        file, category, aiQuestionRecognition, writer);
        if (result != null) {
            try {
                gradingRecordService.saveDatabase(result, actor(principal), safeIdentity(file));
                writeDone(writer);
            } catch (RuntimeException error) {
                writeRecordSaveError(writer);
            }
        }
        writer.close();
    }

    // ================================================================
    // 批改记录与教师复核
    // ================================================================

    @GetMapping("/grading/records")
    public List<GradingRecordService.RecordSummary> listGradingRecords(
            @RequestParam(defaultValue = "50") int limit) {
        return gradingRecordService.list(limit);
    }

    @GetMapping("/grading/records/search")
    public GradingRecordService.SearchPage<GradingRecordService.RecordSummary>
    searchGradingRecords(@RequestParam Map<String, String> parameters) {
        return gradingRecordService.search(searchCriteria(parameters));
    }

    @GetMapping("/grading/questions/search")
    public GradingRecordService.SearchPage<GradingRecordService.QuestionHit>
    searchGradingQuestions(@RequestParam Map<String, String> parameters) {
        return gradingRecordService.searchQuestions(searchCriteria(parameters));
    }

    @GetMapping("/grading/storage")
    public GradingRecordService.StorageStats gradingStorageStats() {
        return gradingRecordService.storageStats();
    }

    @GetMapping("/grading/records/{id}/questions/{questionIndex}")
    public GradingRecordService.QuestionDetail getGradingQuestion(
            @PathVariable String id, @PathVariable int questionIndex) {
        return gradingRecordService.getQuestion(id, questionIndex);
    }

    @GetMapping("/grading/records/{id}")
    public GradingRecordService.StoredRecord getGradingRecord(@PathVariable String id) {
        return gradingRecordService.get(id);
    }

    @PatchMapping("/grading/records/{id}/review")
    public GradingRecordService.RecordSummary reviewGradingRecord(
            @PathVariable String id,
            @RequestBody GradingRecordService.ReviewRequest request,
            Principal principal) {
        return gradingRecordService.review(
                id, request.status(), request.note(), actor(principal));
    }

    @GetMapping("/grading/records/export.csv")
    public ResponseEntity<byte[]> exportGradingRecords() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=grading-records.csv")
                .body(gradingRecordService.exportCsv());
    }

    private GradingRecordService.SearchCriteria searchCriteria(
            Map<String, String> parameters) {
        return new GradingRecordService.SearchCriteria(
                parameter(parameters, "keyword"),
                parameter(parameters, "domain"),
                parameter(parameters, "studentName"),
                parameter(parameters, "studentId"),
                parameter(parameters, "className"),
                parameter(parameters, "reviewStatus"),
                integerParameter(parameters, "minScore", null),
                integerParameter(parameters, "maxScore", null),
                dateParameter(parameters, "from", false),
                dateParameter(parameters, "to", true),
                integerParameter(parameters, "questionIndex", null),
                parameter(parameters, "questionKeyword"),
                integerParameter(parameters, "limit", 30),
                integerParameter(parameters, "offset", 0),
                parameter(parameters, "sortBy"),
                parameter(parameters, "sortDirection"));
    }

    private String parameter(Map<String, String> parameters, String name) {
        String value = parameters.get(name);
        return value == null ? "" : value.trim();
    }

    private Integer integerParameter(
            Map<String, String> parameters, String name, Integer fallback) {
        String value = parameter(parameters, name);
        if (value.isBlank()) return fallback;
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(name + " 必须是整数");
        }
    }

    private Instant dateParameter(
            Map<String, String> parameters, String name, boolean upperExclusive) {
        String value = parameter(parameters, name);
        if (value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                LocalDate date = LocalDate.parse(value);
                if (upperExclusive) date = date.plusDays(1);
                return date.atStartOfDay(ZoneId.systemDefault()).toInstant();
            } catch (DateTimeParseException error) {
                throw new IllegalArgumentException(name + " 必须是 YYYY-MM-DD 或 ISO-8601 时间");
            }
        }
    }

    private List<Dto.QuestionConfig> parseQuestionConfigs(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(
                    json, new TypeReference<List<Dto.QuestionConfig>>() {});
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "逐题批改配置格式不正确", e);
        }
    }

    private void validateDocx(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请上传作业文档");
        }
        String fileName = file.getOriginalFilename();
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".docx")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持 .docx 格式");
        }
    }

    private void validateDocxFiles(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请上传学生作业文档");
        }
        for (MultipartFile file : files) validateDocx(file);
    }

    private <T> List<T> emptyIfNull(List<T> values) {
        return values == null ? List.of() : values;
    }

    private String actor(Principal principal) {
        return principal == null ? "local-teacher" : principal.getName();
    }

    private Dto.StudentIdentity safeIdentity(MultipartFile file) {
        try {
            return assignmentDocumentService.parseIdentity(file);
        } catch (RuntimeException ignored) {
            String fileName = file == null ? "未命名作业" : file.getOriginalFilename();
            if (fileName == null || fileName.isBlank()) fileName = "未命名作业";
            fileName = java.nio.file.Path.of(fileName).getFileName().toString()
                    .replaceFirst("(?i)\\.docx$", "");
            return new Dto.StudentIdentity(fileName, "", "", "");
        }
    }

    private void writeDone(PrintWriter writer) {
        writer.write("event: done\ndata: [DONE]\n\n");
        writer.flush();
    }

    private void writeEvent(PrintWriter writer, String event, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            writer.write("event: " + event + "\n");
            for (String line : json.split("\n", -1)) writer.write("data: " + line + "\n");
            writer.write("\n");
            writer.flush();
        } catch (Exception error) {
            writer.write("event: error\ndata: {\"message\":\"批量结果序列化失败\"}\n\n");
            writer.flush();
        }
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
        message = message.replaceAll("sk-[A-Za-z0-9._-]{8,}", "sk-[已隐藏]")
                .replaceAll("(?i)bearer\\s+[A-Za-z0-9._-]+", "Bearer [已隐藏]");
        return message.length() <= 300 ? message : message.substring(0, 300) + "...";
    }

    private void writeRecordSaveError(PrintWriter writer) {
        writer.write("event: error\ndata: {\"message\":\"批改已完成，但记录写入数据库失败，请检查 data 目录写权限\"}\n\n");
        writer.flush();
    }
}
