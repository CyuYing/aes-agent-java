package com.aes.service;

import com.aes.model.Dto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 使用 MySQL 数据库持久化整份批改与逐题明细，并提供面向教师的组合查询。
 * 旧版本 data/grading_records/*.json 会在启动时幂等迁移，原文件保留作备份。
 */
@Service
public class GradingRecordService {

    private static final Logger log = LoggerFactory.getLogger(GradingRecordService.class);
    private static final List<String> REVIEW_STATUSES = List.of(
            "PENDING_REVIEW", "APPROVED", "NEEDS_REVISION");
    private static final DateTimeFormatter ID_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT)
                    .withZone(ZoneId.of("UTC"));

    private final ObjectMapper objectMapper;

    @Value("${aes.grading-records.path:${AES_GRADING_RECORDS_PATH:data/grading_records}}")
    private String recordsPath;

    @Value("${aes.grading-database.url:${AES_GRADING_DATABASE_URL:jdbc:mysql://127.0.0.1:3307/aes_agent?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true}}")
    private String configuredJdbcUrl;

    @Value("${aes.grading-database.username:${AES_GRADING_DATABASE_USERNAME:aes_agent}}")
    private String configuredUsername;

    @Value("${aes.grading-database.password:${AES_GRADING_DATABASE_PASSWORD:}}")
    private String configuredPassword;

    private volatile String jdbcUrl;
    private volatile String jdbcUsername;
    private volatile String jdbcPassword;

    public GradingRecordService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public synchronized void init() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            String url = value(configuredJdbcUrl);
            if (!url.toLowerCase(Locale.ROOT).startsWith("jdbc:mysql://")) {
                throw new IllegalStateException("批改记录数据库必须使用 jdbc:mysql:// URL");
            }
            String username = value(configuredUsername);
            if (username.isBlank()) {
                throw new IllegalStateException("MySQL 批改记录账号不能为空");
            }
            String password = configuredPassword == null ? "" : configuredPassword;
            if (password.isBlank()) {
                throw new IllegalStateException(
                        "MySQL 批改记录密码未配置，请设置 AES_GRADING_DATABASE_PASSWORD");
            }
            ensureLegacyDirectory();
            jdbcUrl = url;
            jdbcUsername = username;
            jdbcPassword = password;
            createSchema();
            migrateLegacyJsonRecords();
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("缺少 MySQL Connector/J 数据库驱动", e);
        }
    }

    public synchronized RecordSummary saveJava(Dto.HomeworkResult result, String createdBy) {
        return saveJava(result, createdBy, emptyIdentity());
    }

    public synchronized RecordSummary saveJava(
            Dto.HomeworkResult result, String createdBy, Dto.StudentIdentity student) {
        return save("java", result.fileName(), result.totalScore(),
                result.maxTotalScore(), result, createdBy, student);
    }

    public synchronized RecordSummary saveDatabase(
            Dto.DatabaseHomeworkResult result, String createdBy) {
        return saveDatabase(result, createdBy, emptyIdentity());
    }

    public synchronized RecordSummary saveDatabase(
            Dto.DatabaseHomeworkResult result, String createdBy, Dto.StudentIdentity student) {
        return save("database", result.fileName(), result.totalScore(),
                result.maxTotalScore(), result, createdBy, student);
    }

    /** 答案库批量批改统一产出 HomeworkResult，但仍按真实课程类型归档。 */
    public synchronized RecordSummary saveHomework(
            String domain, Dto.HomeworkResult result, String createdBy) {
        return saveHomework(domain, result, createdBy, emptyIdentity());
    }

    public synchronized RecordSummary saveHomework(
            String domain, Dto.HomeworkResult result, String createdBy,
            Dto.StudentIdentity student) {
        String safeDomain = "database".equalsIgnoreCase(domain) ? "database" : "java";
        return save(safeDomain, result.fileName(), result.totalScore(),
                result.maxTotalScore(), result, createdBy, student);
    }

    public synchronized List<RecordSummary> list(int limit) {
        return search(new SearchCriteria(
                "", "", "", "", "", "", null, null,
                null, null, null, "", limit, 0, "createdAt", "desc"))
                .items();
    }

    public synchronized SearchPage<RecordSummary> search(SearchCriteria requested) {
        SearchCriteria criteria = normalizeCriteria(requested);
        SqlFilter filter = recordFilter(criteria);
        String order = recordOrder(criteria);
        long total = count("SELECT COUNT(*) FROM grading_record r" + filter.where(),
                filter.parameters());
        String sql = """
                SELECT r.* FROM grading_record r
                """ + filter.where() + " ORDER BY " + order + " LIMIT ? OFFSET ?";
        List<RecordSummary> items = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int position = bind(statement, filter.parameters(), 1);
            statement.setInt(position++, criteria.limit());
            statement.setInt(position, criteria.offset());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) items.add(readSummary(rows));
            }
        } catch (SQLException e) {
            throw databaseError("查询批改记录失败", e);
        }
        return new SearchPage<>(List.copyOf(items), total,
                criteria.limit(), criteria.offset());
    }

    public synchronized SearchPage<QuestionHit> searchQuestions(SearchCriteria requested) {
        SearchCriteria criteria = normalizeCriteria(requested);
        SqlFilter filter = questionFilter(criteria);
        String order = questionOrder(criteria);
        long total = count("SELECT COUNT(*) FROM grading_question q "
                        + "JOIN grading_record r ON r.id=q.record_id" + filter.where(),
                filter.parameters());
        String sql = """
                SELECT r.id AS record_id, r.created_at, r.domain, r.file_name,
                       r.total_score, r.max_total_score, r.review_status,
                       r.student_name, r.student_id, r.class_name,
                       q.question_index, q.title, q.score, q.max_score,
                       q.question_type, q.grading_method, q.student_answer, q.report
                  FROM grading_question q
                  JOIN grading_record r ON r.id=q.record_id
                """ + filter.where() + " ORDER BY " + order + " LIMIT ? OFFSET ?";
        List<QuestionHit> items = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int position = bind(statement, filter.parameters(), 1);
            statement.setInt(position++, criteria.limit());
            statement.setInt(position, criteria.offset());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) items.add(readQuestionHit(rows));
            }
        } catch (SQLException e) {
            throw databaseError("查询逐题批改记录失败", e);
        }
        return new SearchPage<>(List.copyOf(items), total,
                criteria.limit(), criteria.offset());
    }

    public synchronized StoredRecord get(String id) {
        validateId(id);
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM grading_record WHERE id=?")) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("批改记录不存在: " + id);
                return readStoredRecord(rows);
            }
        } catch (SQLException e) {
            throw databaseError("读取批改记录失败", e);
        }
    }

    public synchronized QuestionDetail getQuestion(String recordId, int questionIndex) {
        validateId(recordId);
        if (questionIndex <= 0) throw new IllegalArgumentException("题号必须大于 0");
        String sql = """
                SELECT r.id AS record_id, r.created_at, r.domain, r.file_name,
                       r.total_score, r.max_total_score, r.review_status,
                       r.student_name, r.student_id, r.class_name,
                       q.question_index, q.title, q.score, q.max_score,
                       q.question_type, q.grading_method, q.student_answer,
                       q.correct_answer, q.report, q.question_json
                  FROM grading_question q
                  JOIN grading_record r ON r.id=q.record_id
                 WHERE r.id=? AND q.question_index=?
                """;
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, recordId);
            statement.setInt(2, questionIndex);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalArgumentException(
                            "未找到记录 " + recordId + " 的第 " + questionIndex + " 题");
                }
                return readQuestionDetail(rows);
            }
        } catch (SQLException e) {
            throw databaseError("读取逐题批改详情失败", e);
        }
    }

    public synchronized RecordSummary review(
            String id, String status, String note, String reviewer) {
        String normalizedStatus = normalizeReviewStatus(status, true);
        if ("NEEDS_REVISION".equals(normalizedStatus)
                && (note == null || note.isBlank())) {
            throw new IllegalArgumentException("标记为需调整时必须填写审核意见");
        }
        get(id);
        String sql = """
                UPDATE grading_record
                   SET review_status=?, review_note=?, reviewed_by=?, reviewed_at=?
                 WHERE id=?
                """;
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalizedStatus);
            statement.setString(2, abbreviate(note, 1000));
            statement.setString(3, normalizeActor(reviewer));
            statement.setString(4, Instant.now().toString());
            statement.setString(5, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw databaseError("保存教师复核结果失败", e);
        }
        return get(id).summary();
    }

    public synchronized byte[] exportCsv() {
        StringBuilder csv = new StringBuilder("\uFEFF记录ID,批改时间,类型,学生姓名,学号,班级,文件名,得分,满分,题数,审核状态,提交人,审核人,审核意见\r\n");
        SearchPage<RecordSummary> page = search(new SearchCriteria(
                "", "", "", "", "", "", null, null,
                null, null, null, "", 100, 0, "createdAt", "desc"));
        int offset = 0;
        while (true) {
            for (RecordSummary record : page.items()) {
                csv.append(csv(record.id())).append(',')
                        .append(csv(record.createdAt().toString())).append(',')
                        .append(csv(record.domain())).append(',')
                        .append(csv(record.studentName())).append(',')
                        .append(csv(record.studentId())).append(',')
                        .append(csv(record.className())).append(',')
                        .append(csv(record.fileName())).append(',')
                        .append(record.totalScore()).append(',')
                        .append(record.maxTotalScore()).append(',')
                        .append(record.questionCount()).append(',')
                        .append(csv(record.reviewStatus())).append(',')
                        .append(csv(record.createdBy())).append(',')
                        .append(csv(record.reviewedBy())).append(',')
                        .append(csv(record.reviewNote())).append("\r\n");
            }
            offset += page.items().size();
            if (offset >= page.total() || page.items().isEmpty()) break;
            page = search(new SearchCriteria(
                    "", "", "", "", "", "", null, null,
                    null, null, null, "", 100, offset, "createdAt", "desc"));
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    public synchronized StorageStats storageStats() {
        long records = count("SELECT COUNT(*) FROM grading_record", List.of());
        long questions = count("SELECT COUNT(*) FROM grading_question", List.of());
        return new StorageStats("MySQL", mysqlDatabaseName(), records, questions, 0);
    }

    private RecordSummary save(String domain,
                               String fileName,
                               int score,
                               int maxScore,
                               Object result,
                               String createdBy,
                               Dto.StudentIdentity student) {
        Instant now = Instant.now();
        String id = ID_TIME.format(now) + "-" + UUID.randomUUID().toString().substring(0, 8);
        Dto.StudentIdentity identity = normalizeIdentity(student);
        StoredRecord record = new StoredRecord(
                id, now, domain, safeFileName(fileName), score, maxScore,
                "PENDING_REVIEW", "", "", null, normalizeActor(createdBy),
                identity.name(), identity.studentId(), identity.className(),
                identity.assignmentNo(), questionCount(result), result);
        insertRecord(record, false);
        return record.summary();
    }

    private void insertRecord(StoredRecord record, boolean ignoreExisting) {
        if (ignoreExisting && recordExists(record.id())) return;
        String recordJson;
        JsonNode resultNode;
        try {
            recordJson = objectMapper.writeValueAsString(record.result());
            resultNode = objectMapper.readTree(recordJson);
        } catch (Exception e) {
            throw new IllegalStateException("序列化批改结果失败: " + e.getMessage(), e);
        }
        List<QuestionRow> questions = questionRows(resultNode);
        String recordSql = """
                INSERT INTO grading_record(
                    id, created_at, domain, file_name, total_score, max_total_score,
                    review_status, review_note, reviewed_by, reviewed_at, created_by,
                    student_name, student_id, class_name, assignment_no,
                    question_count, result_json)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        String questionSql = """
                INSERT INTO grading_question(
                    record_id, question_index, title, score, max_score,
                    question_type, grading_method, student_answer, correct_answer,
                    report, question_json)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """;
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(recordSql)) {
                statement.setString(1, record.id());
                statement.setString(2, record.createdAt().toString());
                statement.setString(3, safeDomain(record.domain()));
                statement.setString(4, safeFileName(record.fileName()));
                statement.setInt(5, record.totalScore());
                statement.setInt(6, record.maxTotalScore());
                statement.setString(7, normalizeReviewStatus(record.reviewStatus(), false));
                statement.setString(8, value(record.reviewNote()));
                statement.setString(9, value(record.reviewedBy()));
                statement.setString(10, record.reviewedAt() == null
                        ? null : record.reviewedAt().toString());
                statement.setString(11, normalizeActor(record.createdBy()));
                statement.setString(12, value(record.studentName()));
                statement.setString(13, value(record.studentId()));
                statement.setString(14, value(record.className()));
                statement.setString(15, value(record.assignmentNo()));
                statement.setInt(16, questions.size());
                statement.setString(17, recordJson);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(questionSql)) {
                for (QuestionRow question : questions) {
                    statement.setString(1, record.id());
                    statement.setInt(2, question.index());
                    statement.setString(3, question.title());
                    statement.setInt(4, question.score());
                    statement.setInt(5, question.maxScore());
                    statement.setString(6, question.questionType());
                    statement.setString(7, question.gradingMethod());
                    statement.setString(8, question.studentAnswer());
                    statement.setString(9, question.correctAnswer());
                    statement.setString(10, question.report());
                    statement.setString(11, question.json());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            connection.commit();
        } catch (SQLException e) {
            if (ignoreExisting && isDuplicate(e)) return;
            throw databaseError("保存批改记录失败", e);
        }
    }

    private void createSchema() {
        List<String> statements = List.of(
                """
                CREATE TABLE IF NOT EXISTS grading_record(
                    id VARCHAR(80) NOT NULL,
                    created_at VARCHAR(40) NOT NULL,
                    domain VARCHAR(20) NOT NULL,
                    file_name VARCHAR(500) NOT NULL,
                    total_score INT NOT NULL,
                    max_total_score INT NOT NULL,
                    review_status VARCHAR(30) NOT NULL,
                    review_note VARCHAR(1200) NOT NULL DEFAULT '',
                    reviewed_by VARCHAR(160) NOT NULL DEFAULT '',
                    reviewed_at VARCHAR(40),
                    created_by VARCHAR(160) NOT NULL,
                    student_name VARCHAR(200) NOT NULL DEFAULT '',
                    student_id VARCHAR(200) NOT NULL DEFAULT '',
                    class_name VARCHAR(200) NOT NULL DEFAULT '',
                    assignment_no VARCHAR(200) NOT NULL DEFAULT '',
                    question_count INT NOT NULL DEFAULT 0,
                    result_json LONGTEXT NOT NULL,
                    PRIMARY KEY(id),
                    KEY idx_grading_record_created(created_at),
                    KEY idx_grading_record_student_id(student_id),
                    KEY idx_grading_record_student_name(student_name),
                    KEY idx_grading_record_class(class_name),
                    KEY idx_grading_record_domain(domain),
                    KEY idx_grading_record_status(review_status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """,
                """
                CREATE TABLE IF NOT EXISTS grading_question(
                    record_id VARCHAR(80) NOT NULL,
                    question_index INT NOT NULL,
                    title TEXT NOT NULL,
                    score INT NOT NULL,
                    max_score INT NOT NULL,
                    question_type VARCHAR(50) NOT NULL DEFAULT '',
                    grading_method VARCHAR(100) NOT NULL DEFAULT '',
                    student_answer LONGTEXT,
                    correct_answer LONGTEXT,
                    report LONGTEXT,
                    question_json LONGTEXT NOT NULL,
                    PRIMARY KEY(record_id, question_index),
                    KEY idx_grading_question_index(question_index),
                    CONSTRAINT fk_grading_question_record
                        FOREIGN KEY(record_id) REFERENCES grading_record(id) ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """
        );
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            for (String sql : statements) statement.execute(sql);
        } catch (SQLException e) {
            throw databaseError("初始化批改数据库失败", e);
        }
    }

    private void migrateLegacyJsonRecords() {
        Path directory = ensureLegacyDirectory();
        int migrated = 0;
        try (Stream<Path> files = Files.list(directory)) {
            for (Path path : files.filter(file -> file.getFileName().toString().endsWith(".json"))
                    .sorted().toList()) {
                try {
                    StoredRecord legacy = objectMapper.readValue(path.toFile(), StoredRecord.class);
                    if (legacy.id() == null || legacy.result() == null || recordExists(legacy.id())) {
                        continue;
                    }
                    StoredRecord normalized = new StoredRecord(
                            legacy.id(), legacy.createdAt(), safeDomain(legacy.domain()),
                            safeFileName(legacy.fileName()), legacy.totalScore(), legacy.maxTotalScore(),
                            normalizeReviewStatus(legacy.reviewStatus(), false), value(legacy.reviewNote()),
                            value(legacy.reviewedBy()), legacy.reviewedAt(), normalizeActor(legacy.createdBy()),
                            value(legacy.studentName()), value(legacy.studentId()), value(legacy.className()),
                            value(legacy.assignmentNo()), questionCount(legacy.result()), legacy.result());
                    insertRecord(normalized, true);
                    migrated++;
                } catch (Exception error) {
                    log.warn("忽略无法迁移的旧批改记录 {}: {}",
                            path.getFileName(), error.getMessage());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("扫描旧批改记录失败: " + e.getMessage(), e);
        }
        if (migrated > 0) {
            log.info("已将 {} 条旧 JSON 批改记录迁移到 MySQL 数据库，原文件保留作备份", migrated);
        }
    }

    private SqlFilter recordFilter(SearchCriteria criteria) {
        List<String> conditions = new ArrayList<>();
        List<Object> parameters = new ArrayList<>();
        addRecordConditions(conditions, parameters, criteria);
        if (criteria.questionIndex() != null) {
            conditions.add("EXISTS (SELECT 1 FROM grading_question q WHERE q.record_id=r.id AND q.question_index=?)");
            parameters.add(criteria.questionIndex());
        }
        if (!criteria.questionKeyword().isBlank()) {
            conditions.add("EXISTS (SELECT 1 FROM grading_question q WHERE q.record_id=r.id AND ("
                    + questionTextCondition("q") + "))");
            addLikeParameters(parameters, criteria.questionKeyword(), 4);
        }
        if (!criteria.keyword().isBlank()) {
            conditions.add("(LOWER(r.id) LIKE ? OR LOWER(r.file_name) LIKE ? "
                    + "OR LOWER(r.student_name) LIKE ? OR LOWER(r.student_id) LIKE ? "
                    + "OR LOWER(r.class_name) LIKE ? OR LOWER(r.assignment_no) LIKE ? "
                    + "OR EXISTS (SELECT 1 FROM grading_question q WHERE q.record_id=r.id AND ("
                    + questionTextCondition("q") + ")))");
            addLikeParameters(parameters, criteria.keyword(), 10);
        }
        return new SqlFilter(where(conditions), List.copyOf(parameters));
    }

    private SqlFilter questionFilter(SearchCriteria criteria) {
        List<String> conditions = new ArrayList<>();
        List<Object> parameters = new ArrayList<>();
        addRecordConditions(conditions, parameters, criteria);
        if (criteria.questionIndex() != null) {
            conditions.add("q.question_index=?");
            parameters.add(criteria.questionIndex());
        }
        if (!criteria.questionKeyword().isBlank()) {
            conditions.add("(" + questionTextCondition("q") + ")");
            addLikeParameters(parameters, criteria.questionKeyword(), 4);
        }
        if (!criteria.keyword().isBlank()) {
            conditions.add("(LOWER(r.id) LIKE ? OR LOWER(r.file_name) LIKE ? "
                    + "OR LOWER(r.student_name) LIKE ? OR LOWER(r.student_id) LIKE ? "
                    + "OR LOWER(r.class_name) LIKE ? OR LOWER(r.assignment_no) LIKE ? OR "
                    + questionTextCondition("q") + ")");
            addLikeParameters(parameters, criteria.keyword(), 10);
        }
        return new SqlFilter(where(conditions), List.copyOf(parameters));
    }

    private void addRecordConditions(List<String> conditions,
                                     List<Object> parameters,
                                     SearchCriteria criteria) {
        addLike(conditions, parameters, "r.student_name", criteria.studentName());
        addLike(conditions, parameters, "r.student_id", criteria.studentId());
        addLike(conditions, parameters, "r.class_name", criteria.className());
        if (!criteria.domain().isBlank()) {
            conditions.add("r.domain=?");
            parameters.add(criteria.domain());
        }
        if (!criteria.reviewStatus().isBlank()) {
            conditions.add("r.review_status=?");
            parameters.add(criteria.reviewStatus());
        }
        if (criteria.minScore() != null) {
            conditions.add("r.total_score>=?");
            parameters.add(criteria.minScore());
        }
        if (criteria.maxScore() != null) {
            conditions.add("r.total_score<=?");
            parameters.add(criteria.maxScore());
        }
        if (criteria.from() != null) {
            conditions.add("r.created_at>=?");
            parameters.add(criteria.from().toString());
        }
        if (criteria.to() != null) {
            conditions.add("r.created_at<?");
            parameters.add(criteria.to().toString());
        }
    }

    private String questionTextCondition(String alias) {
        return "LOWER(" + alias + ".title) LIKE ? OR LOWER(COALESCE(" + alias
                + ".student_answer,'')) LIKE ? OR LOWER(COALESCE(" + alias
                + ".correct_answer,'')) LIKE ? OR LOWER(COALESCE(" + alias + ".report,'')) LIKE ?";
    }

    private void addLike(List<String> conditions, List<Object> parameters,
                         String column, String value) {
        if (value == null || value.isBlank()) return;
        conditions.add("LOWER(" + column + ") LIKE ?");
        parameters.add(like(value));
    }

    private void addLikeParameters(List<Object> parameters, String value, int count) {
        String pattern = like(value);
        for (int i = 0; i < count; i++) parameters.add(pattern);
    }

    private String where(List<String> conditions) {
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    private String recordOrder(SearchCriteria criteria) {
        String column = switch (criteria.sortBy()) {
            case "score" -> "r.total_score";
            case "student" -> "r.student_name";
            case "fileName" -> "r.file_name";
            default -> "r.created_at";
        };
        return column + ("asc".equals(criteria.sortDirection()) ? " ASC" : " DESC")
                + ", r.id DESC";
    }

    private String questionOrder(SearchCriteria criteria) {
        String column = switch (criteria.sortBy()) {
            case "score" -> "q.score";
            case "questionIndex" -> "q.question_index";
            case "student" -> "r.student_name";
            case "fileName" -> "r.file_name";
            default -> "r.created_at";
        };
        return column + ("asc".equals(criteria.sortDirection()) ? " ASC" : " DESC")
                + ", r.id DESC, q.question_index ASC";
    }

    private SearchCriteria normalizeCriteria(SearchCriteria value) {
        SearchCriteria source = value == null ? new SearchCriteria(
                "", "", "", "", "", "", null, null,
                null, null, null, "", 30, 0, "createdAt", "desc") : value;
        String domain = source.domain() == null ? "" : source.domain().trim().toLowerCase(Locale.ROOT);
        if (!domain.isBlank() && !List.of("java", "database").contains(domain)) {
            throw new IllegalArgumentException("课程类型仅支持 java 或 database");
        }
        String status = normalizeReviewFilter(source.reviewStatus());
        int limit = Math.max(1, Math.min(source.limit(), 100));
        int offset = Math.max(0, source.offset());
        Integer minScore = scoreFilter(source.minScore(), "最低分");
        Integer maxScore = scoreFilter(source.maxScore(), "最高分");
        if (minScore != null && maxScore != null && minScore > maxScore) {
            throw new IllegalArgumentException("最低分不能高于最高分");
        }
        Integer questionIndex = source.questionIndex();
        if (questionIndex != null && questionIndex <= 0) {
            throw new IllegalArgumentException("题号必须大于 0");
        }
        if (source.from() != null && source.to() != null
                && !source.from().isBefore(source.to())) {
            throw new IllegalArgumentException("起始时间必须早于截止时间");
        }
        return new SearchCriteria(
                value(source.keyword()), domain, value(source.studentName()),
                value(source.studentId()), value(source.className()), status,
                minScore, maxScore, source.from(), source.to(), questionIndex,
                value(source.questionKeyword()), limit, offset,
                value(source.sortBy()), "asc".equalsIgnoreCase(source.sortDirection()) ? "asc" : "desc");
    }

    private Integer scoreFilter(Integer score, String label) {
        if (score == null) return null;
        if (score < 0 || score > 10000) {
            throw new IllegalArgumentException(label + "超出允许范围");
        }
        return score;
    }

    private RecordSummary readSummary(ResultSet row) throws SQLException {
        return new RecordSummary(
                row.getString("id"), Instant.parse(row.getString("created_at")),
                row.getString("domain"), row.getString("file_name"),
                row.getInt("total_score"), row.getInt("max_total_score"),
                row.getString("review_status"), row.getString("review_note"),
                row.getString("reviewed_by"), instant(row.getString("reviewed_at")),
                row.getString("created_by"), row.getString("student_name"),
                row.getString("student_id"), row.getString("class_name"),
                row.getString("assignment_no"), row.getInt("question_count"));
    }

    private StoredRecord readStoredRecord(ResultSet row) throws SQLException {
        RecordSummary summary = readSummary(row);
        Object result;
        try {
            result = objectMapper.readValue(row.getString("result_json"), Object.class);
        } catch (IOException e) {
            throw new IllegalStateException("数据库中的批改结果 JSON 损坏", e);
        }
        return new StoredRecord(
                summary.id(), summary.createdAt(), summary.domain(), summary.fileName(),
                summary.totalScore(), summary.maxTotalScore(), summary.reviewStatus(),
                summary.reviewNote(), summary.reviewedBy(), summary.reviewedAt(),
                summary.createdBy(), summary.studentName(), summary.studentId(),
                summary.className(), summary.assignmentNo(), summary.questionCount(), result);
    }

    private QuestionHit readQuestionHit(ResultSet row) throws SQLException {
        return new QuestionHit(
                row.getString("record_id"), Instant.parse(row.getString("created_at")),
                row.getString("domain"), row.getString("file_name"),
                row.getInt("total_score"), row.getInt("max_total_score"),
                row.getString("review_status"), row.getString("student_name"),
                row.getString("student_id"), row.getString("class_name"),
                row.getInt("question_index"), row.getString("title"),
                row.getInt("score"), row.getInt("max_score"),
                row.getString("question_type"), row.getString("grading_method"),
                abbreviate(row.getString("student_answer"), 260),
                abbreviate(row.getString("report"), 320));
    }

    private QuestionDetail readQuestionDetail(ResultSet row) throws SQLException {
        Object question;
        try {
            question = objectMapper.readValue(row.getString("question_json"), Object.class);
        } catch (IOException e) {
            throw new IllegalStateException("数据库中的逐题结果 JSON 损坏", e);
        }
        return new QuestionDetail(
                row.getString("record_id"), Instant.parse(row.getString("created_at")),
                row.getString("domain"), row.getString("file_name"),
                row.getInt("total_score"), row.getInt("max_total_score"),
                row.getString("review_status"), row.getString("student_name"),
                row.getString("student_id"), row.getString("class_name"),
                row.getInt("question_index"), row.getString("title"),
                row.getInt("score"), row.getInt("max_score"),
                row.getString("question_type"), row.getString("grading_method"),
                value(row.getString("student_answer")), value(row.getString("correct_answer")),
                value(row.getString("report")), question);
    }

    private List<QuestionRow> questionRows(JsonNode result) {
        List<QuestionRow> questions = new ArrayList<>();
        if (result == null || !result.path("questions").isArray()) return questions;
        for (JsonNode question : result.path("questions")) {
            int index = question.path("index").asInt(questions.size() + 1);
            if (index <= 0) index = questions.size() + 1;
            try {
                questions.add(new QuestionRow(
                        index, question.path("title").asText(""),
                        question.path("score").asInt(0), question.path("maxScore").asInt(0),
                        question.path("questionType").asText(""),
                        question.path("gradingMethod").asText(""),
                        question.path("studentAnswer").asText(""),
                        question.path("correctAnswer").asText(""),
                        question.path("report").asText(""), objectMapper.writeValueAsString(question)));
            } catch (Exception e) {
                throw new IllegalStateException("序列化第 " + index + " 题失败", e);
            }
        }
        return List.copyOf(questions);
    }

    private int questionCount(Object result) {
        if (result == null) return 0;
        JsonNode node = objectMapper.valueToTree(result);
        return node.path("questions").isArray() ? node.path("questions").size() : 0;
    }

    private long count(String sql, List<Object> parameters) {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters, 1);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw databaseError("统计批改记录失败", e);
        }
    }

    private int bind(PreparedStatement statement, List<Object> parameters, int start)
            throws SQLException {
        int position = start;
        for (Object parameter : parameters) {
            if (parameter instanceof Integer number) statement.setInt(position++, number);
            else statement.setString(position++, parameter == null ? null : parameter.toString());
        }
        return position;
    }

    private boolean recordExists(String id) {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM grading_record WHERE id=?")) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException e) {
            throw databaseError("检查批改记录失败", e);
        }
    }

    private Connection openConnection() throws SQLException {
        if (jdbcUrl == null) throw new IllegalStateException("批改数据库尚未初始化");
        return DriverManager.getConnection(jdbcUrl, jdbcUsername, jdbcPassword);
    }

    private Path ensureLegacyDirectory() {
        try {
            String configured = recordsPath == null || recordsPath.isBlank()
                    ? "data/grading_records" : recordsPath;
            Path directory = Path.of(configured);
            if (!directory.isAbsolute()) directory = Path.of("").toAbsolutePath().resolve(directory);
            directory = directory.normalize().toAbsolutePath();
            Files.createDirectories(directory);
            return directory;
        } catch (IOException e) {
            throw new IllegalStateException("创建旧记录迁移目录失败: " + e.getMessage(), e);
        }
    }

    private Dto.StudentIdentity normalizeIdentity(Dto.StudentIdentity student) {
        if (student == null) return emptyIdentity();
        return new Dto.StudentIdentity(
                abbreviate(student.name(), 180), abbreviate(student.studentId(), 180),
                abbreviate(student.className(), 180), abbreviate(student.assignmentNo(), 180));
    }

    private Dto.StudentIdentity emptyIdentity() {
        return new Dto.StudentIdentity("", "", "", "");
    }

    private String safeDomain(String value) {
        return "database".equalsIgnoreCase(value) ? "database" : "java";
    }

    private String normalizeReviewStatus(String status, boolean strict) {
        String value = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (value.isBlank() && !strict) return "PENDING_REVIEW";
        if (!REVIEW_STATUSES.contains(value)) {
            if (!strict) return "PENDING_REVIEW";
            throw new IllegalArgumentException(
                    "审核状态仅支持 PENDING_REVIEW、APPROVED、NEEDS_REVISION");
        }
        return value;
    }

    private String normalizeReviewFilter(String status) {
        String value = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (value.isBlank()) return "";
        if (!REVIEW_STATUSES.contains(value)) {
            throw new IllegalArgumentException(
                    "审核状态仅支持 PENDING_REVIEW、APPROVED、NEEDS_REVISION");
        }
        return value;
    }

    private void validateId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9-]{8,80}")) {
            throw new IllegalArgumentException("批改记录 ID 格式不正确");
        }
    }

    private String safeFileName(String value) {
        if (value == null || value.isBlank()) return "unknown.docx";
        return Path.of(value).getFileName().toString();
    }

    private String normalizeActor(String value) {
        return value == null || value.isBlank() ? "local-teacher" : abbreviate(value, 120);
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() <= maxLength
                ? trimmed : trimmed.substring(0, maxLength) + "...";
    }

    private String value(String value) {
        return value == null ? "" : value.trim();
    }

    private String like(String value) {
        return "%" + value(value).toLowerCase(Locale.ROOT) + "%";
    }

    private Instant instant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private boolean isDuplicate(SQLException error) {
        return "23000".equals(error.getSQLState()) && error.getErrorCode() == 1062;
    }

    private String mysqlDatabaseName() {
        String url = jdbcUrl == null ? "" : jdbcUrl;
        int query = url.indexOf('?');
        if (query >= 0) url = url.substring(0, query);
        int slash = url.lastIndexOf('/');
        String name = slash >= 0 ? url.substring(slash + 1) : "aes_agent";
        return name.isBlank() ? "aes_agent" : name;
    }

    private IllegalStateException databaseError(String message, SQLException error) {
        return new IllegalStateException(message + ": " + error.getMessage(), error);
    }

    private String csv(String value) {
        String safe = value == null ? "" : value.replace("\"", "\"\"");
        if (!safe.isEmpty() && "=+-@\t\r".indexOf(safe.charAt(0)) >= 0) safe = "'" + safe;
        return "\"" + safe + "\"";
    }

    public record ReviewRequest(String status, String note) {}

    public record SearchCriteria(
            String keyword,
            String domain,
            String studentName,
            String studentId,
            String className,
            String reviewStatus,
            Integer minScore,
            Integer maxScore,
            Instant from,
            Instant to,
            Integer questionIndex,
            String questionKeyword,
            int limit,
            int offset,
            String sortBy,
            String sortDirection
    ) {}

    public record SearchPage<T>(List<T> items, long total, int limit, int offset) {}

    public record RecordSummary(
            String id,
            Instant createdAt,
            String domain,
            String fileName,
            int totalScore,
            int maxTotalScore,
            String reviewStatus,
            String reviewNote,
            String reviewedBy,
            Instant reviewedAt,
            String createdBy,
            String studentName,
            String studentId,
            String className,
            String assignmentNo,
            int questionCount
    ) {}

    public record StoredRecord(
            String id,
            Instant createdAt,
            String domain,
            String fileName,
            int totalScore,
            int maxTotalScore,
            String reviewStatus,
            String reviewNote,
            String reviewedBy,
            Instant reviewedAt,
            String createdBy,
            String studentName,
            String studentId,
            String className,
            String assignmentNo,
            int questionCount,
            Object result
    ) {
        public RecordSummary summary() {
            return new RecordSummary(
                    id, createdAt, domain, fileName, totalScore, maxTotalScore,
                    reviewStatus, reviewNote, reviewedBy, reviewedAt, createdBy,
                    studentName, studentId, className, assignmentNo, questionCount);
        }
    }

    public record QuestionHit(
            String recordId,
            Instant createdAt,
            String domain,
            String fileName,
            int totalScore,
            int maxTotalScore,
            String reviewStatus,
            String studentName,
            String studentId,
            String className,
            int questionIndex,
            String title,
            int score,
            int maxScore,
            String questionType,
            String gradingMethod,
            String studentAnswerSnippet,
            String reportSnippet
    ) {}

    public record QuestionDetail(
            String recordId,
            Instant createdAt,
            String domain,
            String fileName,
            int totalScore,
            int maxTotalScore,
            String reviewStatus,
            String studentName,
            String studentId,
            String className,
            int questionIndex,
            String title,
            int score,
            int maxScore,
            String questionType,
            String gradingMethod,
            String studentAnswer,
            String correctAnswer,
            String report,
            Object question
    ) {}

    public record StorageStats(
            String engine,
            String fileName,
            long recordCount,
            long questionCount,
            long fileSizeBytes
    ) {}

    private record QuestionRow(
            int index,
            String title,
            int score,
            int maxScore,
            String questionType,
            String gradingMethod,
            String studentAnswer,
            String correctAnswer,
            String report,
            String json
    ) {}

    private record SqlFilter(String where, List<Object> parameters) {}
}
