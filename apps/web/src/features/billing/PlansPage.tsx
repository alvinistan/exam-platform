import type { ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router';
import { Check, CreditCard, Layers, BookOpen, Sparkles } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { Alert, Badge, Card, EmptyState, ErrorState, Skeleton, buttonClasses } from '@/components/ui';
import { useCategories } from '@/features/exams/api';
import { useAuth } from '@/lib/auth/AuthContext';
import { cn } from '@/lib/cn';
import {
  describeLimits,
  formatPrice,
  formatPriceShort,
  monthsLabel,
  perMonth,
  priceIn,
  useMySubscription,
  usePlans,
  type Currency,
  type PublicPlan,
} from './api';

const longDate = new Intl.DateTimeFormat(undefined, { dateStyle: 'long' });

/**
 * /plans — plans on sale, priced by the administrator: single subjects, bundles by category, and everything. Public;
 * choosing one asks the visitor to sign in. ?exam= narrows to plans that include that exam; ?currency= picks LKR or USD.
 */
export function PlansPage() {
  const [params, setParams] = useSearchParams();
  const examId = params.get('exam') ?? undefined;
  const currency: Currency = params.get('currency') === 'USD' ? 'USD' : 'LKR';
  const plans = usePlans({ examId });
  const categories = useCategories();
  const { state } = useAuth();
  const mine = useMySubscription();

  const setCurrency = (c: Currency) => {
    const next = new URLSearchParams(params);
    next.set('currency', c);
    setParams(next, { replace: true });
  };
  const examTitle = examId ? plans.data?.plans.flatMap((p) => p.exams).find((e) => e.examId === examId)?.title : undefined;

  return (
    <div className="mx-auto flex max-w-[1100px] flex-col gap-8 px-4 py-8 sm:px-8 sm:py-12">
      <title>Plans · Leenex Labs</title>
      <header className="flex flex-col gap-3">
        <h1 className="text-3xl font-extrabold tracking-tight sm:text-4xl">
          {examTitle ? `Plans for ${examTitle}` : 'Choose a plan'}
        </h1>
        <p className="max-w-2xl text-lg text-ink-2">
          Every exam gives you a few free attempts. A plan adds attempts, or unlimited practice, for one subject, a whole group of
          subjects, or everything.
        </p>
        <div className="flex flex-wrap items-center justify-between gap-3">
          {examId ? (
            <Link to={{ pathname: '/plans', search: currency === 'USD' ? '?currency=USD' : '' }} className="font-semibold">
              See all plans
            </Link>
          ) : (
            <span />
          )}
          <CurrencySwitch value={currency} onChange={setCurrency} />
        </div>
      </header>

      {state.status === 'signedIn' && mine.data && mine.data.plans.length > 0 && (
        <Alert tone="success" title={`You have ${mine.data.plans.length === 1 ? 'a plan' : `${mine.data.plans.length} plans`}`}>
          {mine.data.premiumEndsAt && <>The last one ends on {longDate.format(new Date(mine.data.premiumEndsAt))}. </>}A new plan
          starts straight away and adds to what you have.{' '}
          <Link to="/account" className="font-semibold">
            See your plans
          </Link>
        </Alert>
      )}

      {plans.data?.testPayments && <TestModeNotice />}

      {plans.isPending ? (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {[0, 1, 2, 3].map((i) => (
            <Skeleton key={i} className="h-64" />
          ))}
        </div>
      ) : plans.isError ? (
        <ErrorState title="We couldn’t load the plans" onRetry={() => void plans.refetch()} />
      ) : plans.data.plans.length === 0 ? (
        <EmptyState icon={CreditCard} title={examId ? 'No plans for this exam yet' : 'No plans on sale right now'}>
          <p>Please check back soon. Your free attempts are still available on every exam.</p>
        </EmptyState>
      ) : (
        <PlanSections
          plans={plans.data.plans}
          currency={currency}
          categoryName={(id) => categories.data?.find((c) => c.id === id)?.name}
        />
      )}

      <p className="text-[13.5px] text-muted">
        Payments are processed by PayHere. You pay once for the period you choose; nothing renews automatically. Unused attempts
        don’t carry over when a plan ends.
      </p>
    </div>
  );
}

export function TestModeNotice() {
  return (
    <Alert tone="warning" title="Test payments">
      Real payments aren’t switched on yet. Choosing a plan opens a test page where no money moves.
    </Alert>
  );
}

function CurrencySwitch({ value, onChange }: { value: Currency; onChange: (c: Currency) => void }) {
  return (
    <div role="radiogroup" aria-label="Currency" className="inline-flex rounded-md border border-border bg-surface p-1">
      {(['LKR', 'USD'] as const).map((c) => (
        <button
          key={c}
          type="button"
          role="radio"
          aria-checked={value === c}
          onClick={() => onChange(c)}
          className={cn(
            'rounded px-3.5 py-1.5 text-[14px] font-semibold',
            value === c ? 'bg-primary text-white' : 'text-ink-2 hover:bg-surface-2',
          )}
        >
          {c === 'LKR' ? 'LKR (Rs)' : 'USD ($)'}
        </button>
      ))}
    </div>
  );
}

interface Group {
  key: string;
  title: string;
  plans: PublicPlan[];
}

/** Bundles by category, then single subjects by exam, then everything; each group sorted by length. */
function PlanSections({
  plans,
  currency,
  categoryName,
}: {
  plans: PublicPlan[];
  currency: Currency;
  categoryName: (id: string) => string | undefined;
}) {
  const groups = (scope: PublicPlan['scopeType'], keyOf: (p: PublicPlan) => string, titleOf: (p: PublicPlan) => string) => {
    const map = new Map<string, Group>();
    for (const p of plans.filter((x) => x.scopeType === scope)) {
      const key = keyOf(p);
      const g = map.get(key) ?? { key, title: titleOf(p), plans: [] };
      g.plans.push(p);
      map.set(key, g);
    }
    return [...map.values()].map((g) => ({ ...g, plans: g.plans.sort((a, b) => a.durationMonths - b.durationMonths) }));
  };
  const bundles = groups(
    'BUNDLE',
    (p) =>
      p.exams
        .map((e) => e.examId)
        .sort()
        .join(','),
    (p) => (p.categoryId && categoryName(p.categoryId)) || 'Bundle',
  );
  const singles = groups(
    'EXAM',
    (p) => p.exams[0]?.examId ?? p.id,
    (p) => p.exams[0]?.title ?? 'One exam',
  );
  const everything = groups(
    'ALL',
    () => 'all',
    () => 'Every exam',
  );

  return (
    <div className="flex flex-col gap-10">
      {bundles.length > 0 && (
        <Section icon={Layers} title="Subject bundles" intro="One price for a group of subjects.">
          {bundles.map((g) => (
            <GroupRow key={g.key} group={g} currency={currency} showSubjects />
          ))}
        </Section>
      )}
      {singles.length > 0 && (
        <Section icon={BookOpen} title="Single subjects" intro="Just the subject you need.">
          {singles.map((g) => (
            <GroupRow key={g.key} group={g} currency={currency} />
          ))}
        </Section>
      )}
      {everything.length > 0 && (
        <Section icon={Sparkles} title="Everything" intro="Every exam on the site, including new ones.">
          {everything.map((g) => (
            <GroupRow key={g.key} group={g} currency={currency} />
          ))}
        </Section>
      )}
    </div>
  );
}

function Section({
  icon: Icon,
  title,
  intro,
  children,
}: {
  icon: LucideIcon;
  title: string;
  intro: string;
  children: ReactNode;
}) {
  return (
    <section className="flex flex-col gap-5" aria-label={title}>
      <div className="flex items-center gap-3">
        <span className="flex size-9 items-center justify-center rounded-md bg-primary-soft text-primary">
          <Icon aria-hidden="true" className="size-5" />
        </span>
        <div>
          <h2 className="text-xl font-bold">{title}</h2>
          <p className="text-[14.5px] text-muted">{intro}</p>
        </div>
      </div>
      {children}
    </section>
  );
}

function GroupRow({ group, currency, showSubjects }: { group: Group; currency: Currency; showSubjects?: boolean }) {
  // "Best value": the lowest price per month in this group, when there's a real difference.
  const monthly = group.plans.map((p) => {
    const price = priceIn(p, currency);
    return price === null ? null : perMonth(price, p.durationMonths);
  });
  const known = monthly.filter((m): m is number => m !== null);
  const cheapest = known.length > 1 ? Math.min(...known) : null;
  const priciest = known.length > 1 ? Math.max(...known) : null;
  const subjects = group.plans[0]?.exams ?? [];
  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-col gap-1">
        <h3 className="text-lg font-bold">{group.title}</h3>
        {showSubjects && subjects.length > 0 && (
          <p className="text-[14px] text-ink-2">
            {subjects.length} subjects: {subjects.map((e) => e.title).join(', ')}
          </p>
        )}
      </div>
      <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {group.plans.map((p, i) => {
          const m = monthly[i];
          const best = cheapest !== null && m === cheapest && cheapest < (priciest ?? 0);
          const saving = best && priciest ? Math.round((1 - cheapest / priciest) * 100) : 0;
          return (
            <li key={p.id}>
              <PlanCard plan={p} currency={currency} best={best} saving={saving} />
            </li>
          );
        })}
      </ul>
    </div>
  );
}

