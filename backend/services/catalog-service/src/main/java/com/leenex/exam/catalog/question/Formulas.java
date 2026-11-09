package com.leenex.exam.catalog.question;

import java.util.ArrayList;
import java.util.List;
import org.springframework.web.util.HtmlUtils;

/**
 * Formulas in question text (Phase 12): LaTeX between \( and \) (in the line) or \[ and \] (on its own line), kept as
 * ordinary text. Students' browsers typeset them with KaTeX. The server can't typeset LaTeX, so it checks what it can:
 * every formula is closed within the same run of unformatted text, isn't empty, and isn't too long. The editor also
 * checks that KaTeX can read each formula before saving. Mirrors apps/web/src/lib/math.ts.
 */
final class Formulas {

    static final int MAX_FORMULA = 1000;

    private Formulas() {
    }

    /** The first problem with the formulas in sanitised HTML, or null. */
    static String problemInHtml(String html) {
        if (html == null || html.isEmpty()) {
            return null;
        }
        for (String run : textRuns(html)) {
            String problem = problem(run);
            if (problem != null) {
                return problem;
            }
        }
        return null;
    }

    /** The first problem with the formulas in one run of plain text, or null. */
    static String problem(String text) {
        if (text == null) {
            return null;
        }
        int i = 0;
        while (i < text.length()) {
            int open = next(text, i, "\\(", "\\[");
            int close = next(text, i, "\\)", "\\]");
            if (open < 0) {
                return close < 0 ? null : "a formula is closed with " + text.substring(close, close + 2) + " but never opened";
            }
            if (close >= 0 && close < open) {
                return "a formula is closed with " + text.substring(close, close + 2) + " but never opened";
            }
            boolean display = text.startsWith("\\[", open);
            String right = display ? "\\]" : "\\)";
            int end = text.indexOf(right, open + 2);
            if (end < 0) {
                return "a formula starting with " + text.substring(open, open + 2) + " isn’t closed with " + right;
            }
            String latex = text.substring(open + 2, end);
            if (latex.isBlank()) {
                return "a formula is empty";
            }
            if (latex.length() > MAX_FORMULA) {
                return "a formula is longer than " + MAX_FORMULA + " characters";
            }
            i = end + 2;
        }
        return null;
    }

    private static int next(String text, int from, String a, String b) {
        int x = text.indexOf(a, from);
        int y = text.indexOf(b, from);
        if (x < 0) {
            return y;
        }
        return y < 0 ? x : Math.min(x, y);
    }

    /** Runs of text between tags, decoded, as the browser's text nodes would be. */
    static List<String> textRuns(String html) {
        List<String> runs = new ArrayList<>();
        for (String part : html.split("<[^>]*>")) {
            if (!part.isEmpty()) {
                runs.add(HtmlUtils.htmlUnescape(part));
            }
        }
        return runs;
    }
}
