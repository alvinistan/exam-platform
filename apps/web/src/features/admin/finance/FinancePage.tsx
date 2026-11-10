import { useId, useState } from 'react';
import { Link } from 'react-router';
import { ArrowRight, Info, Wallet } from 'lucide-react';
import { Alert, Badge, Card, EmptyState, ErrorState, Skeleton, TextField } from '@/components/ui';
import { buttonClasses } from '@/components/ui';
import { formatPrice, type Currency } from '@/features/billing/api';
import { useQuery } from '@tanstack/react-query';
import { cn } from '@/lib/cn';
import { namesFor, useFinanceSummary, type FinanceSummary, type Money } from './api';
import { PRESETS, dayDate, daysBetween, presetRange, type Preset } from './range';

const dayLong = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' });
const monthLong = new Intl.DateTimeFormat(undefined, { month: 'long', year: 'numeric' });
const monthShort = new Intl.DateTimeFormat(undefined, { month: 'short' });
const dayShort = new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short' });

/** /admin/finance — money in and out, payments and subscribers over a date range. Numbers come from the server. */
export function FinancePage() {
  const [preset, setPreset] = useState<Preset | 'CUSTOM'>('30D');
  const [custom, setCustom] = useState(() => presetRange('30D'));
  const range = preset === 'CUSTOM' ? custom : presetRange(preset);
  const valid = range.from !== '' && range.to !== '' && range.from <= range.to;
  const summary = useFinanceSummary(valid ? range.from : presetRange('30D').from, valid ? range.to : presetRange('30D').to);

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-extrabold tracking-tight">Finance</h1>
          <p className="text-muted">Payments, refunds and subscribers. Days are in your time zone.</p>
        </div>
        <Link to={`/admin/payments?from=${range.from}&to=${range.to}`} className={buttonClasses('secondary')}>
          See the payments <ArrowRight aria-hidden="true" className="size-4" />
        </Link>
      </div>

      <Card className="flex flex-col gap-3 p-4">
        <div role="radiogroup" aria-label="Period" className="flex flex-wrap gap-1.5">
          {[...PRESETS, { value: 'CUSTOM' as const, label: 'Custom' }].map((p) => (
            <button
              key={p.value}
              type="button"
              role="radio"
              aria-checked={preset === p.value}
              onClick={() => {
                if (p.value === 'CUSTOM') setCustom(range);
                setPreset(p.value);
              }}
              className={cn(
                'rounded-full border px-3.5 py-1.5 text-[14px] font-semibold',
                preset === p.value
                  ? 'border-primary bg-primary text-white'
                  : 'border-border-strong bg-surface text-ink-2 hover:border-primary hover:text-ink',
              )}
            >
              {p.label}
            </button>
          ))}
        </div>
        {preset === 'CUSTOM' && (
          <div className="grid max-w-md gap-3 sm:grid-cols-2">
            <TextField
              label="From"
              type="date"
              value={custom.from}
              onChange={(e) => setCustom({ ...custom, from: e.target.value })}
            />
            <TextField
              label="To"
              type="date"
              value={custom.to}
              onChange={(e) => setCustom({ ...custom, to: e.target.value })}
              error={valid ? undefined : 'Choose an end on or after the start.'}
            />
          </div>
        )}
      </Card>

      {summary.isPending ? (
        <div className="grid gap-4 md:grid-cols-2">
          <Skeleton className="h-40" />
          <Skeleton className="h-40" />
        </div>
      ) : summary.isError ? (
        <ErrorState title="We couldn’t load the finance summary" onRetry={() => void summary.refetch()} />
      ) : (
        <Summary data={summary.data} />
      )}
    </div>
  );
}

