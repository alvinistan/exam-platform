declare module 'katex/contrib/auto-render' {
  import type { KatexOptions } from 'katex';
  interface RenderMathInElementOptions extends KatexOptions {
    delimiters?: { left: string; right: string; display: boolean }[];
    ignoredTags?: string[];
    ignoredClasses?: string[];
  }
  export default function renderMathInElement(element: HTMLElement, options?: RenderMathInElementOptions): void;
}
declare module 'katex/contrib/mhchem';
declare module 'katex/dist/katex.min.css';
