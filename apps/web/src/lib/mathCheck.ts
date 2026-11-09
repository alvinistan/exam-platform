import katex from 'katex';
import 'katex/contrib/mhchem';
import { KATEX_OPTIONS, scanFormulas, textRuns } from './math';

/**
 * The first problem with the formulas in a piece of question HTML or plain text, or null. Used by the question editor
 * before saving, so a formula students would see as red source text can't be published.
 */
export function formulaProblem(htmlOrText: string, isHtml = true): string | null {
  const runs = isHtml ? textRuns(htmlOrText) : [htmlOrText];
  for (const run of runs) {
    const { formulas, problem } = scanFormulas(run);
    if (problem) return problem;
    for (const f of formulas) {
      const error = latexError(f.latex, f.display);
      if (error) return `Formula “${short(f.latex)}”: ${error}`;
    }
  }
  return null;
}

/** KaTeX's parse error for one formula, without its position noise, or null when it typesets. */
export function latexError(latex: string, display = false): string | null {
  let blocked: string | null = null;
  try {
    katex.renderToString(latex, {
      ...KATEX_OPTIONS,
      throwOnError: true,
      displayMode: display,
      // Students would see these as red text: links, images and HTML are never allowed in formulas.
      trust: (context) => {
        blocked ??= context.command;
        return false;
      },
    });
    return blocked ? `${blocked} isn’t allowed in formulas.` : null;
  } catch (e) {
    const message = e instanceof Error ? e.message : String(e);
    return (
      message
        .replace(/^KaTeX parse error: /, '')
        .replace(/ at (position \d+|end of input):.*$/s, '')
        .trim() || 'This isn’t valid LaTeX.'
    );
  }
}

export function renderFormula(latex: string, display: boolean): string {
  return katex.renderToString(latex, { ...KATEX_OPTIONS, displayMode: display });
}

const short = (latex: string) => (latex.length > 40 ? `${latex.slice(0, 40)}…` : latex);
