import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { BookPlus, Plus } from 'lucide-react';
import { z } from 'zod';
import { Alert, Badge, Button, Card, CheckboxField, Dialog, EmptyState, ErrorState, Skeleton, TextAreaField, TextField } from '@/components/ui';
import { examKeys } from '@/features/exams/api';
import { ApiError } from '@/lib/api/problem';
import {
  catalogKeys,
  createModule,
  createSubject,
  createTopic,
  updateModule,
  updateSubject,
  updateTopic,
  useLibrary,
  type ContentStatus,
  type ModuleAdmin,
  type SubjectAdmin,
  type TopicAdmin,
} from './api';

type Editing =
  | { kind: 'subject'; subject?: SubjectAdmin }
  | { kind: 'module'; subject: SubjectAdmin; module?: ModuleAdmin }
  | { kind: 'topic'; subject: SubjectAdmin; module: ModuleAdmin; topic?: TopicAdmin };

/**
 * The content library: subjects → modules → topics, shared by every exam (FR-CAT-03). Nothing is deleted; archived
 * items stop counting towards exams but keep their questions and history.
 */
export function ContentLibraryPage() {
  const library = useLibrary();
  const [showArchived, setShowArchived] = useState(false);
  const [editing, setEditing] = useState<Editing | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const visible = <T extends { status: ContentStatus }>(items: T[]) => (showArchived ? items : items.filter((i) => i.status === 'ACTIVE'));

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-extrabold tracking-tight">Content library</h1>
          <p className="text-muted">Subjects, modules and topics. Link a subject to any number of exams.</p>
        </div>
        <Button onClick={() => setEditing({ kind: 'subject' })}>
          <BookPlus aria-hidden="true" className="size-4" /> New subject
        </Button>
      </div>
      <CheckboxField label="Show archived items" checked={showArchived} onChange={(e) => setShowArchived(e.target.checked)} />
      {notice && <Alert tone="success" title={notice} />}

      {library.isPending ? (
        <div className="flex flex-col gap-3" aria-busy="true">
          <p role="status" className="sr-only">
            Loading content…
          </p>
          <Skeleton className="h-32 w-full" />
          <Skeleton className="h-32 w-full" />
        </div>
      ) : library.isError ? (
        <ErrorState title="We couldn’t load the content library" onRetry={() => void library.refetch()} />
      ) : visible(library.data).length === 0 ? (
        <EmptyState icon={BookPlus} title="No subjects yet" action={<Button onClick={() => setEditing({ kind: 'subject' })}>Create a subject</Button>}>
          <p>A subject holds modules, and modules hold the topics questions belong to.</p>
        </EmptyState>
      ) : (
        <ul className="flex flex-col gap-4">
          {visible(library.data).map((subject) => (
            <li key={subject.id}>
              <Card className="flex flex-col gap-4 p-5">
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div className="min-w-0">
                    <h2 className="flex flex-wrap items-center gap-2 text-lg font-bold">
                      {subject.title}
                      {subject.code && <span className="text-[13px] font-medium text-muted">{subject.code}</span>}
                      {subject.status === 'ARCHIVED' && <Badge>Archived</Badge>}
                    </h2>
                    <p className="text-[14px] text-muted">
                      Used by {subject.linkedExamCount} exam{subject.linkedExamCount === 1 ? '' : 's'}
                      {subject.description && ` · ${subject.description}`}
                    </p>
                  </div>
                  <div className="flex gap-2">
                    <Button size="sm" variant="secondary" aria-label={`Edit subject ${subject.title}`} onClick={() => setEditing({ kind: 'subject', subject })}>
                      Edit
                    </Button>
                    {subject.status === 'ACTIVE' && (
                      <Button size="sm" variant="ghost" aria-label={`Add module to ${subject.title}`} onClick={() => setEditing({ kind: 'module', subject })}>
                        <Plus aria-hidden="true" className="size-4" /> Module
                      </Button>
                    )}
                  </div>
                </div>
                {visible(subject.modules).length === 0 ? (
                  <p className="text-[14.5px] text-muted">No modules yet.</p>
                ) : (
                  <ul className="flex flex-col gap-3">
                    {visible(subject.modules).map((module) => (
                      <li key={module.id} className="rounded-md border border-border">
                        <div className="flex flex-wrap items-center justify-between gap-2 border-b border-border bg-[#fafbfc] px-4 py-2.5">
                          <h3 className="flex items-center gap-2 font-semibold">
                            {module.title}
                            {module.status === 'ARCHIVED' && <Badge>Archived</Badge>}
                          </h3>
                          <div className="flex gap-2">
                            <Button size="sm" variant="ghost" aria-label={`Edit module ${module.title}`} onClick={() => setEditing({ kind: 'module', subject, module })}>
                              Edit
                            </Button>
                            {module.status === 'ACTIVE' && (
                              <Button size="sm" variant="ghost" aria-label={`Add topic to ${module.title}`} onClick={() => setEditing({ kind: 'topic', subject, module })}>
                                <Plus aria-hidden="true" className="size-4" /> Topic
                              </Button>
                            )}
                          </div>
                        </div>
                        {visible(module.topics).length === 0 ? (
                          <p className="px-4 py-3 text-[14.5px] text-muted">No topics yet.</p>
                        ) : (
                          <ul>
                            {visible(module.topics).map((topic) => (
                              <li key={topic.id} className="flex flex-wrap items-center justify-between gap-2 border-b border-border px-4 py-2.5 last:border-b-0">
                                <div className="min-w-0">
                                  <p className="flex items-center gap-2">
                                    {topic.title}
                                    {topic.status === 'ARCHIVED' && <Badge>Archived</Badge>}
                                  </p>
                                  <p className="text-[13px] text-muted">
                                    {topic.publishedQuestionCount} published question{topic.publishedQuestionCount === 1 ? '' : 's'}
                                    {topic.trialQuestionCount > 0 && ` · ${topic.trialQuestionCount} trial`}
                                    {topic.prerequisiteTopicIds.length > 0 &&
                                      ` · ${topic.prerequisiteTopicIds.length} prerequisite${topic.prerequisiteTopicIds.length === 1 ? '' : 's'}`}
                                  </p>
                                </div>
                                <Button size="sm" variant="ghost" aria-label={`Edit topic ${topic.title}`} onClick={() => setEditing({ kind: 'topic', subject, module, topic })}>
                                  Edit
                                </Button>
                              </li>
                            ))}
                          </ul>
                        )}
                      </li>
                    ))}
                  </ul>
                )}
              </Card>
            </li>
          ))}
        </ul>
      )}

      {editing && (
        <NodeDialog
          editing={editing}
          onClose={() => setEditing(null)}
          onSaved={(message) => {
            setEditing(null);
            setNotice(message);
          }}
        />
      )}
    </div>
  );
}

