/**
 * Mathematical formulas in question text (Phase 12). A formula is LaTeX between \( and \) (in the line) or \[ and \]
 * (on its own line), stored as ordinary text inside the question's HTML. Nothing new is stored, so the server's HTML
 * allow-list, search and Excel imports need no change; the browser typesets formulas with KaTeX when it shows them.
 *
 * `$…$` is deliberately not a delimiter: questions mention prices in dollars.
 */
import type { KatexOptions } from 'katex';

export const MATH_DELIMITERS = [
  { left: '\\[', right: '\\]', display: true },
  { left: '\\(', right: '\\)', display: false },
];

/**
 * Safe settings: no \href, \url, \includegraphics or HTML extensions (trust: false), bounded sizes and macro
 * expansion, and a bad formula shows as red source text instead of breaking the page.
 */
export const KATEX_OPTIONS: KatexOptions = {
  throwOnError: false,
  trust: false,
  strict: 'ignore',
  maxSize: 20,
  maxExpand: 1000,
  output: 'htmlAndMathml',
};

/** The longest formula accepted, in characters of LaTeX. */
export const MAX_FORMULA = 1000;

/** Whether a text or HTML string contains a formula (cheap; decides whether KaTeX is loaded at all). */
export const hasMath = (text: string | null | undefined) => !!text && (text.includes('\\(') || text.includes('\\['));

export interface Formula {
  latex: string;
  display: boolean;
}

/**
 * The formulas in one piece of plain text, or the first problem with the delimiters. KaTeX's auto-render works on
 * one text node at a time, so a formula must open and close within the same run of unformatted text.
 */
export function scanFormulas(text: string): { formulas: Formula[]; problem: string | null } {
  const formulas: Formula[] = [];
  let i = 0;
  while (i < text.length) {
    const open = nextOpening(text, i);
    const close = nextClosing(text, i);
    if (open === null) {
      if (close !== null) return { formulas, problem: `A formula is closed with ${close.token} but never opened.` };
      break;
    }
    if (close !== null && close.at < open.at) {
      return { formulas, problem: `A formula is closed with ${close.token} but never opened.` };
    }
    const right = open.display ? '\\]' : '\\)';
    const end = text.indexOf(right, open.at + 2);
    if (end === -1) return { formulas, problem: `A formula starting with ${open.token} isn’t closed with ${right}.` };
    const latex = text.slice(open.at + 2, end);
    if (latex.trim() === '') return { formulas, problem: 'A formula is empty.' };
    if (latex.length > MAX_FORMULA) return { formulas, problem: `A formula is longer than ${MAX_FORMULA} characters.` };
    formulas.push({ latex, display: open.display });
    i = end + 2;
  }
  return { formulas, problem: null };
}

function nextOpening(text: string, from: number) {
  const inline = text.indexOf('\\(', from);
  const display = text.indexOf('\\[', from);
  if (inline === -1 && display === -1) return null;
  if (display === -1 || (inline !== -1 && inline < display)) return { at: inline, display: false, token: '\\(' };
  return { at: display, display: true, token: '\\[' };
}

function nextClosing(text: string, from: number) {
  const inline = text.indexOf('\\)', from);
  const display = text.indexOf('\\]', from);
  if (inline === -1 && display === -1) return null;
  if (display === -1 || (inline !== -1 && inline < display)) return { at: inline, token: '\\)' };
  return { at: display, token: '\\]' };
}

/** The runs of plain text in a piece of HTML, in the same way KaTeX's auto-render sees them. */
export function textRuns(html: string): string[] {
  const doc = new DOMParser().parseFromString(html, 'text/html');
  const runs: string[] = [];
  const walker = doc.createTreeWalker(doc.body, NodeFilter.SHOW_TEXT);
  for (let n = walker.nextNode(); n; n = walker.nextNode()) {
    if (n.textContent) runs.push(n.textContent);
  }
  return runs;
}

/** Escapes plain text (key points, definitions) so it can be shown with the same renderer as HTML. */
export const escapeHtml = (text: string) =>
  text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

/** A selected piece of text that is exactly one formula, so the dialog can edit it. */
export function parseFormula(text: string): Formula | null {
  const t = text.trim();
  const inline = /^\\\(([\s\S]+)\\\)$/.exec(t);
  if (inline?.[1]) return { latex: inline[1].trim(), display: false };
  const block = /^\\\[([\s\S]+)\\\]$/.exec(t);
  if (block?.[1]) return { latex: block[1].trim(), display: true };
  return null;
}
