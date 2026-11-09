import { useId, useState } from 'react';
import { EditorContent, useEditor, useEditorState } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import { Bold, Code, Italic, List, ListOrdered, Sigma, Underline } from 'lucide-react';
import { cn } from '@/lib/cn';
import { parseFormula } from '@/lib/math';
import { FormulaDialog, type FormulaValue } from './FormulaDialog';

interface RichTextEditorProps {
  label: string;
  value: string;
  onChange: (html: string) => void;
  help?: string;
  error?: string | undefined;
  /** Smaller editor for answer options. */
  compact?: boolean;
  hideLabel?: boolean;
}

/**
 * Rich text limited to what questions may contain (FR-QB-01): paragraphs, bold, italic, underline, inline code and
 * lists. The server sanitises again with the same allow-list, so nothing else can be stored. Formulas are LaTeX text
 * between \( \) or \[ \]; the Formula button writes them with a preview.
 */
export function RichTextEditor({ label, value, onChange, help, error, compact = false, hideLabel = false }: RichTextEditorProps) {
  const id = useId();
  const helpId = help ? `${id}-help` : undefined;
  const errorId = error ? `${id}-error` : undefined;
  const editor = useEditor({
    extensions: [
      StarterKit.configure({
        heading: false,
        blockquote: false,
        codeBlock: false,
        horizontalRule: false,
        strike: false,
        link: false,
      }),
    ],
    content: value,
    editorProps: {
      attributes: {
        id,
        role: 'textbox',
        'aria-multiline': 'true',
        'aria-label': label,
        ...(helpId || errorId ? { 'aria-describedby': [helpId, errorId].filter(Boolean).join(' ') } : {}),
        ...(error ? { 'aria-invalid': 'true' } : {}),
        class: cn('rich-text min-w-0 px-3 py-2.5 text-[15px] focus:outline-none', compact ? 'min-h-11' : 'min-h-28'),
      },
    },
    onUpdate: ({ editor: e }) => onChange(e.isEmpty ? '' : e.getHTML()),
    immediatelyRender: true,
  });
  const [formula, setFormula] = useState<{ initial: FormulaValue | null; from: number; to: number } | null>(null);
  const active = useEditorState({
    editor,
    selector: ({ editor: e }) => ({
      bold: e.isActive('bold'),
      italic: e.isActive('italic'),
      underline: e.isActive('underline'),
      code: e.isActive('code'),
      bullet: e.isActive('bulletList'),
      ordered: e.isActive('orderedList'),
    }),
  });

  const openFormula = () => {
    const { from, to } = editor.state.selection;
    setFormula({ initial: parseFormula(editor.state.doc.textBetween(from, to, ' ')), from, to });
  };
  const insertFormula = ({ latex, display }: FormulaValue) => {
    if (!formula) return;
    const text = display ? `\\[${latex}\\]` : `\\(${latex}\\)`;
    editor.chain().focus().insertContentAt({ from: formula.from, to: formula.to }, { type: 'text', text }).run();
    setFormula(null);
  };

  const tools = [
    { name: 'Bold', icon: Bold, on: active.bold, run: () => editor.chain().focus().toggleBold().run() },
    { name: 'Italic', icon: Italic, on: active.italic, run: () => editor.chain().focus().toggleItalic().run() },
    { name: 'Underline', icon: Underline, on: active.underline, run: () => editor.chain().focus().toggleUnderline().run() },
    { name: 'Code', icon: Code, on: active.code, run: () => editor.chain().focus().toggleCode().run() },
    ...(compact
      ? []
      : [
          { name: 'Bulleted list', icon: List, on: active.bullet, run: () => editor.chain().focus().toggleBulletList().run() },
          { name: 'Numbered list', icon: ListOrdered, on: active.ordered, run: () => editor.chain().focus().toggleOrderedList().run() },
        ]),
    { name: 'Formula', icon: Sigma, on: false, run: openFormula },
  ];

  return (
    <div className="flex flex-col gap-1.5">
      {/* The editable area is a div, not a form control, so it is named with aria-label rather than <label for>. */}
      <span aria-hidden="true" className={cn('text-sm font-semibold text-ink', hideLabel && 'sr-only')}>
        {label}
      </span>
      <div
        className={cn(
          'rounded-md border border-border-strong bg-surface focus-within:border-primary focus-within:shadow-[0_0_0_3px_var(--color-primary-soft)]',
          error && 'border-danger',
        )}
      >
        <div role="toolbar" aria-label={`${label} formatting`} className="flex gap-0.5 border-b border-border px-1.5 py-1">
          {tools.map(({ name, icon: Icon, on, run }) => (
            <button
              key={name}
              type="button"
              aria-label={name}
              aria-pressed={on}
              onMouseDown={(e) => e.preventDefault()}
              onClick={run}
              className={cn(
                'flex size-8 items-center justify-center rounded-sm text-ink-2 hover:bg-surface-2',
                on && 'bg-primary-soft text-primary-ink',
              )}
            >
              <Icon aria-hidden="true" className="size-4" />
            </button>
          ))}
        </div>
        <EditorContent editor={editor} />
      </div>
      {help && (
        <p id={helpId} className="text-[13px] text-muted">
          {help}
        </p>
      )}
      {error && (
        <p id={errorId} className="text-[13px] text-danger-ink">
          {error}
        </p>
      )}
      {formula && <FormulaDialog initial={formula.initial} onInsert={insertFormula} onClose={() => setFormula(null)} />}
    </div>
  );
}
