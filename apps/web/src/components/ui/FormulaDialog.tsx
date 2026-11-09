import { useId, useRef, useState } from 'react';
import 'katex/dist/katex.min.css';
import { Button } from './Button';
import { Dialog } from './Dialog';
import { MAX_FORMULA, type Formula } from '@/lib/math';
import { latexError, renderFormula } from '@/lib/mathCheck';

/** Common pieces, inserted at the cursor of the LaTeX box. */
const SNIPPETS: { label: string; title: string; latex: string }[] = [
  { label: 'a⁄b', title: 'Fraction', latex: '\\frac{a}{b}' },
  { label: '√x', title: 'Square root', latex: '\\sqrt{x}' },
  { label: 'ⁿ√x', title: 'nth root', latex: '\\sqrt[n]{x}' },
  { label: 'x²', title: 'Power', latex: 'x^{2}' },
  { label: 'x₁', title: 'Subscript', latex: 'x_{1}' },
  { label: '×', title: 'Times', latex: '\\times ' },
  { label: '÷', title: 'Divide', latex: '\\div ' },
  { label: '±', title: 'Plus or minus', latex: '\\pm ' },
  { label: '≤', title: 'Less than or equal', latex: '\\le ' },
  { label: '≥', title: 'Greater than or equal', latex: '\\ge ' },
  { label: '≠', title: 'Not equal', latex: '\\neq ' },
  { label: 'π', title: 'Pi', latex: '\\pi ' },
  { label: 'θ', title: 'Theta', latex: '\\theta ' },
  { label: '°', title: 'Degrees', latex: '^\\circ ' },
  { label: 'Σ', title: 'Sum', latex: '\\sum_{i=1}^{n} ' },
  { label: '∫', title: 'Integral', latex: '\\int_{a}^{b} ' },
  { label: 'lim', title: 'Limit', latex: '\\lim_{x \\to 0} ' },
  { label: '→', title: 'Arrow', latex: '\\rightarrow ' },
  { label: 'H₂O', title: 'Chemistry (formula or equation)', latex: '\\ce{2H2 + O2 -> 2H2O}' },
];

export type FormulaValue = Formula;

/**
 * Writes one formula in LaTeX with a live preview. The result is inserted into the text as \( … \) (in the line) or
 * \[ … \] (on its own line), which is also how formulas can be typed or imported directly.
 */
export function FormulaDialog({
  initial,
  onInsert,
  onClose,
}: {
  initial: FormulaValue | null;
  onInsert: (value: FormulaValue) => void;
  onClose: () => void;
}) {
  const id = useId();
  const area = useRef<HTMLTextAreaElement>(null);
  const [latex, setLatex] = useState(initial?.latex ?? '');
  const [display, setDisplay] = useState(initial?.display ?? false);
  const trimmed = latex.trim();
  const error =
    trimmed === ''
      ? null
      : trimmed.length > MAX_FORMULA
        ? `Use ${MAX_FORMULA} characters or fewer.`
        : latexError(trimmed, display);
  const preview = trimmed !== '' && !error ? renderFormula(trimmed, display) : null;

  const insertSnippet = (snippet: string) => {
    const el = area.current;
    const start = el?.selectionStart ?? latex.length;
    const end = el?.selectionEnd ?? latex.length;
    const next = latex.slice(0, start) + snippet + latex.slice(end);
    setLatex(next);
    requestAnimationFrame(() => {
      el?.focus();
      el?.setSelectionRange(start + snippet.length, start + snippet.length);
    });
  };

  return (
    <Dialog
      open
      onClose={onClose}
      title={initial ? 'Edit formula' : 'Insert a formula'}
      description="Write it in LaTeX, or start from a button below. Students see the typeset version."
      className="w-[min(640px,calc(100vw-32px))]"
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button disabled={trimmed === '' || error !== null} onClick={() => onInsert({ latex: trimmed, display })}>
            {initial ? 'Update formula' : 'Insert formula'}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <div role="group" aria-label="Insert a symbol or structure" className="flex flex-wrap gap-1.5">
          {SNIPPETS.map((s) => (
            <button
              key={s.title}
              type="button"
              title={s.title}
              aria-label={s.title}
              onClick={() => insertSnippet(s.latex)}
              className="h-9 min-w-9 rounded-sm border border-border-strong bg-surface px-2 text-[15px] text-ink hover:border-primary hover:bg-primary-soft"
            >
              {s.label}
            </button>
          ))}
        </div>
        <div className="flex flex-col gap-1.5">
          <label htmlFor={`${id}-latex`} className="text-sm font-semibold">
            LaTeX
          </label>
          <textarea
            id={`${id}-latex`}
            ref={area}
            value={latex}
            onChange={(e) => setLatex(e.target.value)}
            rows={3}
            spellCheck={false}
            autoCapitalize="off"
            autoComplete="off"
            placeholder="\frac{-b \pm \sqrt{b^2 - 4ac}}{2a}"
            aria-invalid={error ? 'true' : undefined}
            aria-describedby={`${id}-help${error ? ` ${id}-error` : ''}`}
            className="rounded-md border border-border-strong bg-surface px-3 py-2 font-mono text-[14.5px] focus:border-primary focus:shadow-[0_0_0_3px_var(--color-primary-soft)] focus:outline-none"
          />
          <p id={`${id}-help`} className="text-[13px] text-muted">
            For chemistry use <code>\ce{'{…}'}</code>, e.g. <code>\ce{'{CaCO3 -> CaO + CO2}'}</code>.
          </p>
          {error && (
            <p id={`${id}-error`} className="text-[13px] text-danger-ink">
              {error}
            </p>
          )}
        </div>
        <label className="flex items-center gap-2 text-[15px]">
          <input
            type="checkbox"
            checked={display}
            onChange={(e) => setDisplay(e.target.checked)}
            className="size-4 accent-[var(--color-primary)]"
          />
          Show on its own line, larger
        </label>
        <div className="flex flex-col gap-1.5">
          <span className="text-sm font-semibold">Preview</span>
          <div
            aria-live="polite"
            className="flex min-h-16 items-center overflow-x-auto rounded-md border border-border bg-[#fafbfc] px-4 py-3 text-[17px]"
          >
            {preview ? (
              <span dangerouslySetInnerHTML={{ __html: preview }} />
            ) : (
              <span className="text-[14px] text-muted">{error ? 'Fix the formula to see it.' : 'The formula appears here.'}</span>
            )}
          </div>
        </div>
      </div>
    </Dialog>
  );
}
