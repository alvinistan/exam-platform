import { useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { Alert, Button, Dialog, SelectField, TextField } from '@/components/ui';
import { billingKeys, monthsLabel } from '@/features/billing/api';
import { accessKeys } from '@/features/access/api';
import { ApiError } from '@/lib/api/problem';
import {
  addCampaignStudents,
  billingAdminKeys,
  createCampaign,
  fromLocalInput,
  toLocalInput,
  type Campaign,
  type CampaignInput,
} from './api';
import { LimitFields, ScopeFields } from './ScopeFields';
import { UNLIMITED_ALL, checkScopeAndLimits, fromLimit, limitsSchema } from './scope';
import { StudentPicker, type PickedStudent } from './StudentPicker';

const schema = z
  .object({
    name: z.string().trim().min(2, 'Use at least 2 characters.').max(80, 'Use 80 characters or fewer.'),
    scopeType: z.enum(['EXAM', 'BUNDLE', 'ALL']),
    categoryId: z.string(),
    examIds: z.array(z.string()),
    limits: limitsSchema,
    audience: z.enum(['ALL', 'SELECTED']),
    startsAt: z.string(),
    endsAt: z.string(),
    durationMonths: z.enum(['1', '3', '6', '12']),
    students: z.array(z.object({ id: z.string(), name: z.string(), email: z.string() })),
  })
  .superRefine((v, ctx) => {
    checkScopeAndLimits(v, ctx);
    if (v.audience === 'ALL') {
      const starts = fromLocalInput(v.startsAt) ?? new Date().toISOString();
      const ends = fromLocalInput(v.endsAt);
      if (!ends) ctx.addIssue({ code: 'custom', path: ['endsAt'], message: 'Choose when it ends.' });
      else if (Date.parse(ends) <= Math.max(Date.parse(starts), Date.now())) {
        ctx.addIssue({ code: 'custom', path: ['endsAt'], message: 'Must be in the future, after the start.' });
      }
    } else if (v.students.length === 0) {
      ctx.addIssue({ code: 'custom', path: ['students'], message: 'Add at least one student.' });
    }
  });
type FormValues = z.infer<typeof schema>;

const errorText = (error: unknown) =>
  error instanceof ApiError ? (error.problem?.errors?.[0]?.message ?? error.message) : 'Please try again.';

/**
 * Starts a free access campaign: an exam, a bundle or everything, with the attempts chosen, for every student between
 * two dates or for chosen students for 1, 3, 6 or 12 months. It works like a plan nobody pays for.
 */
export function CampaignDialog({ onClose }: { onClose: () => void }) {
  const queryClient = useQueryClient();
  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      name: '',
      scopeType: 'ALL',
      categoryId: '',
      examIds: [],
      limits: UNLIMITED_ALL,
      audience: 'ALL',
      startsAt: toLocalInput(new Date().toISOString()),
      endsAt: '',
      durationMonths: '1',
      students: [],
    },
  });
  const save = useMutation({
    mutationFn: (v: FormValues) => {
      const input: CampaignInput = {
        name: v.name.trim(),
        scopeType: v.scopeType,
        categoryId: v.scopeType === 'ALL' || v.categoryId === '' ? null : v.categoryId,
        examIds: v.scopeType === 'ALL' ? [] : v.examIds,
        practiceLimit: fromLimit(v.limits.practice),
        mockLimit: fromLimit(v.limits.mock),
        adaptiveLimit: fromLimit(v.limits.adaptive),
        audience: v.audience,
        startsAt: v.audience === 'ALL' ? fromLocalInput(v.startsAt) : null,
        endsAt: v.audience === 'ALL' ? fromLocalInput(v.endsAt) : null,
        durationMonths: v.audience === 'SELECTED' ? Number(v.durationMonths) : null,
        studentIds: v.audience === 'SELECTED' ? v.students.map((s) => s.id) : [],
      };
      return createCampaign(input);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: billingAdminKeys.all });
      void queryClient.invalidateQueries({ queryKey: billingKeys.all });
      void queryClient.invalidateQueries({ queryKey: accessKeys.all });
      onClose();
    },
  });
  const e = form.formState.errors;
  const [scopeType, categoryId, examIds, limits, audience, students] = useWatch({
    control: form.control,
    name: ['scopeType', 'categoryId', 'examIds', 'limits', 'audience', 'students'],
  });
  const revalidate = { shouldDirty: true, shouldValidate: form.formState.isSubmitted };

  return (
    <Dialog
      open
      onClose={onClose}
      title="New free access campaign"
      description="Students get the access without paying. Ending the campaign stops it."
      className="w-[min(720px,calc(100vw-32px))]"
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" form="campaign-form" disabled={save.isPending}>
            {save.isPending ? 'Starting…' : 'Start campaign'}
          </Button>
        </>
      }
    >
      <form id="campaign-form" noValidate onSubmit={form.handleSubmit((v) => save.mutate(v))} className="flex flex-col gap-5">
        <TextField
          label="Name"
          placeholder="Exam week: free Science practice"
          help="Students see this name."
          error={e.name?.message}
          {...form.register('name')}
        />
        <ScopeFields
          scopeType={scopeType}
          categoryId={categoryId}
          examIds={examIds}
          error={e.examIds?.message}
          onScopeType={(v) => form.setValue('scopeType', v, revalidate)}
          onCategory={(v) => form.setValue('categoryId', v, { shouldDirty: true })}
          onExams={(v) => form.setValue('examIds', v, revalidate)}
        />
        <LimitFields
          legend={`Attempts ${scopeType === 'EXAM' ? '' : 'per exam '}while the campaign lasts`}
          values={limits}
          errors={{
            kind: e.limits?.practice?.kind?.message,
            counts: {
              practice: e.limits?.practice?.count?.message,
              mock: e.limits?.mock?.count?.message,
              adaptive: e.limits?.adaptive?.count?.message,
            },
          }}
          onChange={(key, value) => form.setValue(`limits.${key}`, value, revalidate)}
        />

        <fieldset className="flex flex-col gap-3">
          <legend className="mb-1 text-sm font-semibold">Who gets it</legend>
          <div className="flex flex-wrap gap-x-5 gap-y-2" role="radiogroup" aria-label="Who gets it">
            {(
              [
                ['ALL', 'Every student, between two dates'],
                ['SELECTED', 'Students I choose'],
              ] as const
            ).map(([value, label]) => (
              <label key={value} className="flex items-center gap-2 text-[15px]">
                <input
                  type="radio"
                  value={value}
                  className="size-4 accent-[var(--color-primary)]"
                  {...form.register('audience')}
                />
                {label}
              </label>
            ))}
          </div>
          {audience === 'ALL' ? (
            <div className="grid gap-4 sm:grid-cols-2">
              <TextField label="Starts" type="datetime-local" error={e.startsAt?.message} {...form.register('startsAt')} />
              <TextField
                label="Ends"
                type="datetime-local"
                help="At most 2 years. Students who haven’t signed up yet get it too."
                error={e.endsAt?.message}
                {...form.register('endsAt')}
              />
            </div>
          ) : (
            <>
              <SelectField label="How long each student has it" {...form.register('durationMonths')}>
                {(['1', '3', '6', '12'] as const).map((m) => (
                  <option key={m} value={m}>
                    {monthsLabel(Number(m))} from today
                  </option>
                ))}
              </SelectField>
              <StudentPicker
                value={students}
                onChange={(v) => form.setValue('students', v, revalidate)}
                error={e.students?.message}
              />
            </>
          )}
        </fieldset>
        {save.isError && (
          <Alert tone="error" title="Couldn’t start the campaign">
            {errorText(save.error)}
          </Alert>
        )}
      </form>
    </Dialog>
  );
}

