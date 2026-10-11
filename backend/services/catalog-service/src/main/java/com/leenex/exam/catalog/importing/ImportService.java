package com.leenex.exam.catalog.importing;

import com.leenex.exam.catalog.question.Bank;
import com.leenex.exam.catalog.question.QuestionContent.Definition;
import com.leenex.exam.catalog.question.QuestionService;
import com.leenex.exam.catalog.question.QuestionService.Input;
import com.leenex.exam.catalog.question.QuestionService.OptionInput;
import com.leenex.exam.catalog.question.RichText;
import com.leenex.exam.catalog.support.AuditLog;
import com.leenex.exam.platform.web.ApiException;
import com.leenex.exam.platform.web.ProblemCode;
import com.leenex.exam.platform.web.UuidV7;
import java.math.BigDecimal;
import java.sql.Array;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bulk import (FR-QB-08). Upload validates every row and keeps the valid ones aside; nothing reaches a bank until the
 * admin confirms, and then every valid row becomes a Draft question in one transaction.
 */
@Service
public class ImportService {

    static final Duration LIFETIME = Duration.ofHours(24);
    private static final Pattern TAG = Pattern.compile("^[a-z0-9][a-z0-9 -]{0,40}$");
    private static final String LABELS = "abcde";

    public record RowError(int row, String column, String message) {
    }

    public record ImportReport(UUID importId, UUID examId, Bank bank, String fileName, int totalRows, int validRows,
                               List<RowError> errors, String status, OffsetDateTime expiresAt,
                               List<UUID> createdQuestionIds) {
    }

    private static final TypeReference<List<Input>> INPUTS = new TypeReference<>() {
    };
    private static final TypeReference<List<RowError>> ERRORS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final RichText richText;
    private final QuestionService questions;
    private final AuditLog audit;
    private final Clock clock;

