import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Gift, Plus, Tag } from 'lucide-react';
import { Alert, Badge, Button, Card, Dialog, EmptyState, ErrorState, Skeleton } from '@/components/ui';
import { accessKeys } from '@/features/access/api';
import { billingKeys, describeLimits, formatPrice, monthsLabel } from '@/features/billing/api';
import { ApiError } from '@/lib/api/problem';
import { AddStudentsDialog, CampaignDialog } from './CampaignDialog';
import { DiscountCodeDialog } from './DiscountCodeDialog';
import {
  billingAdminKeys,
  campaignOpen,
  endCampaign,
  useAdminPlans,
  useCampaigns,
  useDiscountCodes,
  usePlanExams,
  type Campaign,
  type DiscountCode,
} from './api';

const date = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' });
const dateTime = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' });

const th = 'px-4 py-3';
const headRow = 'border-b border-border bg-[#fafbfc] text-left text-[12.5px] font-bold tracking-wider text-muted uppercase';

/** /admin/promotions — free access campaigns and discount codes. Sale prices are set on each plan. */
export function PromotionsPage() {
  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-extrabold tracking-tight">Promotions</h1>
        <p className="text-muted">
          Give free access for a while, or discount codes for checkout. To put a plan on sale, edit the plan in Plans &amp;
          payments.
        </p>
      </div>
      <CampaignsCard />
      <CodesCard />
    </div>
  );
}

