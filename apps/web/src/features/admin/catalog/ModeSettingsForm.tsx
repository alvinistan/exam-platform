import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { Alert, Badge, Button, Card, CheckboxField, SelectField, TextField } from '@/components/ui';
import { examKeys, modeLabels, type Mode } from '@/features/exams/api';
import { ApiError } from '@/lib/api/problem';
import { catalogKeys, configureMode, type ModeInput, type ModeSettings } from './api';

const count = (label: string) =>
  z
    .number({ error: `Enter the ${label} as a number.` })
    .int('Use a whole number.')
    .min(1, 'Use 1 or more.')
    .max(500, 'Use 500 or fewer.');

const schema = z
  .object({
    enabled: z.boolean(),
    minCount: count('minimum'),
    defaultCount: count('default'),
    maxCount: count('maximum'),
    timeLimitMinutes: z.number({ error: 'Enter the time limit in minutes.' }).int('Use whole minutes.').min(1).max(720).optional(),
    feedbackTiming: z.enum(['IMMEDIATE', 'AFTER_SUBMIT']),
    allowTopicChoice: z.boolean(),
  })
  .superRefine((v, ctx) => {
    if (v.minCount > v.defaultCount) {
      ctx.addIssue({ code: 'custom', path: ['minCount'], message: 'Must not be more than the default.' });
    }
    if (v.defaultCount > v.maxCount) {
      ctx.addIssue({ code: 'custom', path: ['maxCount'], message: 'Must not be less than the default.' });
    }
  });

type FormValues = z.infer<typeof schema>;

const descriptions: Record<Mode, string> = {
  PRACTICE: 'Students pick topics and a number of questions, and can see each answer as they go.',
  MOCK: 'A timed paper under exam conditions. Answers are shown only after submission.',
  USER_FRIENDLY: 'Short guided sessions sized by question count or time available.',
};

function toValues(s: ModeSettings): FormValues {
  return {
    enabled: s.enabled,
    minCount: s.minCount,
    defaultCount: s.defaultCount,
    maxCount: s.maxCount,
    ...(s.mode === 'MOCK' ? { timeLimitMinutes: Math.round((s.timeLimitSeconds ?? 3600) / 60) } : {}),
    feedbackTiming: s.feedbackTiming,
    allowTopicChoice: s.allowTopicChoice,
  };
}

interface Props {
  examId: string;
  settings: ModeSettings;
  /** Published question count (Main bank), to warn before a minimum can't be met. */
  publishedQuestions: number;
}

/**
 * One mode's settings. Only the settings the platform already acts on are shown (FR-CAT-12); the rest keep their
 * stored values and appear here when the attempt modes that use them are delivered.
 */