function PlanCard({ plan, currency, best, saving }: { plan: PublicPlan; currency: Currency; best: boolean; saving: number }) {
  const price = priceIn(plan, currency);
  return (
    <Card className={cn('flex h-full flex-col gap-4 p-5', best && 'border-primary ring-2 ring-primary/20')}>
      <div className="flex items-start justify-between gap-2">
        <h4 className="text-[16.5px] font-bold">{plan.name}</h4>
        {best && <Badge tone="primary">Best value</Badge>}
      </div>
      {price === null ? (
        <p className="text-[14.5px] text-muted">Not sold in {currency}. Switch currency above.</p>
      ) : (
        <div>
          <p className="text-3xl font-extrabold tracking-tight tabular-nums">{formatPriceShort(price, currency)}</p>
          <p className="text-[14px] text-muted">
            for {monthsLabel(plan.durationMonths)}
            {plan.durationMonths > 1 && <> · {formatPrice(perMonth(price, plan.durationMonths), currency)} a month</>}
          </p>
          {saving > 0 && <p className="mt-1 text-[13.5px] font-semibold text-success-ink">Save {saving}% a month</p>}
        </div>
      )}
      <ul className="flex flex-col gap-1.5 text-[14px] text-ink-2">
        {describeLimits(plan.limits).map((line) => (
          <li key={line} className="flex items-start gap-2">
            <Check aria-hidden="true" className="mt-0.5 size-4 flex-none text-success" />
            {plan.scopeType === 'BUNDLE' ? `${line} per subject` : line}
          </li>
        ))}
      </ul>
      {plan.description && <p className="text-[13.5px] text-muted">{plan.description}</p>}
      {price !== null && (
        <Link
          to={`/checkout/${plan.id}?currency=${currency}`}
          className={cn(buttonClasses(best ? 'primary' : 'secondary'), 'mt-auto w-full')}
          aria-label={`Choose ${plan.name}, ${formatPrice(price, currency)}`}
        >
          Choose
        </Link>
      )}
    </Card>
  );
}
