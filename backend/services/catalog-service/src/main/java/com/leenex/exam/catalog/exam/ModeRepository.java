package com.leenex.exam.catalog.exam;

import com.leenex.exam.catalog.exam.ModeSettings.DifficultyMix;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Per-exam mode configuration ({@code catalog.exam_mode_config}). */
@Repository
public class ModeRepository {

    private final JdbcClient jdbc;

    public ModeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** All three modes in a fixed order; modes never saved come back as suggested defaults (disabled). */
    public List<ModeSettings> forExam(UUID examId) {
        Map<String, ModeSettings> saved = jdbc.sql("SELECT * FROM catalog.exam_mode_config WHERE exam_id = :id")
                .param("id", examId).query(ModeRepository::map).list().stream()
                .collect(Collectors.toMap(ModeSettings::mode, Function.identity()));
        List<ModeSettings> all = new ArrayList<>();
        for (String mode : ModeSettings.MODES) {
            all.add(saved.getOrDefault(mode, ModeSettings.suggested(mode)));
        }
        return all;
    }

    public List<ModeSettings> enabled(UUID examId) {
        return forExam(examId).stream().filter(ModeSettings::enabled).toList();
    }

    public void save(UUID examId, ModeSettings s) {
        jdbc.sql("""
                INSERT INTO catalog.exam_mode_config (exam_id, mode, enabled, default_count, min_count, max_count,
                    time_limit_seconds, per_question_seconds, mix_simple_pct, mix_medium_pct, mix_hard_pct,
                    shuffle_questions, shuffle_options, feedback_timing, result_visibility, negative_mark, pass_mark_pct,
                    allow_topic_choice, adaptive_enabled, max_retakes, free_attempts)
                VALUES (:exam, :mode, :enabled, :def, :min, :max, :time, :perQ, :simple, :medium, :hard, :shuffleQ,
                    :shuffleO, :feedback, :visibility, :negative, :pass, :topicChoice, :adaptive, :retakes, :free)
                ON CONFLICT (exam_id, mode) DO UPDATE SET
                    enabled = EXCLUDED.enabled, default_count = EXCLUDED.default_count, min_count = EXCLUDED.min_count,
                    max_count = EXCLUDED.max_count, time_limit_seconds = EXCLUDED.time_limit_seconds,
                    per_question_seconds = EXCLUDED.per_question_seconds, mix_simple_pct = EXCLUDED.mix_simple_pct,
                    mix_medium_pct = EXCLUDED.mix_medium_pct, mix_hard_pct = EXCLUDED.mix_hard_pct,
                    shuffle_questions = EXCLUDED.shuffle_questions, shuffle_options = EXCLUDED.shuffle_options,
                    feedback_timing = EXCLUDED.feedback_timing, result_visibility = EXCLUDED.result_visibility,
                    negative_mark = EXCLUDED.negative_mark, pass_mark_pct = EXCLUDED.pass_mark_pct,
                    allow_topic_choice = EXCLUDED.allow_topic_choice, adaptive_enabled = EXCLUDED.adaptive_enabled,
                    max_retakes = EXCLUDED.max_retakes, free_attempts = EXCLUDED.free_attempts,
                    version = catalog.exam_mode_config.version + 1
                """)
                .param("exam", examId).param("mode", s.mode()).param("enabled", s.enabled())
                .param("def", s.defaultCount()).param("min", s.minCount()).param("max", s.maxCount())
                .param("time", s.timeLimitSeconds()).param("perQ", s.perQuestionSeconds())
                .param("simple", s.difficultyMix().simple()).param("medium", s.difficultyMix().medium())
                .param("hard", s.difficultyMix().hard())
                .param("shuffleQ", s.shuffleQuestions()).param("shuffleO", s.shuffleOptions())
                .param("feedback", s.feedbackTiming()).param("visibility", s.resultVisibility())
                .param("negative", s.negativeMark()).param("pass", s.passMarkPct())
                .param("topicChoice", s.allowTopicChoice()).param("adaptive", s.adaptiveEnabled())
                .param("retakes", s.maxRetakes()).param("free", s.freeAttempts())
                .update();
    }

    private static ModeSettings map(ResultSet rs, int row) throws SQLException {
        return new ModeSettings(rs.getString("mode"), rs.getBoolean("enabled"), rs.getInt("default_count"),
                rs.getInt("min_count"), rs.getInt("max_count"), (Integer) rs.getObject("time_limit_seconds"),
                (Integer) rs.getObject("per_question_seconds"),
                new DifficultyMix(rs.getInt("mix_simple_pct"), rs.getInt("mix_medium_pct"), rs.getInt("mix_hard_pct")),
                rs.getBoolean("shuffle_questions"), rs.getBoolean("shuffle_options"), rs.getString("feedback_timing"),
                rs.getString("result_visibility"), rs.getBigDecimal("negative_mark"),
                rs.getObject("pass_mark_pct") == null ? null : rs.getInt("pass_mark_pct"),
                rs.getBoolean("allow_topic_choice"), rs.getBoolean("adaptive_enabled"),
                (Integer) rs.getObject("max_retakes"), rs.getInt("free_attempts"), rs.getInt("version"), true);
    }
}