export function ModeSettingsForm({ examId, settings, publishedQuestions }: Props) {
  const queryClient = useQueryClient();
  const isMock = settings.mode === 'MOCK';
  const form = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: toValues(settings) });
  const { register, handleSubmit, formState, reset } = form;
  const errors = formState.errors;

  const save = useMutation({
    mutationFn: (v: FormValues) => {
      const input: ModeInput = {
        enabled: v.enabled,
        defaultCount: v.defaultCount,
        minCount: v.minCount,
        maxCount: v.maxCount,
        timeLimitSeconds: isMock ? (v.timeLimitMinutes ?? 60) * 60 : settings.timeLimitSeconds,
        perQuestionSeconds: settings.perQuestionSeconds,
        difficultyMix: settings.difficultyMix,
        shuffleQuestions: settings.shuffleQuestions,
        shuffleOptions: settings.shuffleOptions,
        feedbackTiming: isMock ? 'AFTER_SUBMIT' : v.feedbackTiming,
        resultVisibility: settings.resultVisibility,
        negativeMark: settings.negativeMark,
        passMarkPct: settings.passMarkPct,
        allowTopicChoice: v.allowTopicChoice,
        adaptiveEnabled: settings.adaptiveEnabled,
        maxRetakes: settings.maxRetakes,
      };
      return configureMode(examId, settings.mode, input);
    },
    onSuccess: (saved) => {
      reset(toValues(saved));
      void queryClient.invalidateQueries({ queryKey: catalogKeys.exams() });
      void queryClient.invalidateQueries({ queryKey: examKeys.all });
    },
  });

  const error = save.error instanceof ApiError ? save.error : save.isError ? new ApiError(0, 'UNKNOWN', 'Please try again.') : undefined;
  const headingId = `mode-${settings.mode}`;

  return (
    <Card className="flex flex-col gap-4 p-6">
      <form noValidate aria-labelledby={headingId} onSubmit={handleSubmit((v) => save.mutate(v))} className="flex flex-col gap-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h3 id={headingId} className="text-lg font-bold">
            {modeLabels[settings.mode]}
          </h3>
          <Badge tone={settings.enabled ? 'free' : 'neutral'}>{settings.enabled ? 'On' : settings.saved ? 'Off' : 'Not set up'}</Badge>
        </div>
        <p className="-mt-2 text-[14.5px] text-muted">{descriptions[settings.mode]}</p>
        <CheckboxField label={`Offer ${modeLabels[settings.mode].toLowerCase()} for this exam`} {...register('enabled')} />
        <fieldset className="flex flex-col gap-2">
          <legend className="mb-2 text-sm font-semibold">Questions per session</legend>
          <div className="grid gap-4 sm:grid-cols-3">
            <TextField label="Minimum" type="number" min={1} max={500} error={errors.minCount?.message} {...register('minCount', { valueAsNumber: true })} />
            <TextField label="Default" type="number" min={1} max={500} error={errors.defaultCount?.message} {...register('defaultCount', { valueAsNumber: true })} />
            <TextField label="Maximum" type="number" min={1} max={500} error={errors.maxCount?.message} {...register('maxCount', { valueAsNumber: true })} />
          </div>
          <p className="text-[13px] text-muted">
            Publishing needs at least the minimum in published questions. This exam has {publishedQuestions} now.
          </p>
        </fieldset>
        {isMock ? (
          <TextField
            label="Time limit (minutes)"
            type="number"
            min={1}
            max={720}
            help="Enforced by the server. Answers are shown only after the paper is submitted."
            error={errors.timeLimitMinutes?.message}
            {...register('timeLimitMinutes', { valueAsNumber: true })}
          />
        ) : (
          <SelectField label="When students see the answer" {...register('feedbackTiming')}>
            <option value="IMMEDIATE">After each question</option>
            <option value="AFTER_SUBMIT">After they submit the session</option>
          </SelectField>
        )}
        <CheckboxField
          label="Students can choose topics"
          help="Otherwise every topic of the exam is included."
          {...register('allowTopicChoice')}
        />
        {error && (
          <Alert tone="error" title={error.code === 'PUBLISH_BLOCKED' ? 'This would stop the published exam from running' : 'Settings weren’t saved'}>
            <p>{error.code === 'PUBLISH_BLOCKED' ? 'Archive the exam first, or keep settings it can meet:' : error.message}</p>
            {error.problem?.blockers && (
              <ul className="list-disc pl-5">
                {error.problem.blockers.map((b) => (
                  <li key={b}>{b}</li>
                ))}
              </ul>
            )}
            {error.problem?.errors && (
              <ul className="list-disc pl-5">
                {error.problem.errors.map((e) => (
                  <li key={e.field}>{e.message}</li>
                ))}
              </ul>
            )}
          </Alert>
        )}
        {save.isSuccess && !formState.isDirty && <Alert tone="success" title={`${modeLabels[settings.mode]} settings saved.`} />}
        <div>
          <Button type="submit" variant="secondary" loading={save.isPending} disabled={!formState.isDirty && settings.saved}>
            Save {modeLabels[settings.mode].toLowerCase()} settings
          </Button>
        </div>
      </form>
    </Card>
  );
}
