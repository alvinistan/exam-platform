import { useState } from 'react';
import { Controller, useFieldArray, useForm, useWatch, type FieldPath } from 'react-hook-form';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Eye, Plus, Trash2 } from 'lucide-react';
import { Alert, Button, Card, Dialog, SelectField, Skeleton, TextAreaField, TextField } from '@/components/ui';
import { RichTextEditor } from '@/components/ui/RichTextEditor';
import { useLibrary } from '@/features/admin/catalog/api';
import { QuestionView } from '@/features/questions/QuestionView';
import { ApiError } from '@/lib/api/problem';
import { cn } from '@/lib/cn';
import { ImageField, type ImageValue } from './ImageField';
import {
  bankLabels,
  createMisconception,
  createQuestion,
  questionKeys,
  saveQuestion,
  useMisconceptions,
  type AnswerType,
  type Bank,
  type Difficulty,
  type QuestionDetail,
  type QuestionInput,
} from './api';

interface OptionValues {
  bodyHtml: string;
  image: ImageValue | null;
  isCorrect: boolean;
  misconceptionId: string;
}

interface FormValues {
  bank: Bank;
  topicId: string;
  answerType: AnswerType;
  stemHtml: string;
  stemImage: ImageValue | null;
  options: OptionValues[];
  explanationHtml: string;
  keyPoints: { text: string }[];
  definitions: { term: string; meaning: string }[];
  difficulty: Difficulty;
  estSeconds: number;
  marks: number;
  tags: string;
}

const LABELS = ['A', 'B', 'C', 'D', 'E'];

const emptyOption = (): OptionValues => ({ bodyHtml: '', image: null, isCorrect: false, misconceptionId: '' });

function toFormValues(q: QuestionDetail | undefined, topicId = ''): FormValues {
  if (!q) {
    return {
      bank: 'MAIN',
      topicId,
      answerType: 'SINGLE',
      stemHtml: '',
      stemImage: null,
      options: [emptyOption(), emptyOption(), emptyOption(), emptyOption()],
      explanationHtml: '',
      keyPoints: [],
      definitions: [],
      difficulty: 'MEDIUM',
      estSeconds: 60,
      marks: 1,
      tags: '',
    };
  }
  return {
    bank: q.bank,
    topicId: q.topicId,
    answerType: q.answerType,
    stemHtml: q.stemHtml,
    stemImage: q.stemMediaKey ? { key: q.stemMediaKey, url: q.stemMediaUrl, alt: q.stemMediaAlt ?? '' } : null,
    options: q.options.map((o) => ({
      bodyHtml: o.bodyHtml,
      image: o.mediaKey ? { key: o.mediaKey, url: o.mediaUrl, alt: o.mediaAlt ?? '' } : null,
      isCorrect: o.isCorrect,
      misconceptionId: o.misconceptionId ?? '',
    })),
    explanationHtml: q.explanationHtml,
    keyPoints: q.keyPoints.map((text) => ({ text })),
    definitions: q.definitions,
    difficulty: q.difficulty,
    estSeconds: q.estSeconds,
    marks: q.marks,
    tags: q.tags.join(', '),
  };
}

function toInput(v: FormValues): QuestionInput {
  return {
    topicId: v.topicId,
    bank: v.bank,
    answerType: v.answerType,
    stemHtml: v.stemHtml,
    stemMediaKey: v.stemImage?.key ?? null,
    stemMediaAlt: v.stemImage?.alt ?? null,
    options: v.options.map((o) => ({
      bodyHtml: o.bodyHtml,
      mediaKey: o.image?.key ?? null,
      mediaAlt: o.image?.alt ?? null,
      isCorrect: o.isCorrect,
      misconceptionId: o.isCorrect || !o.misconceptionId ? null : o.misconceptionId,
    })),
    explanationHtml: v.explanationHtml,
    definitions: v.definitions.filter((d) => d.term.trim()),
    keyPoints: v.keyPoints.map((k) => k.text.trim()).filter(Boolean),
    difficulty: v.difficulty,
    estSeconds: v.estSeconds,
    marks: v.marks,
    tags: v.tags.split(',').map((t) => t.trim()).filter(Boolean),
  };
}

