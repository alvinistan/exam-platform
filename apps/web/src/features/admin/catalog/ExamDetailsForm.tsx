import { useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router';
import { z } from 'zod';
import { Alert, Button, Card, CheckboxField, SelectField, Skeleton, TextAreaField, TextField } from '@/components/ui';
import { examKeys } from '@/features/exams/api';
import { ApiError } from '@/lib/api/problem';
import {
  catalogKeys,
  createExam,
  slugify,
  updateExam,
  useAdminCategories,
  useLibrary,
  type AdminExamDetail,
  type ExamInput,
} from './api';

const SLUG = /^[a-z0-9]+(-[a-z0-9]+)*$/;

const schema = z.object({
  title: z.string().trim().min(2, 'Give the exam a title (at least 2 characters).').max(160, 'Use 160 characters or fewer.'),
  slug: z
    .string()
    .trim()
    .min(1, 'Choose an address.')
    .max(80, 'Use 80 characters or fewer.')
    .regex(SLUG, 'Use lower-case letters and numbers, with single hyphens between words.'),
  categoryId: z.string().min(1, 'Choose a category.'),
  shortDescription: z.string().max(300, 'Use 300 characters or fewer.'),
  instructions: z.string().max(5000, 'Use 5,000 characters or fewer.'),
  seoTitle: z.string().max(70, 'Use 70 characters or fewer.'),
  seoDescription: z.string().max(160, 'Use 160 characters or fewer.'),
  displayOrder: z
    .string()
    .trim()
    .regex(/^$|^[1-9]\d{0,5}$/, 'Use a whole number from 1, or leave empty.'),
  featured: z.boolean(),
  subjectIds: z.array(z.string()),
});

type FormValues = z.infer<typeof schema>;

function toValues(exam: AdminExamDetail | undefined): FormValues {
  return {
    title: exam?.title ?? '',
    slug: exam?.slug ?? '',
    categoryId: exam?.categoryId ?? '',
    shortDescription: exam?.shortDescription ?? '',
    instructions: exam?.instructions ?? '',
    seoTitle: exam?.seoTitle ?? '',
    seoDescription: exam?.seoDescription ?? '',
    displayOrder: exam?.displayOrder ? String(exam.displayOrder) : '',
    featured: exam?.featured ?? false,
    subjectIds: exam?.subjectIds ?? [],
  };
}

function toInput(v: FormValues): ExamInput {
  return {
    ...v,
    title: v.title.trim(),
    slug: v.slug.trim(),
    displayOrder: v.displayOrder.trim() ? Number(v.displayOrder) : null,
  };
}

interface Props {
  exam: AdminExamDetail | undefined;
  onCreated: (exam: AdminExamDetail) => void;
  onSaved: () => void;
}

/**
 * Exam details and the subjects it draws on. Every field here has a server implementation (FR-CAT-12).
 * The form waits for the category list: a native select can only show a value whose option already exists.
 */
export function ExamDetailsForm(props: Props) {
  const categories = useAdminCategories();
  if (categories.isPending) {
    return (
      <div aria-busy="true" className="flex flex-col gap-3">
        <p role="status" className="sr-only">
          Loading form…
        </p>
        <Skeleton className="h-96 w-full rounded-lg" />
      </div>
    );
  }
  return <DetailsForm {...props} />;
}

function DetailsForm({ exam, onCreated, onSaved }: Props) {
  const isNew = exam === undefined;
  const queryClient = useQueryClient();
  const categories = useAdminCategories();
  const library = useLibrary();
  const form = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: toValues(exam) });
  const { register, handleSubmit, formState, setValue, setError, reset, control } = form;
  const errors = formState.errors;
  const selected = useWatch({ control, name: 'subjectIds' });
  const slug = useWatch({ control, name: 'slug' });

  const save = useMutation({
    mutationFn: (values: FormValues) => {
      if (exam === undefined) return createExam(toInput(values));
      // The latest version we know of: mode changes elsewhere on this page bump it too.
      const latest = queryClient.getQueryData<AdminExamDetail>(catalogKeys.exam(exam.id))?.version ?? exam.version;
      return updateExam(exam.id, latest, toInput(values));
    },
    onSuccess: (saved) => {
      queryClient.setQueryData(catalogKeys.exam(saved.id), saved);
      void queryClient.invalidateQueries({ queryKey: catalogKeys.exams() });
      void queryClient.invalidateQueries({ queryKey: catalogKeys.library() });
      void queryClient.invalidateQueries({ queryKey: examKeys.all });
      reset(toValues(saved));
      if (isNew) onCreated(saved);
      else onSaved();
    },
    onError: (error) => {
      if (!(error instanceof ApiError)) return;
      if (error.code === 'SLUG_TAKEN') setError('slug', { message: error.message });
      for (const fieldError of error.problem?.errors ?? []) {
        if (fieldError.field in schema.shape) {
          setError(fieldError.field as keyof FormValues, { message: fieldError.message });
        }
      }
    },
  });

  const activeCategories = categories.data?.filter((c) => c.status === 'ACTIVE' || c.id === exam?.categoryId) ?? [];
  const subjects = library.data?.filter((s) => s.status === 'ACTIVE' || selected.includes(s.id)) ?? [];
  const slugField = register('slug');
  const unhandled =
    save.error instanceof ApiError && save.error.code !== 'SLUG_TAKEN' && !save.error.problem?.errors?.length
      ? save.error
      : save.isError && !(save.error instanceof ApiError)
        ? new ApiError(0, 'UNKNOWN', 'Please try again.')
        : undefined;

  return (
    <form noValidate onSubmit={handleSubmit((v) => save.mutate(v))} className="flex flex-col gap-6">
      <Card className="flex flex-col gap-5 p-6">
        <h2 className="text-lg font-bold">Details</h2>
        <TextField
          label="Title"
          error={errors.title?.message}
          maxLength={160}
          {...register('title', {
            onChange: (e: { target: { value: string } }) => {
              if (isNew && !formState.dirtyFields.slug) setValue('slug', slugify(e.target.value));
            },
          })}
        />
        <TextField
          label="Address"
          help={`Students open the exam at /exams/${slug || 'your-address'}. If you change it later, the old address keeps working.`}
          error={errors.slug?.message}
          maxLength={80}
          autoCapitalize="none"
          spellCheck={false}
          {...slugField}
        />
        <div className="grid gap-5 sm:grid-cols-2">
          <SelectField label="Category" {...register('categoryId')} aria-invalid={errors.categoryId ? true : undefined}>
            <option value="">Choose a category</option>
            {activeCategories.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
                {c.status === 'ARCHIVED' ? ' (archived)' : ''}
              </option>
            ))}
          </SelectField>
          <TextField
            label="Catalogue position"
            help="Order in the catalogue; also used by the free-access rule. Leave empty to add it at the end when published."
            inputMode="numeric"
            error={errors.displayOrder?.message}
            {...register('displayOrder')}
          />
        </div>
        {errors.categoryId && <p className="-mt-3 text-[13px] text-danger-ink">{errors.categoryId.message}</p>}
        {categories.data?.length === 0 && (
          <Alert tone="warning" title="Create a category first">
            <p>
              Every exam belongs to a category. <Link to="/admin/categories">Add a category</Link>, then come back.
            </p>
          </Alert>
        )}
        <TextAreaField
          label="Short description"
          help="Shown on catalogue cards. Up to 300 characters."
          rows={2}
          maxLength={300}
          error={errors.shortDescription?.message}
          {...register('shortDescription')}
        />
        <TextAreaField
          label="Instructions"
          help="Shown on the exam page under “Before you start”."
          rows={5}
          maxLength={5000}
          error={errors.instructions?.message}
          {...register('instructions')}
        />
        <CheckboxField label="Feature on the home page" help="Featured exams appear on the landing page." {...register('featured')} />
      </Card>

      <Card className="flex flex-col gap-4 p-6">
        <div className="flex flex-wrap items-end justify-between gap-2">
          <div>
            <h2 className="text-lg font-bold">Subjects</h2>
            <p className="text-[14.5px] text-muted">
              Questions are drawn from the active topics of the subjects you link. A subject can serve several exams.
            </p>
          </div>
          <Link to="/admin/content" className="text-[14.5px] font-semibold">
            Manage content library
          </Link>
        </div>
        {library.isPending ? (
          <Skeleton className="h-24 w-full" />
        ) : subjects.length === 0 ? (
          <p className="text-muted">
            No subjects yet. <Link to="/admin/content">Create one in the content library</Link>.
          </p>
        ) : (
          <fieldset className="flex flex-col gap-3">
            <legend className="sr-only">Linked subjects</legend>
            {subjects.map((s) => {
              const topics = s.modules.filter((m) => m.status === 'ACTIVE').flatMap((m) => m.topics.filter((t) => t.status === 'ACTIVE'));
              const questions = topics.reduce((n, t) => n + t.publishedQuestionCount, 0);
              return (
                <CheckboxField
                  key={s.id}
                  label={s.status === 'ARCHIVED' ? `${s.title} (archived — untick to save)` : s.title}
                  help={`${topics.length} active topic${topics.length === 1 ? '' : 's'} · ${questions} published question${questions === 1 ? '' : 's'}`}
                  checked={selected.includes(s.id)}
                  onChange={(e) =>
                    setValue(
                      'subjectIds',
                      e.target.checked ? [...selected, s.id] : selected.filter((id) => id !== s.id),
                      { shouldDirty: true },
                    )
                  }
                />
              );
            })}
          </fieldset>
        )}
      </Card>

      <Card className="flex flex-col gap-5 p-6">
        <div>
          <h2 className="text-lg font-bold">Search engines</h2>
          <p className="text-[14.5px] text-muted">Optional. The title and short description are used when these are empty.</p>
        </div>
        <TextField label="Page title" maxLength={70} error={errors.seoTitle?.message} {...register('seoTitle')} />
        <TextAreaField
          label="Page description"
          rows={2}
          maxLength={160}
          error={errors.seoDescription?.message}
          {...register('seoDescription')}
        />
      </Card>

      {unhandled && (
        <Alert tone="error" title="The exam wasn’t saved">
          <p>{unhandled.message}</p>
          {unhandled.problem?.blockers && (
            <ul className="list-disc pl-5">
              {unhandled.problem.blockers.map((b) => (
                <li key={b}>{b}</li>
              ))}
            </ul>
          )}
        </Alert>
      )}
      <div className="flex flex-wrap gap-3">
        <Button type="submit" loading={save.isPending}>
          {isNew ? 'Create draft' : 'Save details'}
        </Button>
        {!isNew && formState.isDirty && (
          <Button variant="ghost" onClick={() => reset(toValues(exam))}>
            Discard changes
          </Button>
        )}
      </div>
    </form>
  );
}
