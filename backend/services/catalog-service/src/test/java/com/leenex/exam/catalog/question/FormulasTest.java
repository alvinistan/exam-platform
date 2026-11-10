package com.leenex.exam.catalog.question;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FormulasTest {

    @Test
    void wellFormedFormulasAndOrdinaryTextPass() {
        assertThat(Formulas.problemInHtml("<p>Solve \\(x^2 = 4\\) and \\[\\frac{1}{2}\\]</p>")).isNull();
        assertThat(Formulas.problemInHtml("<p>It costs $5, or $10 for two.</p>")).isNull();
        assertThat(Formulas.problemInHtml("<p>\\(x &lt; 3\\) and \\(\\left( x \\right)\\)</p>")).isNull();
        assertThat(Formulas.problemInHtml("")).isNull();
        assertThat(Formulas.problem(null)).isNull();
    }

    @Test
    void problemsAreNamed() {
        assertThat(Formulas.problemInHtml("<p>\\(x^2</p>")).startsWith("a formula starting with \\( isn");
        assertThat(Formulas.problemInHtml("<p>x\\]</p>")).isEqualTo("a formula is closed with \\] but never opened");
        assertThat(Formulas.problemInHtml("<p>\\(  \\)</p>")).isEqualTo("a formula is empty");
        assertThat(Formulas.problem("\\(" + "x".repeat(Formulas.MAX_FORMULA + 1) + "\\)")).contains("longer than");
    }

    @Test
    void aFormulaMustNotSpanFormatting() {
        // Students' browsers typeset one run of text at a time, so bold inside a formula would break it.
        assertThat(Formulas.problemInHtml("<p>\\(<strong>x</strong>\\)</p>")).isNotNull();
    }
}
