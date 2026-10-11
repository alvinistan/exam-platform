package com.leenex.exam.catalog.question;

import java.util.ArrayList;
import java.util.List;

/**
 * What a question needs before it can be reviewed or published (FR-QB-04). Drafts may be incomplete; these problems are
 * shown on the draft and block the move to Reviewed or Published.
 */
public final class QuestionChecks {

    private QuestionChecks() {
    }

    public static List<String> problems(QuestionContent c, boolean topicActive) {
        List<String> problems = new ArrayList<>();
        if (c.options().size() < 4 || c.options().size() > 5) {
            problems.add("Add 4 or 5 options (there are " + c.options().size() + ").");
        }
        long empty = c.options().stream().filter(o -> o.bodyHtml().isEmpty() && o.mediaKey() == null).count();
        if (empty > 0) {
            problems.add(empty == 1 ? "One option has no text or image." : empty + " options have no text or image.");
        }
        long correct = c.correctCount();
        if ("SINGLE".equals(c.answerType()) && correct != 1) {
            problems.add("A single-answer question needs exactly one correct option (" + correct + " marked).");
        }
        if ("MULTI".equals(c.answerType()) && correct < 2) {
            problems.add("A multi-answer question needs at least two correct options (" + correct + " marked).");
        }
        if ("MULTI".equals(c.answerType()) && correct > 0 && correct == c.options().size()) {
            problems.add("At least one option must be wrong.");
        }
        if (!topicActive) {
            problems.add("The question's topic is archived. Move it to an active topic.");
        }
        return List.copyOf(problems);
    }
}