const schema = z.object({
  title: z.string().trim().min(2, 'Use at least 2 characters.').max(160, 'Use 160 characters or fewer.'),
  code: z
    .string()
    .trim()
    .max(40, 'Use 40 characters or fewer.')
    .regex(/^$|^[A-Z0-9][A-Z0-9_-]*$/, 'Use capital letters, digits, hyphens and underscores.'),
  description: z.string().max(500, 'Use 500 characters or fewer.'),
  displayOrder: z.number({ error: 'Enter a number.' }).int('Use a whole number.').min(0).max(100000),
  archived: z.boolean(),
  prerequisiteTopicIds: z.array(z.string()),
});
type FormValues = z.infer<typeof schema>;

function NodeDialog({ editing, onClose, onSaved }: { editing: Editing; onClose: () => void; onSaved: (message: string) => void }) {
  const queryClient = useQueryClient();
  const existing = editing.kind === 'subject' ? editing.subject : editing.kind === 'module' ? editing.module : editing.topic;
  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      title: existing?.title ?? '',
      code: (editing.kind === 'subject' && editing.subject?.code) || '',
      description: (editing.kind === 'subject' && editing.subject?.description) || '',
      displayOrder: existing && 'displayOrder' in existing ? existing.displayOrder : 0,
      archived: existing?.status === 'ARCHIVED',
      prerequisiteTopicIds: (editing.kind === 'topic' && editing.topic?.prerequisiteTopicIds) || [],
    },
  });
  const { register, handleSubmit, formState, setValue } = form;
  const [prerequisites, setPrerequisites] = useState<string[]>(form.getValues('prerequisiteTopicIds'));
  const errors = formState.errors;

  const save = useMutation({
    mutationFn: async (v: FormValues): Promise<string> => {
      const status: ContentStatus = v.archived ? 'ARCHIVED' : 'ACTIVE';
      switch (editing.kind) {
        case 'subject':
          if (editing.subject) {
            await updateSubject(editing.subject.id, editing.subject.version, { title: v.title, code: v.code, description: v.description, status });
            return `${v.title} saved.`;
          }
          await createSubject({ title: v.title, code: v.code, description: v.description });
          return `${v.title} created. Add its modules next.`;
        case 'module':
          if (editing.module) {
            await updateModule(editing.module.id, editing.module.version, { title: v.title, displayOrder: v.displayOrder, status });
            return `${v.title} saved.`;
          }
          await createModule({ subjectId: editing.subject.id, title: v.title, displayOrder: v.displayOrder });
          return `${v.title} added to ${editing.subject.title}.`;
        case 'topic':
          if (editing.topic) {
            await updateTopic(editing.topic.id, editing.topic.version, {
              title: v.title,
              displayOrder: v.displayOrder,
              status,
              prerequisiteTopicIds: prerequisites,
            });
            return `${v.title} saved.`;
          }
          await createTopic({ moduleId: editing.module.id, title: v.title, displayOrder: v.displayOrder, prerequisiteTopicIds: prerequisites });
          return `${v.title} added to ${editing.module.title}.`;
      }
    },
    onSuccess: (message) => {
      void queryClient.invalidateQueries({ queryKey: catalogKeys.all });
      void queryClient.invalidateQueries({ queryKey: examKeys.all });
      onSaved(message);
    },
  });

  const noun = editing.kind === 'subject' ? 'subject' : editing.kind === 'module' ? 'module' : 'topic';
  const otherTopics =
    editing.kind === 'topic'
      ? editing.subject.modules.flatMap((m) => m.topics).filter((t) => t.id !== editing.topic?.id && t.status === 'ACTIVE')
      : [];
  const error = save.error instanceof ApiError ? save.error : save.isError ? new ApiError(0, 'UNKNOWN', 'Please try again.') : undefined;

  return (
    <Dialog
      open
      onClose={onClose}
      title={existing ? `Edit ${noun}` : editing.kind === 'subject' ? 'New subject' : editing.kind === 'module' ? `New module in ${editing.subject.title}` : `New topic in ${editing.module.title}`}
    >
      <form noValidate className="flex flex-col gap-4" onSubmit={handleSubmit((v) => save.mutate(v))}>
        <TextField label="Title" maxLength={160} error={errors.title?.message} {...register('title')} />
        {editing.kind === 'subject' ? (
          <>
            <TextField label="Code (optional)" help="A short reference, for example PHARM-101." maxLength={40} error={errors.code?.message} {...register('code', { onChange: (e: { target: { value: string } }) => setValue('code', e.target.value.toUpperCase()) })} />
            <TextAreaField label="Description (optional)" rows={2} maxLength={500} error={errors.description?.message} {...register('description')} />
          </>
        ) : (
          <TextField label="Order" type="number" min={0} help="Lower numbers come first." error={errors.displayOrder?.message} {...register('displayOrder', { valueAsNumber: true })} />
        )}
        {editing.kind === 'topic' && otherTopics.length > 0 && (
          <fieldset className="flex flex-col gap-2">
            <legend className="mb-1 text-sm font-semibold">Prerequisites (optional)</legend>
            <p className="text-[13px] text-muted">Topics a student should know first. Recorded now; guided practice will use them to order topics.</p>
            <div className="flex max-h-48 flex-col gap-2 overflow-y-auto rounded-md border border-border p-3">
              {otherTopics.map((t) => (
                <CheckboxField
                  key={t.id}
                  label={t.title}
                  checked={prerequisites.includes(t.id)}
                  onChange={(e) => setPrerequisites((p) => (e.target.checked ? [...p, t.id] : p.filter((id) => id !== t.id)))}
                />
              ))}
            </div>
          </fieldset>
        )}
        {existing && (
          <CheckboxField
            label={`Archive this ${noun}`}
            help="Archived content no longer counts towards exams. Its questions and history are kept, and you can restore it."
            {...register('archived')}
          />
        )}
        {error && (
          <Alert tone="error" title={error.code === 'PUBLISH_BLOCKED' ? 'A published exam depends on this' : 'That wasn’t saved'}>
            <p>{error.problem?.errors?.[0]?.message ?? error.message}</p>
            {error.problem?.blockers && (
              <ul className="list-disc pl-5">
                {error.problem.blockers.map((b) => (
                  <li key={b}>{b}</li>
                ))}
              </ul>
            )}
          </Alert>
        )}
        <div className="flex flex-wrap justify-end gap-3">
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" loading={save.isPending}>
            {existing ? 'Save' : 'Create'}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
