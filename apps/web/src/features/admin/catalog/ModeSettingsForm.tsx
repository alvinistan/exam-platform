import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { Alert, Badge, Button, Card, CheckboxField, SelectField, TextField } from '@/components/ui';
import { examKeys, modeLabels, type Mode } from '@/features/exams/api';
import { ApiError } from '@/lib/api/problem';
import { catalogKeys, configureMode, type ModeInput, type ModeSettings } from './api';

const schema = z.object({
  enabled: z.boolean(),
  maxCount: z
    .number({ error: 'Enter the maximum as a number.' })
    .int('Use a whole number.')
    .min(1, 'Use 1 or more.')
    .max(500, 'Use 500 or fewer.'),
  freeAttempts: z
    .number({ error: 'Enter a number (0 for Premium only).' })
    .int('Use a whole number.')
    .min(0, 'Use 0 or more.')
    .max(1000, 'Use 1000 or fewer.'),
  timeLimitMinutes: z.number({ error: 'Enter the time limit in minutes.' }).int('Use whole minutes.').min(1).max(720).optional(),
  feedbackTiming: z.enum(['IMMEDIATE', 'AFTER_SUBMIT']),
  allowTopicChoice: z.boolean(),
});

type FormValues = z.infer<typeof schema>;

const descriptions: Record<Mode, string> = {
  PRACTICE: 'Students pick topics and a number of questions or minutes, answer one question at a time and see each answer straight away.',
  MOCK: 'A timed paper under exam conditions, with a countdown kept by the server.',
  USER_FRIENDLY: 'One question at a time with instant answers. Difficulty that adapts to each student’s results comes in the next update.',
};

function toValues(s: ModeSettings): FormValues {
  return {
    enabled: s.enabled,
    maxCount: s.maxCount,
    freeAttempts: s.freeAttempts,
    ...(s.mode === 'MOCK' ? { timeLimitMinutes: Math.round((s.timeLimitSeconds ?? 3600) / 60) } : {}),
    feedbackTiming: s.mode === 'MOCK' ? s.feedbackTiming : 'IMMEDIATE',
    allowTopicChoice: s.allowTopicChoice,
  };
}

interface Props {
  examId: string;
  settings: ModeSettings;
  /** Ready (published) questions in the exam, to say how full a session can be. */
  publishedQuestions: number;
}

/**
 * One exam type's settings: on or off, the most questions in one session, free attempts per student and, for the
 * Mock exam, the longest time allowed and when answers are shown. Only settings the platform acts on are shown
 * (FR-CAT-12); the rest keep their stored values.
 */
export function ModeSettingsForm({ examId, settings, publishedQuestions }: Props) {
  const queryClient = useQueryClient();
  const isMock = settings.mode === 'MOCK';
  const form = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: toValues(settings) });
  const { register, handleSubmit, formState, reset } = form;
  const errors = formState.errors;
  const label = modeLabels[settings.mode];

  const save = useMutation({
    mutationFn: (v: FormValues) => {
      const input: ModeInput = {
        enabled: v.enabled,
        minCount: 1,
        defaultCount: Math.min(10, v.maxCount),
        maxCount: v.maxCount,
        timeLimitSeconds: isMock ? (v.timeLimitMinutes ?? 60) * 60 : settings.timeLimitSeconds,
        perQuestionSeconds: settings.perQuestionSeconds,
        difficultyMix: settings.difficultyMix,
        shuffleQuestions: settings.shuffleQuestions,
        shuffleOptions: settings.shuffleOptions,
        feedbackTiming: isMock ? v.feedbackTiming : 'IMMEDIATE',
        resultVisibility: settings.resultVisibility,
        negativeMark: settings.negativeMark,
        passMarkPct: settings.passMarkPct,
        allowTopicChoice: v.allowTopicChoice,
        adaptiveEnabled: settings.adaptiveEnabled,
        maxRetakes: settings.maxRetakes,
        freeAttempts: v.freeAttempts,
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
            {label}
          </h3>
          <Badge tone={settings.enabled ? 'free' : 'neutral'}>{settings.enabled ? 'On' : settings.saved ? 'Off' : 'Not set up'}</Badge>
        </div>
        <p className="-mt-2 text-[14.5px] text-muted">{descriptions[settings.mode]}</p>
        <CheckboxField label={`Offer the ${label.toLowerCase()} for this exam`} {...register('enabled')} />
        <div className="grid gap-4 sm:grid-cols-2">
          <TextField
            label="Most questions in one session"
            type="number"
            min={1}
            max={500}
            help={`Questions are picked at random, without repeats. This exam has ${publishedQuestions} ready.`}
            error={errors.maxCount?.message}
            {...register('maxCount', { valueAsNumber: true })}
          />
          <TextField
            label="Free attempts per student"
            type="number"
            min={0}
            max={1000}
            help="After these, this exam type needs Premium. 0 means Premium only."
            error={errors.freeAttempts?.message}
            {...register('freeAttempts', { valueAsNumber: true })}
          />
        </div>
        {isMock && (
          <div className="grid gap-4 sm:grid-cols-2">
            <TextField
              label="Longest mock exam (minutes)"
              type="number"
              min={1}
              max={720}
              help="Enforced by the server. A shorter paper gets the time its questions need."
              error={errors.timeLimitMinutes?.message}
              {...register('timeLimitMinutes', { valueAsNumber: true })}
            />
            <SelectField label="When students see the answers" {...register('feedbackTiming')}>
              <option value="AFTER_SUBMIT">After they submit (like a real exam)</option>
              <option value="IMMEDIATE">After each question</option>
            </SelectField>
          </div>
        )}
        <CheckboxField label="Students can choose topics" help="Otherwise every topic of the exam is included." {...register('allowTopicChoice')} />
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
        {save.isSuccess && !formState.isDirty && <Alert tone="success" title={`${label} settings saved.`} />}
        <div>
          <Button type="submit" variant="secondary" loading={save.isPending} disabled={!formState.isDirty && settings.saved}>
            Save {label.toLowerCase()} settings
          </Button>
        </div>
      </form>
    </Card>
  );
}
