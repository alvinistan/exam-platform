import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { FolderPlus } from 'lucide-react';
import { z } from 'zod';
import { Alert, Badge, Button, Dialog, EmptyState, ErrorState, Skeleton, TextAreaField, TextField } from '@/components/ui';
import { examKeys } from '@/features/exams/api';
import { ApiError } from '@/lib/api/problem';
import {
  catalogKeys,
  createCategory,
  setCategoryArchived,
  slugify,
  updateCategory,
  useAdminCategories,
  type AdminCategory,
} from './api';

const schema = z.object({
  name: z.string().trim().min(2, 'Use at least 2 characters.').max(80, 'Use 80 characters or fewer.'),
  slug: z
    .string()
    .trim()
    .min(1, 'Choose an address.')
    .max(80, 'Use 80 characters or fewer.')
    .regex(/^[a-z0-9]+(-[a-z0-9]+)*$/, 'Use lower-case letters and numbers, with single hyphens between words.'),
  description: z.string().max(500, 'Use 500 characters or fewer.'),
  displayOrder: z.number({ error: 'Enter a number.' }).int('Use a whole number.').min(0).max(100000),
});
type FormValues = z.infer<typeof schema>;

/** Exam categories (FR-CAT-01). A category with published exams can't be archived. */
export function CategoriesPage() {
  const queryClient = useQueryClient();
  const categories = useAdminCategories();
  const [editing, setEditing] = useState<AdminCategory | 'new' | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: catalogKeys.categories() });
    void queryClient.invalidateQueries({ queryKey: examKeys.categories });
  };

  const toggle = useMutation({
    mutationFn: (c: AdminCategory) => setCategoryArchived(c.id, c.status === 'ACTIVE'),
    onSuccess: (c) => {
      setNotice(c.status === 'ARCHIVED' ? `${c.name} is archived.` : `${c.name} is active again.`);
      refresh();
    },
    onError: () => setNotice(null),
  });

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-extrabold tracking-tight">Categories</h1>
          <p className="text-muted">Group exams so students can browse them, for example “Nursing” or “IT certifications”.</p>
        </div>
        <Button onClick={() => setEditing('new')}>
          <FolderPlus aria-hidden="true" className="size-4" /> New category
        </Button>
      </div>
      {notice && <Alert tone="success" title={notice} />}
      {toggle.error instanceof ApiError && (
        <Alert tone="error" title="That didn’t work">
          <p>{toggle.error.message}</p>
        </Alert>
      )}

      {categories.isPending ? (
        <div className="flex flex-col gap-2" aria-busy="true">
          <p role="status" className="sr-only">
            Loading categories…
          </p>
          {Array.from({ length: 3 }, (_, i) => (
            <Skeleton key={i} className="h-14 w-full" />
          ))}
        </div>
      ) : categories.isError ? (
        <ErrorState onRetry={() => void categories.refetch()} title="We couldn’t load categories" />
      ) : categories.data.length === 0 ? (
        <EmptyState icon={FolderPlus} title="No categories yet" action={<Button onClick={() => setEditing('new')}>Create a category</Button>}>
          <p>Every exam belongs to a category.</p>
        </EmptyState>
      ) : (
        <div className="relative overflow-x-auto rounded-lg border border-border bg-surface">
          <table className="w-full border-collapse text-[14.5px]">
            <caption className="sr-only">Categories</caption>
            <thead>
              <tr className="border-b border-border bg-[#fafbfc] text-left text-[12.5px] font-bold tracking-wider text-muted uppercase">
                <th scope="col" className="px-4 py-3">Category</th>
                <th scope="col" className="px-4 py-3 text-right">Order</th>
                <th scope="col" className="px-4 py-3">Exams</th>
                <th scope="col" className="px-4 py-3">Status</th>
                <th scope="col" className="px-4 py-3"><span className="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {categories.data.map((c) => (
                <tr key={c.id} className="border-b border-border last:border-b-0">
                  <td className="px-4 py-3">
                    <p className="font-semibold">{c.name}</p>
                    <p className="text-[13px] text-muted">/exams?category={c.slug}</p>
                  </td>
                  <td className="px-4 py-3 text-right tabular-nums">{c.displayOrder}</td>
                  <td className="px-4 py-3 text-ink-2">
                    {c.publishedExamCount} published{c.examCount > c.publishedExamCount && `, ${c.examCount - c.publishedExamCount} draft`}
                  </td>
                  <td className="px-4 py-3">
                    <Badge tone={c.status === 'ACTIVE' ? 'free' : 'neutral'}>{c.status === 'ACTIVE' ? 'Active' : 'Archived'}</Badge>
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex justify-end gap-2">
                      <Button size="sm" variant="secondary" aria-label={`Edit ${c.name}`} onClick={() => setEditing(c)}>
                        Edit
                      </Button>
                      <Button
                        size="sm"
                        variant="ghost"
                        aria-label={`${c.status === 'ACTIVE' ? 'Archive' : 'Restore'} ${c.name}`}
                        loading={toggle.isPending && toggle.variables?.id === c.id}
                        onClick={() => {
                          setNotice(null);
                          toggle.mutate(c);
                        }}
                      >
                        {c.status === 'ACTIVE' ? 'Archive' : 'Restore'}
                      </Button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {editing !== null && (
        <CategoryDialog
          category={editing === 'new' ? undefined : editing}
          onClose={() => setEditing(null)}
          onSaved={(c) => {
            setEditing(null);
            setNotice(editing === 'new' ? `${c.name} created.` : `${c.name} saved.`);
            refresh();
          }}
        />
      )}
    </div>
  );
}