/** Server field paths ("options[1].mediaAlt") mapped onto this form's fields. */
function formField(path: string): FieldPath<FormValues> | null {
  const option = /^options\[(\d)]\.(\w+)$/.exec(path);
  if (option) {
    const field = option[2] === 'mediaAlt' || option[2] === 'mediaKey' ? 'image' : option[2] === 'misconceptionId' ? 'misconceptionId' : 'bodyHtml';
    return `options.${Number(option[1])}.${field}` as FieldPath<FormValues>;
  }
  const map: Record<string, FieldPath<FormValues>> = {
    topicId: 'topicId',
    stemHtml: 'stemHtml',
    stemMediaAlt: 'stemImage',
    stemMediaKey: 'stemImage',
    estSeconds: 'estSeconds',
    marks: 'marks',
    tags: 'tags',
    explanationHtml: 'explanationHtml',
  };
  return map[path] ?? null;
}

interface Props {
  question: QuestionDetail | undefined;
  initialTopicId?: string;
  onSaved: (q: QuestionDetail, created: boolean) => void;
}

/**
 * The question editor with a live student preview (FR-QB-01, FR-QB-06). It waits for the content library, because a
 * native select can only show a value whose option already exists.
 */
export function QuestionForm(props: Props) {
  const library = useLibrary();
  if (library.isPending) {
    return (
      <div aria-busy="true">
        <p role="status" className="sr-only">
          Loading editor…
        </p>
        <Skeleton className="h-96 w-full rounded-lg" />
      </div>
    );
  }
  return <Editor {...props} />;
}

