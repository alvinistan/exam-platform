import { useId, useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Archive, ArchiveRestore, Pencil, Plus } from 'lucide-react';
import {
  Alert,
  Button,
  Card,
  Dialog,
  EmptyState,
  ErrorState,
  SelectField,
  Skeleton,
  TextField,
  buttonClasses,
} from '@/components/ui';
import { examKeys } from '@/features/exams/api';
import {
  catalogKeys,
  createModule,
  createTopic,
  linkSubject,
  unlinkSubject,
  updateModule,
  updateSubject,
  updateTopic,
  useLibrary,
  type AdminExamDetail,
  type ModuleAdmin,
  type SubjectAdmin,
  type TopicAdmin,
} from '@/features/admin/catalog/api';
import { ApiError } from '@/lib/api/problem';
import { cn } from '@/lib/cn';
import { StepFooter, StepHeading } from './SetupShell';
import { setupPath } from './steps';

type Run = (action: () => Promise<unknown>, done?: string) => void;

/** Step 2: Subject → Module → Topic, built right here. Students choose topics; each question belongs to one topic. */
export function StructureStep({ exam }: { exam: AdminExamDetail }) {
  const queryClient = useQueryClient();
  const library = useLibrary();
  const [error, setError] = useState<ApiError | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [copying, setCopying] = useState(false);

  // Every change here goes through one place: refresh the tree and the exam's counts, and report problems.
  const mutation = useMutation({
    mutationFn: ({ action }: { action: () => Promise<unknown>; done?: string | undefined }) => action(),
    onMutate: () => {
      setError(null);
      setNotice(null);
    },
    onSuccess: (result, { done }) => {
      if (result && typeof result === 'object' && 'subjectIds' in result) {
        queryClient.setQueryData(catalogKeys.exam(exam.id), result);
      }
      void queryClient.invalidateQueries({ queryKey: catalogKeys.library() });
      void queryClient.invalidateQueries({ queryKey: catalogKeys.exams() });
      void queryClient.invalidateQueries({ queryKey: examKeys.all });
      if (done) setNotice(done);
    },
    onError: (e) => setError(e instanceof ApiError ? e : new ApiError(0, 'UNKNOWN', 'Please try again.')),
  });
  const run: Run = (action, done) => mutation.mutate({ action, done });

  const subjects = exam.subjectIds
    .map((id) => library.data?.find((s) => s.id === id))
    .filter((s): s is SubjectAdmin => s !== undefined);
  const others = library.data?.filter((s) => s.status === 'ACTIVE' && !exam.subjectIds.includes(s.id)) ?? [];

  return (
    <Card className="flex flex-col gap-6 p-6 sm:p-8">
      <StepHeading
        number={2}
        title="What does this exam cover?"
        aside={
          others.length > 0 && (
            <Button variant="secondary" onClick={() => setCopying(true)}>
              Use a subject from another exam
            </Button>
          )
        }
      >
        Subject → Module → Topic. Students pick topics, and every question belongs to one topic.
      </StepHeading>

      {notice && <Alert tone="success" title={notice} />}
      {error && (
        <Alert
          tone="error"
          title={error.code === 'PUBLISH_BLOCKED' ? 'That would stop the live exam from working' : 'That didn’t work'}
        >
          <p>{error.message}</p>
          {error.problem?.blockers && (
            <ul className="list-disc pl-5">
              {error.problem.blockers.map((b) => (
                <li key={b}>{b}</li>
              ))}
            </ul>
          )}
        </Alert>
      )}

      {library.isPending ? (
        <div aria-busy="true" className="flex flex-col gap-3">
          <p role="status" className="sr-only">
            Loading subjects…
          </p>
          <Skeleton className="h-40 w-full" />
        </div>
      ) : library.isError ? (
        <ErrorState title="We couldn’t load the subjects" onRetry={() => void library.refetch()} />
      ) : subjects.length === 0 ? (
        <EmptyState title="No subjects yet">
          <p>Start with a subject, such as “Science”. Then add its modules (“Biology”) and topics (“Cell structure”).</p>
        </EmptyState>
      ) : (
        subjects.map((subject) => (
          <SubjectBlock key={subject.id} exam={exam} subject={subject} run={run} busy={mutation.isPending} />
        ))
      )}

      <AddByName
        label="New subject name"
        button="Add subject"
        placeholder="For example: Science"
        onAdd={(title) => run(() => linkSubject(exam.id, { title }), `${title} added.`)}
        busy={mutation.isPending}
      />

      <p className="text-[14px] text-muted">
        Need topic prerequisites, or to manage subjects shared by several exams?{' '}
        <Link to="/admin/content">Open the subject library</Link>.
      </p>

      <StepFooter>
        <Link to={setupPath(exam.id, 'details')} className="inline-flex min-h-11 items-center font-semibold">
          ← Back
        </Link>
        <Link to={setupPath(exam.id, 'questions')} className={buttonClasses('primary')}>
          Continue → Questions
        </Link>
      </StepFooter>

      <CopySubjectDialog
        open={copying}
        subjects={others}
        onClose={() => setCopying(false)}
        onPick={(s) => {
          setCopying(false);
          run(
            () => linkSubject(exam.id, { subjectId: s.id }),
            `${s.title} added. Changes to it show in every exam that uses it.`,
          );
        }}
      />
    </Card>
  );
}