    public ImportService(JdbcClient jdbc, JsonMapper json, RichText richText, QuestionService questions, AuditLog audit,
                         Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.richText = richText;
        this.questions = questions;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public ImportReport upload(UUID actor, UUID examId, Bank bank, String fileName, byte[] bytes) {
        if (!jdbc.sql("SELECT EXISTS (SELECT 1 FROM catalog.exam WHERE id = :id)").param("id", examId)
                .query(Boolean.class).single()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Choose an existing exam.");
        }
        List<ImportFile.Row> rows;
        try {
            rows = ImportFile.read(fileName, bytes);
        } catch (ImportFile.FormatException e) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, e.getMessage());
        }
        Map<String, UUID> topics = topicsOf(examId);
        List<Input> valid = new ArrayList<>();
        List<RowError> errors = new ArrayList<>();
        for (ImportFile.Row row : rows) {
            List<RowError> rowErrors = new ArrayList<>();
            Input input = parse(row, topics, rowErrors);
            if (rowErrors.isEmpty()) {
                valid.add(input);
            } else {
                errors.addAll(rowErrors);
            }
        }
        UUID id = UuidV7.generate();
        OffsetDateTime expires = OffsetDateTime.ofInstant(clock.instant().plus(LIFETIME), ZoneOffset.UTC);
        String safeName = fileName == null ? "import" : fileName.replaceAll("[^\\w .()-]", "_");
        jdbc.sql("""
                INSERT INTO catalog.question_import (id, exam_id, bank, file_name, total_rows, valid_rows, errors,
                                                     created_by, expires_at)
                VALUES (:id, :exam, :bank, :name, :total, CAST(:valid AS jsonb), CAST(:errors AS jsonb), :actor, :expires)
                """)
                .param("id", id).param("exam", examId).param("bank", bank.name())
                .param("name", safeName.length() > 200 ? safeName.substring(0, 200) : safeName)
                .param("total", rows.size()).param("valid", json.writeValueAsString(valid))
                .param("errors", json.writeValueAsString(errors)).param("actor", actor).param("expires", expires)
                .update();
        audit.record(actor, "QUESTION_IMPORT_UPLOADED", "QUESTION_IMPORT", id,
                Map.of("rows", rows.size(), "valid", valid.size(), "bank", bank.name()));
        return get(id);
    }

    @Transactional
    public ImportReport confirm(UUID actor, UUID importId) {
        Map<String, Object> row = jdbc.sql("""
                SELECT status, bank, valid_rows::text AS valid, expires_at FROM catalog.question_import
                WHERE id = :id FOR UPDATE
                """)
                .param("id", importId)
                .query((rs, n) -> Map.<String, Object>of("status", rs.getString("status"), "bank", rs.getString("bank"),
                        "valid", rs.getString("valid"), "expires", rs.getObject("expires_at", OffsetDateTime.class)))
                .optional().orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "Import not found."));
        if ("CONFIRMED".equals(row.get("status"))) {
            throw new ApiException(ProblemCode.CONFLICT, "This import was already confirmed.");
        }
        if (((OffsetDateTime) row.get("expires")).toInstant().isBefore(clock.instant())) {
            throw new ApiException(ProblemCode.CONFLICT, "This import expired. Upload the file again.");
        }
        List<Input> inputs = json.readValue((String) row.get("valid"), INPUTS);
        if (inputs.isEmpty()) {
            throw new ApiException(ProblemCode.CONFLICT, "No valid rows to import. Fix the file and upload it again.");
        }
        List<UUID> created = questions.createAll(actor, Bank.valueOf((String) row.get("bank")), inputs);
        jdbc.sql("""
                UPDATE catalog.question_import SET status = 'CONFIRMED', confirmed_at = now(), created_question_ids = :ids
                WHERE id = :id
                """).param("id", importId).param("ids", created.toArray(UUID[]::new)).update();
        audit.record(actor, "QUESTION_IMPORT_CONFIRMED", "QUESTION_IMPORT", importId, Map.of("created", created.size()));
        return get(importId);
    }

    @Transactional(readOnly = true)
    public ImportReport get(UUID importId) {
        return jdbc.sql("""
                SELECT id, exam_id, bank, file_name, total_rows, jsonb_array_length(valid_rows) AS valid, errors::text AS errors,
                       status, expires_at, created_question_ids
                FROM catalog.question_import WHERE id = :id
                """)
                .param("id", importId)
                .query((rs, n) -> {
                    Array ids = rs.getArray("created_question_ids");
                    return new ImportReport(rs.getObject("id", UUID.class), rs.getObject("exam_id", UUID.class),
                            Bank.valueOf(rs.getString("bank")), rs.getString("file_name"), rs.getInt("total_rows"),
                            rs.getInt("valid"), json.readValue(rs.getString("errors"), ERRORS), rs.getString("status"),
                            rs.getObject("expires_at", OffsetDateTime.class),
                            ids == null ? List.of() : Arrays.asList((UUID[]) ids.getArray()));
                })
                .optional().orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "Import not found."));
    }

    // --- rows -------------------------------------------------------------------------------------------------------

    /** Active topics of the exam, keyed by "subject|module|topic" titles (case and spacing ignored). */
    private Map<String, UUID> topicsOf(UUID examId) {
        Map<String, UUID> topics = new HashMap<>();
        jdbc.sql("""
                SELECT s.title AS s, m.title AS m, t.title AS t, t.id FROM catalog.exam_subject es
                JOIN catalog.subject s ON s.id = es.subject_id AND s.status = 'ACTIVE'
                JOIN catalog.module m ON m.subject_id = s.id AND m.status = 'ACTIVE'
                JOIN catalog.topic t ON t.module_id = m.id AND t.status = 'ACTIVE'
                WHERE es.exam_id = :exam
                """)
                .param("exam", examId)
                .query((rs, n) -> topics.put(key(rs.getString("s"), rs.getString("m"), rs.getString("t")),
                        rs.getObject("id", UUID.class)))
                .list();
        return topics;
    }

    private static String key(String subject, String module, String topic) {
        return norm(subject) + "|" + norm(module) + "|" + norm(topic);
    }

    private static String norm(String s) {
        return s == null ? "" : s.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    Input parse(ImportFile.Row row, Map<String, UUID> topics, List<RowError> errors) {
        int n = row.number();
        UUID topicId = topics.get(key(row.get("subject"), row.get("module"), row.get("topic")));
        if (topicId == null) {
            errors.add(new RowError(n, "topic", "No active topic “" + row.get("topic") + "” in module “" + row.get("module")
                    + "” of subject “" + row.get("subject") + "” linked to this exam."));
        }
        String type = row.get("type").toUpperCase(Locale.ROOT);
        if (!Set.of("SINGLE", "MULTI").contains(type)) {
            errors.add(new RowError(n, "type", "Use SINGLE or MULTI."));
        }
        String question = row.get("question");
        if (question.isEmpty()) {
            errors.add(new RowError(n, "question", "Write the question."));
        } else if (question.length() > 5000) {
            errors.add(new RowError(n, "question", "Use 5,000 characters or fewer."));
        }
        List<String> options = new ArrayList<>();
        boolean gap = false;
        for (char label : LABELS.toCharArray()) {
            String text = row.get("option_" + label);
            if (text.isEmpty()) {
                gap = true;
            } else if (gap) {
                errors.add(new RowError(n, "option_" + label, "Fill the options in order, A to E, without gaps."));
            } else if (text.length() > 2000) {
                errors.add(new RowError(n, "option_" + label, "Use 2,000 characters or fewer."));
            } else {
                options.add(text);
            }
        }
        if (options.size() < 4) {
            errors.add(new RowError(n, "option_a", "Give 4 or 5 options (found " + options.size() + ")."));
        }
        List<Integer> correct = new ArrayList<>();
        for (String part : row.get("correct").toUpperCase(Locale.ROOT).split("[,;\\s]+")) {
            if (part.isEmpty()) {
                continue;
            }
            int index = part.length() == 1 ? "ABCDE".indexOf(part.charAt(0)) : -1;
            if (index < 0 || index >= options.size()) {
                errors.add(new RowError(n, "correct", "“" + part + "” isn't one of the options given."));
            } else if (!correct.contains(index)) {
                correct.add(index);
            }
        }
        if ("SINGLE".equals(type) && correct.size() != 1) {
            errors.add(new RowError(n, "correct", "A SINGLE question needs exactly one correct letter."));
        }
        if ("MULTI".equals(type) && (correct.size() < 2 || correct.size() == options.size())) {
            errors.add(new RowError(n, "correct", "A MULTI question needs two or more correct letters and at least one wrong option."));
        }
        String difficulty = row.get("difficulty").toUpperCase(Locale.ROOT);
        if (!Set.of("SIMPLE", "MEDIUM", "HARD").contains(difficulty)) {
            errors.add(new RowError(n, "difficulty", "Use SIMPLE, MEDIUM or HARD."));
        }
        int seconds = 0;
        try {
            seconds = Integer.parseInt(row.get("seconds"));
        } catch (NumberFormatException e) {
            // reported below
        }
        if (seconds < 10 || seconds > 600) {
            errors.add(new RowError(n, "seconds", "Give the solving time in whole seconds, 10 to 600."));
        }
        List<String> keyPoints = split(row.get("key_points"));
        if (keyPoints.size() > 10 || keyPoints.stream().anyMatch(k -> k.length() > 300)) {
            errors.add(new RowError(n, "key_points", "At most 10 key points of up to 300 characters, separated by |."));
        }
        List<Definition> definitions = new ArrayList<>();
        for (String d : split(row.get("definitions"))) {
            int colon = d.indexOf(':');
            if (colon <= 0) {
                errors.add(new RowError(n, "definitions", "Write each definition as “term: meaning”, separated by |."));
                break;
            }
            definitions.add(new Definition(d.substring(0, colon).strip(), d.substring(colon + 1).strip()));
        }
        List<String> tags = new ArrayList<>();
        for (String t : split(row.get("tags"))) {
            String tag = t.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            if (!TAG.matcher(tag).matches()) {
                errors.add(new RowError(n, "tags", "“" + t + "”: tags use letters, digits, spaces and hyphens."));
            } else if (!tags.contains(tag)) {
                tags.add(tag);
            }
        }
        BigDecimal marks = BigDecimal.ONE;
        if (!row.get("marks").isEmpty()) {
            try {
                marks = new BigDecimal(row.get("marks"));
                if (marks.signum() <= 0 || marks.compareTo(BigDecimal.valueOf(100)) > 0) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                errors.add(new RowError(n, "marks", "Give marks as a number above 0, up to 100, or leave it empty."));
            }
        }
        List<OptionInput> optionInputs = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            optionInputs.add(new OptionInput(richText.fromPlainText(options.get(i)), null, null, correct.contains(i), null));
        }
        return new Input(topicId, type, richText.fromPlainText(question), null, null, optionInputs,
                richText.fromPlainText(row.get("explanation")), definitions, keyPoints, difficulty, seconds, marks, tags);
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split("\\|")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