function Editor({ question, initialTopicId, onSaved }: Props) {
  const queryClient = useQueryClient();
  const isNew = question === undefined;
  const library = useLibrary();
  const form = useForm<FormValues>({ defaultValues: toFormValues(question, initialTopicId) });
  const { register, handleSubmit, control, formState, setValue, setError, getValues } = form;
  const errors = formState.errors;
  const options = useFieldArray({ control, name: 'options' });
  const keyPoints = useFieldArray({ control, name: 'keyPoints' });
  const definitions = useFieldArray({ control, name: 'definitions' });
  const values = useWatch({ control }) as FormValues;
  const [previewMode, setPreviewMode] = useState<'answering' | 'review'>('answering');
  const [previewSelected, setPreviewSelected] = useState<string[]>([]);
  const [newMisconceptionFor, setNewMisconceptionFor] = useState<number | null>(null);
  const misconceptions = useMisconceptions(values.topicId);

  const save = useMutation({
    mutationFn: (v: FormValues) => {
      if (question === undefined) return createQuestion(toInput(v));
      const latest = queryClient.getQueryData<QuestionDetail>(questionKeys.detail(question.id))?.version ?? question.version;
      return saveQuestion(question.id, latest, toInput(v));
    },
    onSuccess: (saved) => {
      queryClient.setQueryData(questionKeys.detail(saved.id), saved);
      void queryClient.invalidateQueries({ queryKey: questionKeys.all });
      onSaved(saved, isNew);
    },
    onError: (error) => {
      if (!(error instanceof ApiError)) return;
      for (const e of error.problem?.errors ?? []) {
        const field = formField(e.field);
        if (field) setError(field, { message: e.message });
      }
    },
  });

  const submit = handleSubmit((v) => {
    if (!v.topicId) {
      setError('topicId', { message: 'Choose a topic.' });
      return;
    }
    save.mutate(v);
  });

  const setCorrect = (index: number, checked: boolean) => {
    if (getValues('answerType') === 'SINGLE' && checked) {
      getValues('options').forEach((_, i) => setValue(`options.${i}.isCorrect`, i === index, { shouldDirty: true }));
    } else {
      setValue(`options.${index}.isCorrect`, checked, { shouldDirty: true });
    }
  };

  const topicGroups = (library.data ?? [])
    .filter((s) => s.status === 'ACTIVE' || s.modules.some((m) => m.topics.some((t) => t.id === values.topicId)))
    .flatMap((s) =>
      s.modules.map((m) => ({
        label: `${s.title} › ${m.title}`,
        topics: m.topics.filter((t) => (t.status === 'ACTIVE' && m.status === 'ACTIVE') || t.id === values.topicId),
      })),
    )
    .filter((g) => g.topics.length > 0);

  const unhandled =
    save.error instanceof ApiError && !save.error.problem?.errors?.length
      ? save.error.message
      : save.isError && !(save.error instanceof ApiError)
        ? 'Please try again.'
        : null;
  const fieldErrorCount = save.error instanceof ApiError ? (save.error.problem?.errors?.length ?? 0) : 0;
  const multi = values.answerType === 'MULTI';

  return (
    <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_400px]">
      <form noValidate onSubmit={(e) => void submit(e)} className="flex min-w-0 flex-col gap-6" aria-label="Question">
        <Card className="flex flex-col gap-5 p-6">
          <h2 className="text-lg font-bold">Where it belongs</h2>
          <div className="grid gap-5 sm:grid-cols-2">
            {isNew ? (
              <fieldset className="flex flex-col gap-2">
                <legend className="mb-1 text-sm font-semibold">Bank</legend>
                {(['MAIN', 'TRIAL'] as const).map((b) => (
                  <label key={b} className="flex items-center gap-2 text-[15px]">
                    <input type="radio" value={b} className="size-[18px] accent-primary" {...register('bank')} />
                    {bankLabels[b]}
                    <span className="text-[13px] text-muted">{b === 'MAIN' ? 'full exam content' : 'free trial questions'}</span>
                  </label>
                ))}
              </fieldset>
            ) : (
              <div className="flex flex-col gap-1">
                <p className="text-sm font-semibold">Bank</p>
                <p className="text-[15px] text-ink-2">{bankLabels[values.bank]} · move it with a bulk action in the list</p>
              </div>
            )}
            <SelectField label="Topic" {...register('topicId')} aria-invalid={errors.topicId ? true : undefined}>
              <option value="">Choose a topic</option>
              {topicGroups.map((g) => (
                <optgroup key={g.label} label={g.label}>
                  {g.topics.map((t) => (
                    <option key={t.id} value={t.id}>
                      {t.title}
                    </option>
                  ))}
                </optgroup>
              ))}
            </SelectField>
          </div>
          {errors.topicId && <p className="-mt-3 text-[13px] text-danger-ink">{errors.topicId.message}</p>}
        </Card>

        <Card className="flex flex-col gap-5 p-6">
          <h2 className="text-lg font-bold">Question</h2>
          <fieldset className="flex flex-wrap gap-x-6 gap-y-2">
            <legend className="mb-2 text-sm font-semibold">Answer type</legend>
            <label className="flex items-center gap-2 text-[15px]">
              <input
                type="radio"
                value="SINGLE"
                className="size-[18px] accent-primary"
                {...register('answerType', {
                  onChange: () => {
                    const first = getValues('options').findIndex((o) => o.isCorrect);
                    getValues('options').forEach((_, i) => setValue(`options.${i}.isCorrect`, i === first));
                  },
                })}
              />
              One correct answer
            </label>
            <label className="flex items-center gap-2 text-[15px]">
              <input type="radio" value="MULTI" className="size-[18px] accent-primary" {...register('answerType')} />
              Two or more correct answers
            </label>
          </fieldset>
          <Controller
            control={control}
            name="stemHtml"
            render={({ field }) => (
              <RichTextEditor label="Question text" value={field.value} onChange={field.onChange} error={errors.stemHtml?.message} />
            )}
          />
          <Controller
            control={control}
            name="stemImage"
            render={({ field }) => (
              <ImageField label="Question image" value={field.value} onChange={field.onChange} altError={errors.stemImage?.message} />
            )}
          />
        </Card>

        <Card className="flex flex-col gap-4 p-6">
          <div>
            <h2 className="text-lg font-bold">Options</h2>
            <p className="text-[14.5px] text-muted">
              4 or 5 options. {multi ? 'Tick every correct option.' : 'Choose the one correct option.'} Wrong options can
              name the misconception behind them.
            </p>
          </div>
          <ol className="flex flex-col gap-4">
            {options.fields.map((field, i) => {
              const label = LABELS[i] ?? '?';
              const option = values.options[i];
              return (
                <li key={field.id} className={cn('flex flex-col gap-3 rounded-md border p-4', option?.isCorrect ? 'border-success bg-success-soft/40' : 'border-border')}>
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <p className="font-bold">Option {label}</p>
                    <div className="flex items-center gap-3">
                      <label className="flex items-center gap-2 text-[14.5px] font-semibold">
                        <input
                          type={multi ? 'checkbox' : 'radio'}
                          name={multi ? undefined : 'correct-option'}
                          checked={option?.isCorrect ?? false}
                          onChange={(e) => setCorrect(i, e.target.checked)}
                          className="size-[18px] accent-success"
                          aria-label={`Option ${label} is correct`}
                        />
                        Correct
                      </label>
                      {options.fields.length > 2 && (
                        <Button size="sm" variant="ghost" aria-label={`Remove option ${label}`} onClick={() => options.remove(i)}>
                          <Trash2 aria-hidden="true" className="size-4" />
                        </Button>
                      )}
                    </div>
                  </div>
                  <Controller
                    control={control}
                    name={`options.${i}.bodyHtml`}
                    render={({ field: f }) => (
                      <RichTextEditor compact hideLabel label={`Option ${label} text`} value={f.value} onChange={f.onChange} />
                    )}
                  />
                  <Controller
                    control={control}
                    name={`options.${i}.image`}
                    render={({ field: f }) => (
                      <ImageField
                        label={`Option ${label} image`}
                        value={f.value}
                        onChange={f.onChange}
                        altError={errors.options?.[i]?.image?.message}
                      />
                    )}
                  />
                  {!option?.isCorrect && (
                    <div className="flex flex-wrap items-end gap-2">
                      <div className="min-w-56 flex-1">
                        <SelectField
                          label={`Misconception behind option ${label} (optional)`}
                          value={option?.misconceptionId ?? ''}
                          onChange={(e) => setValue(`options.${i}.misconceptionId`, e.target.value, { shouldDirty: true })}
                        >
                          <option value="">None</option>
                          {misconceptions.data?.map((m) => (
                            <option key={m.id} value={m.id}>
                              {m.title}
                            </option>
                          ))}
                        </SelectField>
                      </div>
                      <Button size="sm" variant="ghost" onClick={() => setNewMisconceptionFor(i)} disabled={!values.topicId}>
                        <Plus aria-hidden="true" className="size-4" /> New
                      </Button>
                    </div>
                  )}
                </li>
              );
            })}
          </ol>
          {options.fields.length < 5 && (
            <div>
              <Button variant="secondary" size="sm" onClick={() => options.append(emptyOption())}>
                <Plus aria-hidden="true" className="size-4" /> Add option
              </Button>
            </div>
          )}
        </Card>

        <Card className="flex flex-col gap-5 p-6">
          <h2 className="text-lg font-bold">Explanation</h2>
          <Controller
            control={control}
            name="explanationHtml"
            render={({ field }) => (
              <RichTextEditor label="Why the answer is right" value={field.value} onChange={field.onChange} help="Shown when answers are revealed." />
            )}
          />
          <fieldset className="flex flex-col gap-2">
            <legend className="mb-1 text-sm font-semibold">Key points</legend>
            {keyPoints.fields.map((f, i) => (
              <div key={f.id} className="flex items-end gap-2">
                <div className="flex-1">
                  <TextField label={`Key point ${i + 1}`} maxLength={300} {...register(`keyPoints.${i}.text`)} />
                </div>
                <Button size="sm" variant="ghost" aria-label={`Remove key point ${i + 1}`} onClick={() => keyPoints.remove(i)}>
                  <Trash2 aria-hidden="true" className="size-4" />
                </Button>
              </div>
            ))}
            {keyPoints.fields.length < 10 && (
              <div>
                <Button size="sm" variant="ghost" onClick={() => keyPoints.append({ text: '' })}>
                  <Plus aria-hidden="true" className="size-4" /> Add key point
                </Button>
              </div>
            )}
          </fieldset>
          <fieldset className="flex flex-col gap-2">
            <legend className="mb-1 text-sm font-semibold">Definitions</legend>
            {definitions.fields.map((f, i) => (
              <div key={f.id} className="grid items-end gap-2 sm:grid-cols-[200px_1fr_auto]">
                <TextField label={`Term ${i + 1}`} maxLength={120} {...register(`definitions.${i}.term`)} />
                <TextField label={`Meaning ${i + 1}`} maxLength={1000} {...register(`definitions.${i}.meaning`)} />
                <Button size="sm" variant="ghost" aria-label={`Remove definition ${i + 1}`} onClick={() => definitions.remove(i)}>
                  <Trash2 aria-hidden="true" className="size-4" />
                </Button>
              </div>
            ))}
            {definitions.fields.length < 20 && (
              <div>
                <Button size="sm" variant="ghost" onClick={() => definitions.append({ term: '', meaning: '' })}>
                  <Plus aria-hidden="true" className="size-4" /> Add definition
                </Button>
              </div>
            )}
          </fieldset>
        </Card>

        <Card className="flex flex-col gap-5 p-6">
          <h2 className="text-lg font-bold">Details</h2>
          <div className="grid gap-5 sm:grid-cols-3">
            <SelectField label="Difficulty" {...register('difficulty')}>
              <option value="SIMPLE">Simple</option>
              <option value="MEDIUM">Medium</option>
              <option value="HARD">Hard</option>
            </SelectField>
            <TextField
              label="Solving time (seconds)"
              type="number"
              min={10}
              max={600}
              error={errors.estSeconds?.message}
              {...register('estSeconds', { valueAsNumber: true, min: { value: 10, message: 'Use 10 to 600 seconds.' }, max: { value: 600, message: 'Use 10 to 600 seconds.' } })}
            />
            <TextField
              label="Marks"
              type="number"
              step="0.25"
              min={0.25}
              max={100}
              error={errors.marks?.message}
              {...register('marks', { valueAsNumber: true })}
            />
          </div>
          <TextField label="Tags" help="Separate tags with commas, for example: dosage, paediatrics." error={errors.tags?.message} {...register('tags')} />
        </Card>

        {fieldErrorCount > 0 && (
          <Alert tone="error" title="The question wasn’t saved">
            <p>{fieldErrorCount === 1 ? 'One field needs attention.' : `${fieldErrorCount} fields need attention.`}</p>
            <ul className="list-disc pl-5">
              {save.error instanceof ApiError &&
                save.error.problem?.errors?.map((e) => <li key={e.field + e.message}>{e.message}</li>)}
            </ul>
          </Alert>
        )}
        {unhandled && (
          <Alert tone="error" title="The question wasn’t saved">
            <p>{unhandled}</p>
          </Alert>
        )}
        <div className="flex flex-wrap gap-3">
          <Button type="submit" loading={save.isPending}>
            {isNew ? 'Create draft' : 'Save'}
          </Button>
          {!isNew && formState.isDirty && (
            <span className="self-center text-[14px] text-muted">Unsaved changes</span>
          )}
        </div>
      </form>

      <aside aria-label="Student preview" className="xl:sticky xl:top-6 xl:self-start">
        <Card className="flex flex-col gap-4 p-5">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h2 className="flex items-center gap-2 text-lg font-bold">
              <Eye aria-hidden="true" className="size-5" /> Student preview
            </h2>
            <div role="group" aria-label="Preview state" className="flex rounded-md border border-border p-0.5">
              {(['answering', 'review'] as const).map((m) => (
                <button
                  key={m}
                  type="button"
                  aria-pressed={previewMode === m}
                  onClick={() => setPreviewMode(m)}
                  className={cn('rounded-sm px-3 py-1 text-[13.5px] font-semibold', previewMode === m ? 'bg-primary-soft text-primary-ink' : 'text-ink-2')}
                >
                  {m === 'answering' ? 'Answering' : 'Review'}
                </button>
              ))}
            </div>
          </div>
          <QuestionView
            mode={previewMode}
            selected={previewSelected}
            onSelect={setPreviewSelected}
            question={{
              answerType: values.answerType,
              stemHtml: values.stemHtml || '<p class="text-muted">The question text appears here.</p>',
              stemMediaUrl: values.stemImage?.url ?? null,
              stemMediaAlt: values.stemImage?.alt ?? null,
              options: values.options.map((o, i) => ({
                label: LABELS[i] ?? '?',
                bodyHtml: o.bodyHtml,
                mediaUrl: o.image?.url ?? null,
                mediaAlt: o.image?.alt ?? null,
                isCorrect: o.isCorrect,
              })),
              explanationHtml: values.explanationHtml,
              keyPoints: values.keyPoints.map((k) => k.text).filter(Boolean),
              definitions: values.definitions.filter((d) => d.term.trim()),
            }}
          />
        </Card>
      </aside>

      {newMisconceptionFor !== null && (
        <NewMisconceptionDialog
          topicId={values.topicId}
          onClose={() => setNewMisconceptionFor(null)}
          onCreated={(id) => {
            setValue(`options.${newMisconceptionFor}.misconceptionId`, id, { shouldDirty: true });
            setNewMisconceptionFor(null);
          }}
        />
      )}
    </div>
  );
}

function NewMisconceptionDialog({ topicId, onClose, onCreated }: { topicId: string; onClose: () => void; onCreated: (id: string) => void }) {
  const queryClient = useQueryClient();
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const create = useMutation({
    mutationFn: () => createMisconception({ topicId, title: title.trim(), description: description.trim() }),
    onSuccess: async (m) => {
      await queryClient.invalidateQueries({ queryKey: questionKeys.misconceptions(topicId) });
      onCreated(m.id);
    },
  });
  return (
    <Dialog open onClose={onClose} title="New misconception" description="A wrong idea students often have, so results can explain why an answer was wrong.">
      <form
        noValidate
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          create.mutate();
        }}
      >
        <TextField label="Name" maxLength={160} value={title} onChange={(e) => setTitle(e.target.value)} />
        <TextAreaField label="Description" rows={3} maxLength={1000} value={description} onChange={(e) => setDescription(e.target.value)} />
        {create.error instanceof ApiError && (
          <Alert tone="error" title="Not saved">
            <p>{create.error.problem?.errors?.[0]?.message ?? create.error.message}</p>
          </Alert>
        )}
        <div className="flex justify-end gap-3">
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" loading={create.isPending} disabled={title.trim().length < 2 || !description.trim()}>
            Create
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

