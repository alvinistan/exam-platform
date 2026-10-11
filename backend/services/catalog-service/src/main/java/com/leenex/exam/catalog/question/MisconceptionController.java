package com.leenex.exam.catalog.question;

import com.leenex.exam.catalog.support.AuditLog;
import com.leenex.exam.catalog.support.Requests;
import com.leenex.exam.platform.security.CurrentUser;
import com.leenex.exam.platform.web.ApiException;
import com.leenex.exam.platform.web.ProblemCode;
import com.leenex.exam.platform.web.UuidV7;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Misconception categories (FR-QB-11): named wrong ideas, optionally tied to a topic and linked from wrong options,
 * so results can say why an answer was wrong, not only that it was.
 */
@RestController
@RequestMapping("/api/admin/catalog/misconceptions")
@PreAuthorize("hasRole('ADMIN')")
public class MisconceptionController {

    private final JdbcClient jdbc;
    private final AuditLog audit;

    public MisconceptionController(JdbcClient jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record Misconception(UUID id, UUID topicId, String title, String description, OffsetDateTime createdAt) {
    }

    public record MisconceptionInput(UUID topicId, @NotBlank @Size(min = 2, max = 160) String title,
                                     @NotBlank @Size(max = 1000) String description) {
    }

    @GetMapping
    public List<Misconception> list(@RequestParam(required = false) UUID topicId) {
        String where = topicId == null ? "" : " WHERE topic_id = :topic OR topic_id IS NULL";
        var sql = jdbc.sql("SELECT * FROM catalog.misconception" + where + " ORDER BY title");
        if (topicId != null) {
            sql = sql.param("topic", topicId);
        }
        return sql.query((rs, n) -> new Misconception(rs.getObject("id", UUID.class), rs.getObject("topic_id", UUID.class),
                rs.getString("title"), rs.getString("description"), rs.getObject("created_at", OffsetDateTime.class))).list();
    }

    @PostMapping
    @Transactional
    public ResponseEntity<Misconception> create(@Valid @RequestBody MisconceptionInput in) {
        checkTopic(in.topicId());
        UUID id = UuidV7.generate();
        jdbc.sql("INSERT INTO catalog.misconception (id, topic_id, title, description) VALUES (:id, :topic, :title, :d)")
                .param("id", id).param("topic", in.topicId()).param("title", in.title().strip())
                .param("d", in.description().strip()).update();
        audit.record(CurrentUser.id(), "MISCONCEPTION_CREATED", "MISCONCEPTION", id, Map.of());
        return ResponseEntity.status(HttpStatus.CREATED).body(find(id));
    }

    @PutMapping("/{id}")
    @Transactional
    public Misconception update(@PathVariable UUID id, @Valid @RequestBody MisconceptionInput in) {
        checkTopic(in.topicId());
        int rows = jdbc.sql("UPDATE catalog.misconception SET topic_id = :topic, title = :title, description = :d WHERE id = :id")
                .param("id", id).param("topic", in.topicId()).param("title", in.title().strip())
                .param("d", in.description().strip()).update();
        if (rows == 0) {
            throw new ApiException(ProblemCode.NOT_FOUND, "Misconception not found.");
        }
        audit.record(CurrentUser.id(), "MISCONCEPTION_UPDATED", "MISCONCEPTION", id, Map.of());
        return find(id);
    }

    private void checkTopic(UUID topicId) {
        if (topicId != null && !jdbc.sql("SELECT EXISTS (SELECT 1 FROM catalog.topic WHERE id = :id)")
                .param("id", topicId).query(Boolean.class).single()) {
            throw Requests.invalid("topicId", "topic does not exist");
        }
    }

    private Misconception find(UUID id) {
        return jdbc.sql("SELECT * FROM catalog.misconception WHERE id = :id").param("id", id)
                .query((rs, n) -> new Misconception(rs.getObject("id", UUID.class), rs.getObject("topic_id", UUID.class),
                        rs.getString("title"), rs.getString("description"), rs.getObject("created_at", OffsetDateTime.class)))
                .single();
    }
}
