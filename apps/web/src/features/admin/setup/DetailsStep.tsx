import { useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router';
import { Plus } from 'lucide-react';
import { z } from 'zod';
import { Alert, Button, Card, CheckboxField, Dialog, SelectField, Skeleton, TextAreaField, TextField } from '@/components/ui';
import { examKeys } from '@/features/exams/api';
import { ApiError } from '@/lib/api/problem';
import {
  catalogKeys,
  createCategory,
  createExam,
  slugify,
  updateExam,
  useAdminCategories,
  type AdminCategory,
  type AdminExamDetail,
  type ExamInput,
} from '@/features/admin/catalog/api';
import { StepFooter, StepHeading } from './SetupShell';

const SLUG = /^[a-z0-9]+(-[a-z0-9]+)*$/;

const schema = z.object({
  title: z.string().trim().min(2, 'Give the exam a title (at least 2 characters).').max(160, 'Use 160 characters or fewer.'),
  slug: z
    .string()
    .trim()
    .min(1, 'Choose an address.')
    .max(80, 'Use 80 characters or fewer.')
    .regex(SLUG, 'Use lower-case letters and numbers, with single hyphens between words.'),
  categoryId: z.string().min(1, 'Choose a category, or add one.'),
  shortDescription: z.string().max(300, 'Use 300 characters or fewer.'),
  instructions: z.string().max(5000, 'Use 5,000 characters or fewer.'),
  seoTitle: z.string().max(70, 'Use 70 characters or fewer.'),
  seoDescription: z.string().max(160, 'Use 160 characters or fewer.'),
  displayOrder: z
    .string()
    .trim()
    .regex(/^$|^[1-9]\d{0,5}$/, 'Use a whole number from 1, or leave empty.'),
  featured: z.boolean(),
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
  };
}

interface Props {
  exam: AdminExamDetail | undefined;
  /** Called after a successful save (or "continue" with nothing changed). */
  onSaved: (exam: AdminExamDetail) => void;
}

/**
 * Step 1: the exam's name, address, category and descriptions. Every field has a server implementation (FR-CAT-12).
 * The form waits for the category list: a native select can only show a value whose option already exists.
 */
export function DetailsStep(props: Props) {
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

function DetailsForm({ exam, onSaved }: Props) {
  const isNew = exam === undefined;
  const queryClient = useQueryClient();
  const categories = useAdminCategories();
  const [addingCategory, setAddingCategory] = useState(false);
  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: toValues(exam),
  });
  const { register, handleSubmit, formState, setValue, setError, reset, control } = form;
  const errors = formState.errors;
  const slug = useWatch({ control, name: 'slug' });

  const save = useMutation({
    mutationFn: (values: FormValues) => {
      const input: ExamInput = {
        ...values,
        title: values.title.trim(),
        slug: values.slug.trim(),
        displayOrder: values.displayOrder.trim() ? Number(values.displayOrder) : null,
        // Subjects are managed in step 2; keep the current links.
        subjectIds: exam?.subjectIds ?? [],
      };
      if (exam === undefined) return createExam(input);
      const latest = queryClient.getQueryData<AdminExamDetail>(catalogKeys.exam(exam.id))?.version ?? exam.version;
      return updateExam(exam.id, latest, input);
    },
    onSuccess: (saved) => {
      queryClient.setQueryData(catalogKeys.exam(saved.id), saved);
      void queryClient.invalidateQueries({ queryKey: catalogKeys.exams() });
      void queryClient.invalidateQueries({ queryKey: examKeys.all });
      reset(toValues(saved));
      onSaved(saved);
    },
    onError: (error) => {
      if (!(error instanceof ApiError)) return;
      if (error.code === 'SLUG_TAKEN') setError('slug', { message: error.message });
      for (const fieldError of error.problem?.errors ?? []) {
        if (fieldError.field in schema.shape) {
          setError(fieldError.field as keyof FormValues, {
            message: fieldError.message,
          });
        }
      }
    },
  });

  const submit = handleSubmit((values) => {
    if (exam && !formState.isDirty) onSaved(exam);
    else save.mutate(values);
  });

  const activeCategories = categories.data?.filter((c) => c.status === 'ACTIVE' || c.id === exam?.categoryId) ?? [];
  const unhandled =
    save.error instanceof ApiError && save.error.code !== 'SLUG_TAKEN' && !save.error.problem?.errors?.length
      ? save.error
      : save.isError && !(save.error instanceof ApiError)
        ? new ApiError(0, 'UNKNOWN', 'Please try again.')
        : undefined;

  return (
    <Card className="flex flex-col gap-6 p-6 sm:p-8">
      <StepHeading number={1} title="Exam details">
        The basics students see on the exam page.
      </StepHeading>
      <form noValidate onSubmit={(e) => void submit(e)} className="flex flex-col gap-5">
        <TextField
          label="Exam title"
          error={errors.title?.message}
          maxLength={160}
          {...register('title', {
            onChange: (e: { target: { value: string } }) => {
              if (isNew && !formState.dirtyFields.slug) setValue('slug', slugify(e.target.value));
            },
          })}
        />
        <div className="grid gap-5 sm:grid-cols-2">
          <TextField
            label="Web address"
            help={`Students open it at /exams/${slug || 'your-address'}. Made from the title; if you change it later, old links keep working.`}
            error={errors.slug?.message}
            maxLength={80}
            autoCapitalize="none"
            spellCheck={false}
            {...register('slug')}
          />
          <div className="flex flex-col gap-1.5">
            <SelectField label="Category" {...register('categoryId')} aria-invalid={errors.categoryId ? true : undefined}>
              <option value="">Choose a category</option>
              {activeCategories.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                  {c.status === 'ARCHIVED' ? ' (archived)' : ''}
                </option>
              ))}
            </SelectField>
            {errors.categoryId && <p className="text-[13px] text-danger-ink">{errors.categoryId.message}</p>}
            <div>
              <Button variant="ghost" size="sm" className="-ml-3 text-primary-ink" onClick={() => setAddingCategory(true)}>
                <Plus aria-hidden="true" className="size-4" /> Add a new category
              </Button>
            </div>
          </div>
        </div>
        <TextAreaField
          label="Short description"
          help="Shown on the exam’s card in the catalogue. Up to 300 characters."
          rows={2}
          maxLength={300}
          error={errors.shortDescription?.message}
          {...register('shortDescription')}
        />
        <TextAreaField
          label="Instructions for students (optional)"
          help="Shown on the exam page under “Before you start”."
          rows={4}
          maxLength={5000}
          error={errors.instructions?.message}
          {...register('instructions')}
        />

        <details className="group rounded-md border border-border px-4 py-3">
          <summary className="cursor-pointer font-semibold">More options</summary>
          <div className="mt-4 flex flex-col gap-5">
            <CheckboxField
              label="Feature on the home page"
              help="Featured exams appear on the landing page."
              {...register('featured')}
            />
            <TextField
              label="Catalogue position"
              help="Order in the catalogue; the free-access rule also uses it. Leave empty to add it at the end when published."
              inputMode="numeric"
              error={errors.displayOrder?.message}
              {...register('displayOrder')}
            />
            <TextField label="Search engine title" maxLength={70} error={errors.seoTitle?.message} {...register('seoTitle')} />
            <TextAreaField
              label="Search engine description"
              help="Optional. The title and short description are used when these are empty."
              rows={2}
              maxLength={160}
              error={errors.seoDescription?.message}
              {...register('seoDescription')}
            />
          </div>
        </details>

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
        <StepFooter>
          <Link to="/admin/exams" className="inline-flex min-h-11 items-center font-semibold">
            {isNew ? 'Cancel' : 'Back to exams'}
          </Link>
          <Button type="submit" loading={save.isPending}>
            {isNew ? 'Create draft and continue' : formState.isDirty ? 'Save and continue' : 'Continue'}
          </Button>
        </StepFooter>
      </form>

      <NewCategoryDialog
        open={addingCategory}
        onClose={() => setAddingCategory(false)}
        onCreated={(category) => {
          queryClient.setQueryData<AdminCategory[]>(catalogKeys.categories(), (list) => [...(list ?? []), category]);
          void queryClient.invalidateQueries({
            queryKey: catalogKeys.categories(),
          });
          // The option exists once the list above re-renders; select it then.
          setTimeout(
            () =>
              setValue('categoryId', category.id, {
                shouldDirty: true,
                shouldValidate: true,
              }),
            0,
          );
          setAddingCategory(false);
        }}
      />
    </Card>
  );
}

function NewCategoryDialog({
  open,
  onClose,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  onCreated: (c: AdminCategory) => void;
}) {
  const [name, setName] = useState('');
  const create = useMutation({
    mutationFn: () =>
      createCategory({
        name: name.trim(),
        slug: slugify(name),
        description: '',
        displayOrder: 0,
      }),
    onSuccess: (c) => {
      setName('');
      onCreated(c);
    },
  });
  const tooShort = name.trim().length < 2 || slugify(name) === '';
  const error = create.error instanceof ApiError ? create.error.message : create.isError ? 'Please try again.' : undefined;
  return (
    <Dialog
      open={open}
      onClose={onClose}
      title="New category"
      description="Categories group exams in the catalogue, for example “G.C.E. Ordinary Level”."
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button loading={create.isPending} disabled={tooShort} onClick={() => create.mutate()}>
            Add category
          </Button>
        </>
      }
    >
      <TextField label="Category name" value={name} maxLength={80} onChange={(e) => setName(e.target.value)} error={error} />
    </Dialog>
  );
}