function CategoryDialog({
  category,
  onClose,
  onSaved,
}: {
  category: AdminCategory | undefined;
  onClose: () => void;
  onSaved: (c: AdminCategory) => void;
}) {
  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      name: category?.name ?? '',
      slug: category?.slug ?? '',
      description: category?.description ?? '',
      displayOrder: category?.displayOrder ?? 0,
    },
  });
  const { register, handleSubmit, formState, setValue, setError } = form;
  const save = useMutation({
    mutationFn: (v: FormValues) =>
      category ? updateCategory(category.id, category.version, v) : createCategory(v),
    onSuccess: onSaved,
    onError: (error) => {
      if (error instanceof ApiError && error.code === 'SLUG_TAKEN') setError('slug', { message: error.message });
    },
  });
  const error = save.error instanceof ApiError && save.error.code !== 'SLUG_TAKEN' ? save.error : undefined;

  return (
    <Dialog open onClose={onClose} title={category ? `Edit ${category.name}` : 'New category'}>
      <form noValidate className="flex flex-col gap-4" onSubmit={handleSubmit((v) => save.mutate(v))}>
        <TextField
          label="Name"
          maxLength={80}
          error={formState.errors.name?.message}
          {...register('name', {
            onChange: (e: { target: { value: string } }) => {
              if (!category && !formState.dirtyFields.slug) setValue('slug', slugify(e.target.value));
            },
          })}
        />
        <TextField label="Address" help="Used in catalogue links." maxLength={80} error={formState.errors.slug?.message} {...register('slug')} />
        <TextAreaField label="Description" rows={2} maxLength={500} error={formState.errors.description?.message} {...register('description')} />
        <TextField
          label="Order"
          type="number"
          min={0}
          help="Lower numbers come first."
          error={formState.errors.displayOrder?.message}
          {...register('displayOrder', { valueAsNumber: true })}
        />
        {error && (
          <Alert tone="error" title="The category wasn’t saved">
            <p>{error.message}</p>
          </Alert>
        )}
        <div className="flex flex-wrap justify-end gap-3">
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" loading={save.isPending}>
            {category ? 'Save' : 'Create'}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