function Summary({ data }: { data: FinanceSummary }) {
  const period = `${dayLong.format(dayDate(data.from))} – ${dayLong.format(dayDate(data.to))}`;
  return (
    <>
      {!data.apiRefunds && (
        <Alert tone="info" title="Refunds are recorded by hand">
          Refund in PayHere’s portal, then record it on the payment. To refund straight from here, add a PayHere API key
          (PAYHERE_APP_ID and PAYHERE_APP_SECRET).
        </Alert>
      )}
      <section aria-labelledby="money-heading" className="flex flex-col gap-3">
        <h2 id="money-heading" className="text-lg font-bold">
          Money <span className="text-[14px] font-normal text-muted">· {period}</span>
        </h2>
        {data.money.length === 0 ? (
          <Card className="p-6">
            <EmptyState icon={Wallet} title="No payments in this period">
              <p>Paid and refunded amounts appear here, per currency.</p>
            </EmptyState>
          </Card>
        ) : (
          <div className="grid gap-4 md:grid-cols-2">
            {data.money.map((m) => (
              <MoneyCard key={m.currency} money={m} />
            ))}
          </div>
        )}
        <Card className="flex flex-wrap gap-x-8 gap-y-3 p-5">
          {(
            [
              ['Paid', data.payments.paid],
              ['Free with a code', data.payments.free],
              ['Waiting', data.payments.pending],
              ['Failed', data.payments.failed],
              ['Cancelled', data.payments.cancelled],
            ] as const
          ).map(([label, n]) => (
            <div key={label}>
              <p className="text-[13px] text-muted">{label}</p>
              <p className="text-xl font-bold tabular-nums">{n}</p>
            </div>
          ))}
          <p className="basis-full text-[13px] text-muted">Payments started in this period, by what happened to them.</p>
        </Card>
      </section>

      {data.money.length > 0 && <Chart data={data} />}

      <section aria-labelledby="subscribers-heading" className="flex flex-col gap-3">
        <h2 id="subscribers-heading" className="text-lg font-bold">
          Subscribers
        </h2>
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          <Stat label="With a paid plan now" value={data.subscribers.activeNow} />
          <Stat label="Plan ends within 7 days" value={data.subscribers.endingIn7Days} tone="warning" />
          <Stat label="Plan ends within 30 days" value={data.subscribers.endingIn30Days} />
          <Stat label="New buyers in this period" value={data.subscribers.newBuyers} note="First paid plan ever" />
          <Stat label="Returning buyers in this period" value={data.subscribers.returningBuyers} note="Bought before too" />
          <Stat label="Free access now" value={data.subscribers.freeAccessNow} note="Campaigns and access you gave" />
        </div>
      </section>

      <div className="flex flex-col gap-6">
        <EndingSoon rows={data.endingSoon} />
        <ByPlan rows={data.byPlan} />
      </div>
    </>
  );
}

function MoneyCard({ money: m }: { money: Money }) {
  return (
    <Card className="flex flex-col gap-4 p-5">
      <div className="flex items-center justify-between">
        <h3 className="font-bold">{m.currency}</h3>
        <Badge tone="neutral">
          {m.paidPayments} paid · {m.refunds} refund{m.refunds === 1 ? '' : 's'}
        </Badge>
      </div>
      <div>
        <p className="text-[13px] text-muted">Net (paid − refunded)</p>
        <p className="text-[26px] leading-tight font-extrabold tabular-nums">{formatPrice(m.netMinor, m.currency)}</p>
      </div>
      <dl className="grid grid-cols-2 gap-3 border-t border-border pt-3">
        <div>
          <dt className="text-[13px] text-muted">Paid</dt>
          <dd className="font-semibold whitespace-nowrap tabular-nums">{formatPrice(m.paidMinor, m.currency)}</dd>
        </div>
        <div>
          <dt className="text-[13px] text-muted">Refunded</dt>
          <dd className="font-semibold whitespace-nowrap tabular-nums">
            {m.refundedMinor > 0 ? `−${formatPrice(m.refundedMinor, m.currency)}` : 'Nothing'}
          </dd>
        </div>
      </dl>
      {m.discountMinor > 0 && (
        <p className="text-[13px] text-muted">{formatPrice(m.discountMinor, m.currency)} given in discount codes.</p>
      )}
    </Card>
  );
}

function Stat({ label, value, note, tone }: { label: string; value: number; note?: string; tone?: 'warning' }) {
  return (
    <Card className="flex flex-col gap-1 p-5">
      <p className="text-[13.5px] text-muted">{label}</p>
      <p className={cn('text-2xl font-extrabold tabular-nums', tone === 'warning' && value > 0 && 'text-warning-ink')}>{value}</p>
      {note && <p className="text-[12.5px] text-muted">{note}</p>}
    </Card>
  );
}

interface Bucket {
  key: string;
  label: string;
  tick: string;
  paid: number;
  refunded: number;
  payments: number;
}

