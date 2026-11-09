import { useEffect, useRef, type ElementType } from 'react';
import { KATEX_OPTIONS, MATH_DELIMITERS, escapeHtml, hasMath } from '@/lib/math';

interface RichContentProps {
  /** Server-sanitised question HTML (or editor output in the admin preview). */
  html?: string;
  /** Plain text, escaped here (key points, definitions). */
  text?: string;
  as?: ElementType;
  className?: string;
  id?: string;
}

/**
 * Question text as students see it, with any formulas typeset by KaTeX. KaTeX (and its fonts) is loaded only when the
 * text contains a formula, so pages without maths don't pay for it.
 */
export function RichContent({ html, text, as: Tag = 'div', className, id }: RichContentProps) {
  const ref = useRef<HTMLElement>(null);
  const content = html ?? escapeHtml(text ?? '');
  useEffect(() => {
    if (!hasMath(content)) return;
    let cancelled = false;
    void loadRenderer().then((render) => {
      if (!cancelled && ref.current) render(ref.current, { ...KATEX_OPTIONS, delimiters: MATH_DELIMITERS });
    });
    return () => {
      cancelled = true;
    };
  }, [content]);
  // The key makes React replace the element when the content changes, so typeset output never mixes with new text.
  return <Tag key={content} ref={ref} id={id} className={className} dangerouslySetInnerHTML={{ __html: content }} />;
}

type Render = typeof import('katex/contrib/auto-render').default;
let renderer: Promise<Render> | null = null;

function loadRenderer(): Promise<Render> {
  renderer ??= Promise.all([
    import('katex/contrib/auto-render'),
    import('katex/contrib/mhchem'), // \ce{…} for chemistry
    import('katex/dist/katex.min.css'),
  ]).then(([m]) => m.default);
  return renderer;
}