/** Adds students to a campaign for chosen students; each gets the campaign's length from today. */
export function AddStudentsDialog({ campaign, onClose }: { campaign: Campaign; onClose: () => void }) {
  const queryClient = useQueryClient();
  const [students, setStudents] = useState<PickedStudent[]>([]);
  const save = useMutation({
    mutationFn: () =>
      addCampaignStudents(
        campaign,
        students.map((s) => s.id),
      ),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: billingAdminKeys.all });
      onClose();
    },
  });
  return (
    <Dialog
      open
      onClose={onClose}
      title={`Add students to ${campaign.name}`}
      description={`Each student gets ${monthsLabel(campaign.durationMonths ?? 1)} from today. Students who already have it are skipped.`}
      className="w-[min(560px,calc(100vw-32px))]"
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button disabled={students.length === 0 || save.isPending} onClick={() => save.mutate()}>
            {save.isPending
              ? 'Adding…'
              : students.length === 0
                ? 'Add students'
                : `Add ${students.length} student${students.length === 1 ? '' : 's'}`}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <StudentPicker value={students} onChange={setStudents} />
        {save.isError && (
          <Alert tone="error" title="Couldn’t add the students">
            {errorText(save.error)}
          </Alert>
        )}
      </div>
    </Dialog>
  );
}
