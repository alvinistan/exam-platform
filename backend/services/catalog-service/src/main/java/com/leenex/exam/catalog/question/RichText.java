package com.leenex.exam.catalog.question;

import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Allow-list HTML for question text (FR-QB-01): paragraphs, line breaks, bold, italic, underline, lists, sub- and
 * superscript and inline code. Everything else — scripts, styles, attributes, links, images — is removed, so text
 * stored here can be shown to students as HTML.
 */
@Component
public class RichText {

    private static final PolicyFactory POLICY = new HtmlPolicyBuilder()
            .allowElements("p", "br", "strong", "b", "em", "i", "u", "ul", "ol", "li", "sub", "sup", "code")
            .toFactory();

    public String sanitize(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String clean = POLICY.sanitize(html.strip());
        // An editor's empty paragraph is not content.
        return toText(clean).isEmpty() ? "" : clean;
    }

    /** Plain text for search and summaries: tags become spaces, entities are decoded, whitespace collapsed. */
    public String toText(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        String noTags = html.replaceAll("<[^>]*>", " ");
        return HtmlUtils.htmlUnescape(noTags).replace(' ', ' ').replaceAll("\\s+", " ").strip();
    }

    /** Plain text (from an import file) as safe HTML: escaped, one paragraph per blank-line-separated block. */
    public String fromPlainText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        StringBuilder html = new StringBuilder();
        for (String paragraph : text.strip().split("\\R\\s*\\R")) {
            html.append("<p>").append(HtmlUtils.htmlEscape(paragraph.strip()).replaceAll("\\R", "<br />")).append("</p>");
        }
        return html.toString();
    }
}