function CampaignsCard() {
  const campaigns = useCampaigns();
  const exams = usePlanExams();
  const [creating, setCreating] = useState(false);
  const [adding, setAdding] = useState<Campaign | null>(null);
  const [ending, setEnding] = useState<Campaign | null>(null);
  // One clock reading per visit: statuses don't flicker while the page is open.
  const [now] = useState(() => Date.now());
  const titleOf = (id: string) => exams.data?.find((x) => x.id === id)?.title ?? 'Exam';
  const covers = (c: Campaign) => (c.scopeType === 'ALL' ? 'Every exam' : c.examIds.map(titleOf).join(', '));
  return (
    <Card className="flex flex-col gap-5 p-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="text-lg font-bold">Free access campaigns</h2>
          <p className="text-[14.5px] text-muted">
            Like a plan nobody pays for: for every student between two dates, or for students you choose.
          </p>
        </div>
        <Button onClick={() => setCreating(true)}>
          <Plus aria-hidden="true" className="size-4" />
          New campaign
        </Button>
      </div>
      {campaigns.isPending ? (
        <Skeleton className="h-32 w-full" />
      ) : campaigns.isError ? (
        <ErrorState title="We couldn’t load the campaigns" onRetry={() => void campaigns.refetch()} />
      ) : campaigns.data.length === 0 ? (
        <EmptyState icon={Gift} title="No campaigns yet">
          <p>For example: free Mock exams for every student during exam week, or a month of Premium for your top students.</p>
        </EmptyState>
      ) : (
        <div className="overflow-x-auto rounded-md border border-border">
          <table className="w-full min-w-[820px] border-collapse text-[14.5px]">
            <caption className="sr-only">Free access campaigns</caption>
            <thead>
              <tr className={headRow}>
                <th scope="col" className={th}>
                  Campaign
                </th>
                <th scope="col" className={th}>
                  Covers
                </th>
                <th scope="col" className={th}>
                  Who
                </th>
                <th scope="col" className={th}>
                  Status
                </th>
                <th scope="col" className={th}>
                  <span className="sr-only">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {campaigns.data.map((c) => {
                const open = campaignOpen(c, now);
                const notYet = open && Date.parse(c.startsAt) > now;
                return (
                  <tr key={c.id} className="border-b border-border align-top last:border-b-0">
                    <td className="px-4 py-3">
                      <span className="font-semibold">{c.name}</span>
                      <span className="block text-[13px] text-muted">Started {date.format(new Date(c.createdAt))}</span>
                    </td>
                    <td className="max-w-[260px] px-4 py-3 text-ink-2">
                      {covers(c)}
                      {describeLimits(c.limits).map((line) => (
                        <span key={line} className="block text-[13px] text-muted">
                          {line}
                        </span>
                      ))}
                    </td>
                    <td className="px-4 py-3 text-ink-2">
                      {c.audience === 'ALL' ? (
                        <>
                          Every student
                          <span className="block text-[13px] text-muted">
                            {dateTime.format(new Date(c.startsAt))} – {c.endsAt && dateTime.format(new Date(c.endsAt))}
                          </span>
                          <span className="block text-[13px] text-muted">{c.students} used it so far</span>
                        </>
                      ) : (
                        <>
                          {c.students} student{c.students === 1 ? '' : 's'}
                          <span className="block text-[13px] text-muted">{monthsLabel(c.durationMonths ?? 1)} each</span>
                        </>
                      )}
                    </td>
                    <td className="px-4 py-3">
                      {c.endedAt ? (
                        <Badge tone="neutral">Ended early</Badge>
                      ) : !open ? (
                        <Badge tone="neutral">Finished</Badge>
                      ) : notYet ? (
                        <Badge tone="warning">Scheduled</Badge>
                      ) : (
                        <Badge tone="free">Running</Badge>
                      )}
                      {c.endedAt && <span className="block text-[13px] text-muted">{date.format(new Date(c.endedAt))}</span>}
                    </td>
                    <td className="px-4 py-3 text-right whitespace-nowrap">
                      {open && c.audience === 'SELECTED' && (
                        <Button variant="ghost" size="sm" onClick={() => setAdding(c)}>
                          Add students
                        </Button>
                      )}
                      {open && (
                        <Button variant="ghost" size="sm" className="text-danger-ink" onClick={() => setEnding(c)}>
                          End
                        </Button>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      {creating && <CampaignDialog onClose={() => setCreating(false)} />}
      {adding && <AddStudentsDialog campaign={adding} onClose={() => setAdding(null)} />}
      {ending && <EndCampaignDialog campaign={ending} onClose={() => setEnding(null)} />}
    </Card>
  );
}

function EndCampaignDialog({ campaign, onClose }: { campaign: Campaign; onClose: () => void }) {
  const queryClient = useQueryClient();
  const end = useMutation({
    mutationFn: () => endCampaign(campaign),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: billingAdminKeys.all });
      void queryClient.invalidateQueries({ queryKey: billingKeys.all });
      void queryClient.invalidateQueries({ queryKey: accessKeys.all });
      onClose();
    },
  });
  return (
    <Dialog
      open
      onClose={onClose}
      title={`End ${campaign.name}?`}
      description="Students lose this free access straight away. Attempts they already finished keep their results. This can’t be undone."
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Keep it running
          </Button>
          <Button variant="danger" disabled={end.isPending} onClick={() => end.mutate()}>
            {end.isPending ? 'Ending…' : 'End campaign'}
          </Button>
        </>
      }
    >
      {end.isError && (
        <Alert tone="error" title="Couldn’t end the campaign">
          {end.error instanceof ApiError ? end.error.message : 'Please try again.'}
        </Alert>
      )}
    </Dialog>
  );
}

function CodesCard() {
  const codes = useDiscountCodes();
  const plans = useAdminPlans();
  const [editing, setEditing] = useState<DiscountCode | 'new' | null>(null);
  const [now] = useState(() => Date.now());
  const planName = (id: string) => plans.data?.find((p) => p.id === id)?.name ?? 'Plan';
  const discount = (c: DiscountCode) =>
    c.percentOff !== null
      ? `${c.percentOff}% off`
      : [
          c.amountOffLkrMinor !== null && `${formatPrice(c.amountOffLkrMinor, 'LKR')} off`,
          c.amountOffUsdMinor !== null && `${formatPrice(c.amountOffUsdMinor, 'USD')} off`,
        ]
          .filter(Boolean)
          .join(' · ');
  const state = (c: DiscountCode): { tone: 'free' | 'neutral' | 'warning'; label: string } => {
    if (!c.active) return { tone: 'neutral', label: 'Off' };
    if (Date.parse(c.endsAt) <= now) return { tone: 'neutral', label: 'Expired' };
    if (c.maxUses !== null && c.uses >= c.maxUses) return { tone: 'neutral', label: 'Used up' };
    if (Date.parse(c.startsAt) > now) return { tone: 'warning', label: 'Scheduled' };
    return { tone: 'free', label: 'Working' };
  };
  return (
    <Card className="flex flex-col gap-5 p-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="text-lg font-bold">Discount codes</h2>
          <p className="text-[14.5px] text-muted">
            Students type a code at checkout. The server checks it and works out the price.
          </p>
        </div>
        <Button onClick={() => setEditing('new')}>
          <Plus aria-hidden="true" className="size-4" />
          New code
        </Button>
      </div>
      {codes.isPending ? (
        <Skeleton className="h-32 w-full" />
      ) : codes.isError ? (
        <ErrorState title="We couldn’t load the codes" onRetry={() => void codes.refetch()} />
      ) : codes.data.length === 0 ? (
        <EmptyState icon={Tag} title="No discount codes yet">
          <p>For example: LAUNCH20 for 20% off any plan this month, limited to 100 uses.</p>
        </EmptyState>
      ) : (
        <div className="overflow-x-auto rounded-md border border-border">
          <table className="w-full min-w-[820px] border-collapse text-[14.5px]">
            <caption className="sr-only">Discount codes</caption>
            <thead>
              <tr className={headRow}>
                <th scope="col" className={th}>
                  Code
                </th>
                <th scope="col" className={th}>
                  Discount
                </th>
                <th scope="col" className={th}>
                  Works
                </th>
                <th scope="col" className={th}>
                  Used
                </th>
                <th scope="col" className={th}>
                  Status
                </th>
                <th scope="col" className={th}>
                  <span className="sr-only">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {codes.data.map((c) => {
                const s = state(c);
                return (
                  <tr key={c.id} className="border-b border-border align-top last:border-b-0">
                    <td className="px-4 py-3">
                      <span className="font-mono font-semibold">{c.code}</span>
                      {c.description && <span className="block text-[13px] text-muted">{c.description}</span>}
                    </td>
                    <td className="px-4 py-3 text-ink-2">
                      {discount(c)}
                      <span className="block text-[13px] text-muted">
                        {c.allPlans ? 'Every plan' : c.planIds.map(planName).join(', ')}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-[13.5px] whitespace-nowrap text-ink-2">
                      {date.format(new Date(c.startsAt))} – {date.format(new Date(c.endsAt))}
                    </td>
                    <td className="px-4 py-3 tabular-nums">
                      {c.uses}
                      {c.maxUses !== null && <span className="text-muted"> of {c.maxUses}</span>}
                      <span className="block text-[13px] text-muted">
                        {c.maxUsesPerStudent === 1 ? 'Once per student' : `${c.maxUsesPerStudent} per student`}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <Badge tone={s.tone}>{s.label}</Badge>
                    </td>
                    <td className="px-4 py-3 text-right">
                      <Button variant="ghost" size="sm" onClick={() => setEditing(c)} aria-label={`Edit ${c.code}`}>
                        Edit
                      </Button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      {editing && <DiscountCodeDialog code={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </Card>
  );
}
