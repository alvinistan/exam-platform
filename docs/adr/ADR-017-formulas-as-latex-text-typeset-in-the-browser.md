# ADR-017: Formulas as LaTeX text, typeset in the browser with KaTeX

- **Status:** Accepted (Phase 12, 9 Nov 2026)
- **Deciders:** Product owner (client), development lead

## Context

Maths, Physics and Chemistry questions need real formulas, such as fractions, roots, powers, integrals, matrices and
chemical equations, in the question, the options, the explanation, key points and definitions. Question HTML is
sanitised with a strict allow-list (ADR-008, FR-QB-01), and questions also arrive through CSV/XLSX import as plain text.

## Decision

- A formula is **LaTeX written as ordinary text** between `\(` and `\)` (in the line) or `\[` and `\]` (on its own
  line). `$…$` is not a delimiter, because questions mention dollar prices.
- Nothing new is stored: the HTML allow-list, search text, versions and imports are unchanged.
- **KaTeX 0.16** typesets formulas in the student's browser (`RichContent`, used by `QuestionView` everywhere a
  question is shown). It is loaded only when a text contains a formula. KaTeX outputs HTML plus MathML for screen
  readers. `mhchem` adds `\ce{…}` for chemistry.
- **Safe settings:** `trust: false` (no `\href`, `\url`, images or HTML commands), bounded `maxSize` and
  `maxExpand`, and a bad formula shows as red source text instead of breaking the page.
- **Checks:**
  - The editor's Formula button writes a formula with a live preview.
  - Before saving, the editor checks that KaTeX can typeset every formula and that no untrusted command is used.
  - The catalog service checks what it can without a LaTeX engine: every formula closes within the same run of
    unformatted text, isn't empty, and is at most 1000 characters (400 on the field).

## Consequences

- Admins learn a little LaTeX. The Formula dialog's buttons cover common cases (fraction, root, power, subscript,
  symbols, sum, integral, limit, chemistry).
- Imported questions get only the server's delimiter check. A LaTeX typo in an import shows as red text until an admin
  opens and saves the question, which runs the full check. Imports arrive as drafts, so they are reviewed before
  publishing.
- Formatting (bold, italic) can't be applied inside a formula; LaTeX's own `\mathbf{}` is used instead.
- A later option is server-side rendering (for example KaTeX in Node), if search engines or emails need typeset maths.