/** Paid per day (or per month for long periods) in one currency; refunds and counts in the tooltip and table. */
function Chart({ data }: { data: FinanceSummary }) {
  const currencies = data.money.map((m) => m.currency);
  const [chosen, setChosen] = useState<Currency>(currencies[0] ?? 'LKR');
  const currency = currencies.includes(chosen) ? chosen : (currencies[0] ?? 'LKR');
  const [active, setActive] = useState<number | null>(null);
  const tableId = useId();
  const days = daysBetween(data.from, data.to);
  const monthly = days.length > 62;
  const buckets = new Map<string, Bucket>();
  for (const day of days) {
    const key = monthly ? day.slice(0, 7) : day;
    if (!buckets.has(key)) {
      const d = dayDate(monthly ? `${key}-01` : day);
      buckets.set(key, {
        key,
        label: monthly ? monthLong.format(d) : dayLong.format(d),
        tick: monthly ? monthShort.format(d) : dayShort.format(d),
        paid: 0,
        refunded: 0,
        payments: 0,
      });
    }
  }
  for (const d of data.days.filter((x) => x.currency === currency)) {
    const b = buckets.get(monthly ? d.date.slice(0, 7) : d.date);
    if (!b) continue;
    b.paid += d.paidMinor;
    b.refunded += d.refundedMinor;
    b.payments += d.payments;
  }
  const rows = [...buckets.values()];
  const max = Math.max(1, ...rows.map((r) => r.paid));
  const tickEvery = Math.ceil(rows.length / 8);
  return (
    <section aria-labelledby="chart-heading">
      <Card className="flex flex-col gap-4 p-5 sm:p-6">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h2 id="chart-heading" className="text-lg font-bold">
              Paid per {monthly ? 'month' : 'day'}
            </h2>
            <p className="text-[13.5px] text-muted">
              In {currency}. Hover or focus a bar for refunds and the number of payments.
            </p>
          </div>
          {currencies.length > 1 && (
            <div role="radiogroup" aria-label="Currency" className="flex rounded-md border border-border-strong p-0.5">
              {currencies.map((c) => (
                <button
                  key={c}
                  type="button"
                  role="radio"
                  aria-checked={c === currency}
                  onClick={() => setChosen(c)}
                  className={cn(
                    'rounded px-3 py-1 text-[14px] font-semibold',
                    c === currency ? 'bg-primary text-white' : 'text-ink-2',
                  )}
                >
                  {c}
                </button>
              ))}
            </div>
          )}
        </div>
        <div className="relative">
          <ol className="flex h-48 items-end gap-0.5 border-b border-border-strong" aria-describedby={tableId}>
            {rows.map((r, i) => (
              <li key={r.key} className="flex h-full min-w-0 flex-1 items-end">
                <button
                  type="button"
                  aria-label={`${r.label}: paid ${formatPrice(r.paid, currency)}, refunded ${formatPrice(r.refunded, currency)}, ${r.payments} payments`}
                  onMouseEnter={() => setActive(i)}
                  onMouseLeave={() => setActive(null)}
                  onFocus={() => setActive(i)}
                  onBlur={() => setActive(null)}
                  className="group flex h-full w-full items-end justify-center focus-visible:outline-offset-1"
                >
                  <span
                    className={cn(
                      'block w-full max-w-7 rounded-t-[4px] transition-colors',
                      r.paid ? 'bg-primary group-hover:bg-primary-hover' : 'bg-transparent',
                    )}
                    style={{ height: r.paid ? `${Math.max(3, (r.paid / max) * 100)}%` : 0 }}
                  />
                </button>
              </li>
            ))}
          </ol>
          {active !== null && rows[active] && (
            <div
              role="tooltip"
              className="pointer-events-none absolute -top-2 z-10 -translate-x-1/2 -translate-y-full rounded-md border border-border bg-surface px-3 py-2 text-[13px] whitespace-nowrap shadow-raised"
              style={{ left: `${Math.min(85, Math.max(15, ((active + 0.5) / rows.length) * 100))}%` }}
            >
              <p className="font-semibold">{rows[active].label}</p>
              <p className="text-ink-2 tabular-nums">Paid {formatPrice(rows[active].paid, currency)}</p>
              <p className="text-ink-2 tabular-nums">Refunded {formatPrice(rows[active].refunded, currency)}</p>
              <p className="text-ink-2">
                {rows[active].payments} payment{rows[active].payments === 1 ? '' : 's'}
              </p>
            </div>
          )}
          <div className="mt-1.5 flex gap-0.5 text-[11px] text-muted" aria-hidden="true">
            {rows.map((r, i) => (
              <span key={r.key} className="min-w-0 flex-1 overflow-visible text-center whitespace-nowrap">
                {i % tickEvery === 0 ? r.tick : ''}
              </span>
            ))}
          </div>
        </div>
        <table id={tableId} className="sr-only">
          <caption>Paid and refunded per {monthly ? 'month' : 'day'}</caption>
          <thead>
            <tr>
              <th scope="col">{monthly ? 'Month' : 'Day'}</th>
              <th scope="col">Paid</th>
              <th scope="col">Refunded</th>
              <th scope="col">Payments</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.key}>
                <th scope="row">{r.label}</th>
                <td>{formatPrice(r.paid, currency)}</td>
                <td>{formatPrice(r.refunded, currency)}</td>
                <td>{r.payments}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
    </section>
  );
}