function SubjectBlock({ exam, subject, run, busy }: { exam: AdminExamDetail; subject: SubjectAdmin; run: Run; busy: boolean }) {
  const [renaming, setRenaming] = useState(false);
  const [removing, setRemoving] = useState(false);
  const active = subject.modules.filter((m) => m.status === 'ACTIVE');
  const archived = subject.modules.filter((m) => m.status === 'ARCHIVED');
  const headingId = `subject-${subject.id}`;

  return (
    <section aria-labelledby={headingId} className="overflow-hidden rounded-lg border border-border">
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-border bg-[#fafbfc] px-4 py-3">
        <h3 id={headingId} className="text-lg font-bold">
          {subject.title}
          {subject.linkedExamCount > 1 && (
            <span className="ml-2 text-[13px] font-normal text-muted">
              also used by {subject.linkedExamCount - 1} other exam(s)
            </span>
          )}
        </h3>
        <div className="flex flex-wrap gap-1">
          <Button variant="ghost" size="sm" onClick={() => setRenaming(true)} aria-label={`Rename ${subject.title}`}>
            <Pencil aria-hidden="true" className="size-4" /> Rename
          </Button>
          <Button variant="ghost" size="sm" onClick={() => setRemoving(true)} aria-label={`Remove from exam: ${subject.title}`}>
            Remove from exam
          </Button>
        </div>
      </div>
      <div className="flex flex-col gap-5 p-4">
        {active.length === 0 && <p className="text-muted">Add a module, such as “Biology”, to hold this subject’s topics.</p>}
        {active.map((module) => (
          <ModuleBlock key={module.id} module={module} run={run} busy={busy} />
        ))}
        {archived.length > 0 && (
          <details>
            <summary className="cursor-pointer text-[14.5px] font-semibold text-muted">
              Archived modules ({archived.length})
            </summary>
            <ul className="mt-2 flex flex-col gap-2">
              {archived.map((m) => (
                <li key={m.id} className="flex flex-wrap items-center gap-2 text-muted">
                  {m.title}
                  <Button
                    variant="ghost"
                    size="sm"
                    disabled={busy}
                    onClick={() =>
                      run(
                        () =>
                          updateModule(m.id, m.version, {
                            title: m.title,
                            displayOrder: m.displayOrder,
                            status: 'ACTIVE',
                          }),
                        `${m.title} restored.`,
                      )
                    }
                  >
                    <ArchiveRestore aria-hidden="true" className="size-4" /> Restore
                  </Button>
                </li>
              ))}
            </ul>
          </details>
        )}
        <AddByName
          label={`New module in ${subject.title}`}
          button="Add module"
          placeholder={`New module in ${subject.title}`}
          onAdd={(title) =>
            run(
              () =>
                createModule({
                  subjectId: subject.id,
                  title,
                  displayOrder: subject.modules.length + 1,
                }),
              `${title} added to ${subject.title}.`,
            )
          }
          busy={busy}
          compact
        />
      </div>

      <RenameDialog
        open={renaming}
        what="subject"
        current={subject.title}
        onClose={() => setRenaming(false)}
        onSave={(title) => {
          setRenaming(false);
          run(
            () =>
              updateSubject(subject.id, subject.version, {
                title,
                code: subject.code ?? '',
                description: subject.description ?? '',
              }),
            'Subject renamed.',
          );
        }}
      />
      <Dialog
        open={removing}
        onClose={() => setRemoving(false)}
        title={`Remove ${subject.title} from ${exam.title}?`}
        description="Its modules, topics and questions are kept, and you can add it back later with “Use a subject from another exam”."
        footer={
          <>
            <Button variant="secondary" onClick={() => setRemoving(false)}>
              Cancel
            </Button>
            <Button
              variant="danger"
              onClick={() => {
                setRemoving(false);
                run(() => unlinkSubject(exam.id, subject.id), `${subject.title} removed from this exam.`);
              }}
            >
              Remove
            </Button>
          </>
        }
      />
    </section>
  );
}

function ModuleBlock({ module, run, busy }: { module: ModuleAdmin; run: Run; busy: boolean }) {
  const [renaming, setRenaming] = useState<TopicAdmin | 'module' | null>(null);
  const active = module.topics.filter((t) => t.status === 'ACTIVE');
  const archived = module.topics.filter((t) => t.status === 'ARCHIVED');
  const headingId = `module-${module.id}`;

  return (
    <div role="group" aria-labelledby={headingId} className="flex flex-col gap-2.5">
      <div className="flex flex-wrap items-center gap-2">
        <h4 id={headingId} className="font-bold">
          {module.title}
        </h4>
        <Button variant="ghost" size="sm" onClick={() => setRenaming('module')} aria-label={`Rename module ${module.title}`}>
          <Pencil aria-hidden="true" className="size-4" />
        </Button>
        <Button
          variant="ghost"
          size="sm"
          disabled={busy}
          aria-label={`Archive module ${module.title}`}
          onClick={() =>
            run(
              () =>
                updateModule(module.id, module.version, {
                  title: module.title,
                  displayOrder: module.displayOrder,
                  status: 'ARCHIVED',
                }),
              `${module.title} archived. Its topics are hidden from students.`,
            )
          }
        >
          <Archive aria-hidden="true" className="size-4" />
        </Button>
      </div>
      <ul className="flex flex-wrap items-center gap-2" aria-label={`Topics in ${module.title}`}>
        {active.map((t) => (
          <li
            key={t.id}
            className="inline-flex min-h-10 items-center gap-1 rounded-full border border-border-strong bg-surface py-0.5 pr-1 pl-3.5 text-[14.5px] font-semibold"
          >
            {t.title}
            <span className={cn('ml-1 text-[13px] font-normal', t.publishedQuestionCount ? 'text-muted' : 'text-warning-ink')}>
              {t.publishedQuestionCount} ready
            </span>
            <button
              type="button"
              className="grid size-8 place-items-center rounded-full text-muted hover:bg-surface-2"
              aria-label={`Rename topic ${t.title}`}
              onClick={() => setRenaming(t)}
            >
              <Pencil aria-hidden="true" className="size-3.5" />
            </button>
            <button
              type="button"
              className="grid size-8 place-items-center rounded-full text-muted hover:bg-surface-2"
              aria-label={`Archive topic ${t.title}`}
              disabled={busy}
              onClick={() =>
                run(
                  () =>
                    updateTopic(t.id, t.version, {
                      title: t.title,
                      displayOrder: t.displayOrder,
                      status: 'ARCHIVED',
                    }),
                  `${t.title} archived.`,
                )
              }
            >
              <Archive aria-hidden="true" className="size-3.5" />
            </button>
          </li>
        ))}
        {active.length === 0 && <li className="text-[14.5px] text-muted">No topics yet.</li>}
      </ul>
      {archived.length > 0 && (
        <p className="flex flex-wrap items-center gap-2 text-[14px] text-muted">
          Archived:
          {archived.map((t) => (
            <Button
              key={t.id}
              variant="ghost"
              size="sm"
              disabled={busy}
              aria-label={`Restore topic ${t.title}`}
              onClick={() =>
                run(
                  () =>
                    updateTopic(t.id, t.version, {
                      title: t.title,
                      displayOrder: t.displayOrder,
                      status: 'ACTIVE',
                    }),
                  `${t.title} restored.`,
                )
              }
            >
              {t.title} <ArchiveRestore aria-hidden="true" className="size-4" />
            </Button>
          ))}
        </p>
      )}
      <AddByName
        label={`New topic in ${module.title}`}
        button="Add topic"
        placeholder={`New topic in ${module.title}`}
        onAdd={(title) =>
          run(
            () =>
              createTopic({
                moduleId: module.id,
                title,
                displayOrder: module.topics.length + 1,
                prerequisiteTopicIds: [],
              }),
            `${title} added to ${module.title}.`,
          )
        }
        busy={busy}
        compact
      />
      <RenameDialog
        open={renaming !== null}
        what={renaming === 'module' ? 'module' : 'topic'}
        current={renaming === 'module' ? module.title : (renaming?.title ?? '')}
        onClose={() => setRenaming(null)}
        onSave={(title) => {
          const target = renaming;
          setRenaming(null);
          if (target === 'module')
            run(
              () =>
                updateModule(module.id, module.version, {
                  title,
                  displayOrder: module.displayOrder,
                }),
              'Module renamed.',
            );
          else if (target)
            run(
              () =>
                updateTopic(target.id, target.version, {
                  title,
                  displayOrder: target.displayOrder,
                }),
              'Topic renamed.',
            );
        }}
      />
    </div>
  );
}

/** A single-field "type a name, press Add" form. The compact version keeps its label for screen readers only. */
function AddByName(props: {
  label: string;
  button: string;
  placeholder: string;
  onAdd: (name: string) => void;
  busy: boolean;
  compact?: boolean;
}) {
  const [name, setName] = useState('');
  const id = useId();
  const trimmed = name.trim();
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (trimmed.length < 2) return;
    props.onAdd(trimmed);
    setName('');
  };
  if (props.compact) {
    return (
      <form onSubmit={submit} className="flex max-w-md flex-wrap items-center gap-2">
        <label htmlFor={id} className="sr-only">
          {props.label}
        </label>
        <input
          id={id}
          value={name}
          maxLength={160}
          placeholder={props.placeholder}
          onChange={(e) => setName(e.target.value)}
          className="min-h-10 min-w-48 flex-1 rounded-md border border-border-strong bg-surface px-3 text-[14.5px] focus:border-primary focus:shadow-[0_0_0_3px_var(--color-primary-soft)] focus:outline-none"
        />
        <Button type="submit" variant="ghost" size="sm" className="text-primary-ink" disabled={trimmed.length < 2 || props.busy}>
          <Plus aria-hidden="true" className="size-4" /> {props.button}
        </Button>
      </form>
    );
  }
  return (
    <form onSubmit={submit} className="flex flex-wrap items-end gap-2">
      <div className="min-w-56 flex-1">
        <TextField
          label={props.label}
          placeholder={props.placeholder}
          value={name}
          maxLength={160}
          onChange={(e) => setName(e.target.value)}
        />
      </div>
      <Button type="submit" variant="secondary" disabled={trimmed.length < 2 || props.busy}>
        <Plus aria-hidden="true" className="size-4" /> {props.button}
      </Button>
    </form>
  );
}

