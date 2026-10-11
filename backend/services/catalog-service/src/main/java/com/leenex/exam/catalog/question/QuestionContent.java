package com.leenex.exam.catalog.question;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Everything a question version holds, already sanitised. Options are in label order A, B, C … */
public record QuestionContent(UUID topicId, String answerType, String stemHtml, String stemMediaKey,
                              String stemMediaAlt, List<Option> options, String explanationHtml,
                              List<Definition> definitions, List<String> keyPoints, String difficulty,
                              int estSeconds, BigDecimal marks, List<String> tags) {

    public record Option(String label, String bodyHtml, String mediaKey, String mediaAlt, boolean correct,
                         UUID misconceptionId) {
    }

    public record Definition(String term, String meaning) {
    }

    public long correctCount() {
        return options.stream().filter(Option::correct).count();
    }
}