const th = 'px-4 py-3';
const headRow = 'border-b border-border bg-[#fafbfc] text-left text-[12.5px] font-bold tracking-wider text-muted uppercase';

function EndingSoon({ rows }: { rows: FinanceSummary['endingSoon'] }) {
  const names = useQuery({
    queryKey: ['admin-finance', 'names', rows.map((r) => r.studentId)],
    queryFn: () => namesFor(rows.map((r) => r.studentId)),
    enabled: rows.length > 0,
    staleTime: 5 * 60_000,
  });
  return (
    <Card className="flex flex-col gap-4 p-5">
      <div>
        <h2 className="text-lg font-bold">Plans ending within 30 days</h2>
        <p className="text-[13.5px] text-muted">Students whose last paid plan ends soon, for a reminder to renew.</p>
      </div>
      {rows.length === 0 ? (
        <p className="flex items-center gap-2 text-[14px] text-muted">
          <Info aria-hidden="true" className="size-4" /> No paid plans end in the next 30 days.
        </p>
      ) : (
        <div className="overflow-x-auto rounded-md border border-border">
          <table className="w-full min-w-[480px] border-collapse text-[14px]">
            <caption className="sr-only">Plans ending within 30 days</caption>
            <thead>
              <tr className={headRow}>
                <th scope="col" className={th}>
                  Student
                </th>
                <th scope="col" className={th}>
                  Plan
                </th>
                <th scope="col" className={th}>
                  Ends
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => {
                const who = names.data?.get(r.studentId);
                return (
                  <tr key={r.studentId} className="border-b border-border last:border-b-0">
                    <td className="px-4 py-2.5">
                      <Link to={`/admin/payments?studentId=${r.studentId}`} className="font-semibold">
                        {who?.displayName ?? 'Student'}
                      </Link>
                      {who && <span className="block text-[13px] break-all text-muted">{who.email}</span>}
                    </td>
                    <td className="px-4 py-2.5 text-ink-2">{r.planName}</td>
                    <td className="px-4 py-2.5 whitespace-nowrap">{dayLong.format(new Date(r.endsAt))}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}

function ByPlan({ rows }: { rows: FinanceSummary['byPlan'] }) {
  return (
    <Card className="flex flex-col gap-4 p-5">
      <div>
        <h2 className="text-lg font-bold">Paid by plan</h2>
        <p className="text-[13.5px] text-muted">Before refunds, in this period.</p>
      </div>
      {rows.length === 0 ? (
        <p className="flex items-center gap-2 text-[14px] text-muted">
          <Info aria-hidden="true" className="size-4" /> No paid plans in this period.
        </p>
      ) : (
        <div className="overflow-x-auto rounded-md border border-border">
          <table className="w-full min-w-[420px] border-collapse text-[14px]">
            <caption className="sr-only">Paid by plan</caption>
            <thead>
              <tr className={headRow}>
                <th scope="col" className={th}>
                  Plan
                </th>
                <th scope="col" className={`${th} text-right`}>
                  Payments
                </th>
                <th scope="col" className={`${th} text-right`}>
                  Paid
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={`${r.planName}-${r.currency}`} className="border-b border-border last:border-b-0">
                  <td className="px-4 py-2.5 font-semibold">{r.planName}</td>
                  <td className="px-4 py-2.5 text-right tabular-nums">{r.payments}</td>
                  <td className="px-4 py-2.5 text-right whitespace-nowrap tabular-nums">
                    {formatPrice(r.paidMinor, r.currency)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}