function RenameDialog(props: {
  open: boolean;
  what: string;
  current: string;
  onClose: () => void;
  onSave: (title: string) => void;
}) {
  const [draft, setDraft] = useState<{ for: string; value: string } | null>(null);
  const value = draft?.for === props.current ? draft.value : props.current;
  const valid = value.trim().length >= 2 && value.trim() !== props.current;
  return (
    <Dialog
      open={props.open}
      onClose={props.onClose}
      title={`Rename ${props.what}`}
      footer={
        <>
          <Button variant="secondary" onClick={props.onClose}>
            Cancel
          </Button>
          <Button disabled={!valid} onClick={() => props.onSave(value.trim())}>
            Save name
          </Button>
        </>
      }
    >
      <TextField
        label="Name"
        value={value}
        maxLength={160}
        onChange={(e) => setDraft({ for: props.current, value: e.target.value })}
      />
    </Dialog>
  );
}

function CopySubjectDialog(props: {
  open: boolean;
  subjects: SubjectAdmin[];
  onClose: () => void;
  onPick: (s: SubjectAdmin) => void;
}) {
  const [id, setId] = useState('');
  const chosen = props.subjects.find((s) => s.id === id);
  return (
    <Dialog
      open={props.open}
      onClose={props.onClose}
      title="Use a subject from another exam"
      description="The subject is shared, not copied: renaming it or adding topics changes it in every exam that uses it."
      footer={
        <>
          <Button variant="secondary" onClick={props.onClose}>
            Cancel
          </Button>
          <Button disabled={!chosen} onClick={() => chosen && props.onPick(chosen)}>
            Add to this exam
          </Button>
        </>
      }
    >
      <SelectField label="Subject" value={id} onChange={(e) => setId(e.target.value)}>
        <option value="">Choose a subject</option>
        {props.subjects.map((s) => {
          const topics = s.modules.flatMap((m) => m.topics.filter((t) => t.status === 'ACTIVE')).length;
          return (
            <option key={s.id} value={s.id}>
              {s.title} ({topics} topic{topics === 1 ? '' : 's'})
            </option>
          );
        })}
      </SelectField>
    </Dialog>
  );
}
