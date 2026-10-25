package com.leenex.exam.catalog.question;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RichTextTest {

    private final RichText richText = new RichText();

    @Test
    void keepsTheAllowedFormattingAndDropsEverythingElse() {
        assertThat(richText.sanitize("<p style=\"color:red\">Hi <em>there</em><a href=\"javascript:x\">link</a></p>"
                + "<iframe src=x></iframe><ol><li><b>one</b></li></ol>"))
                .isEqualTo("<p>Hi <em>there</em>link</p><ol><li><b>one</b></li></ol>");
        assertThat(richText.sanitize("<p><br></p>")).isEmpty();
        assertThat(richText.sanitize(null)).isEmpty();
    }

    @Test
    void plainTextBecomesEscapedParagraphs() {
        assertThat(richText.fromPlainText("a < b\nc\n\nnext")).isEqualTo("<p>a &lt; b<br />c</p><p>next</p>");
        assertThat(richText.toText("<p>a &amp; b</p><p>c</p>")).isEqualTo("a & b c");
    }
}
