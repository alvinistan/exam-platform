import { useState } from 'react';
import { useSearchParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { Download, Receipt } from 'lucide-react';
import { Alert, Badge, Button, Card, EmptyState, ErrorState, SelectField, Skeleton, TextField } from '@/components/ui';
import { formatPrice } from '@/features/billing/api';
import { useAdminPlans } from '@/features/admin/billing/api';
import { ApiError } from '@/lib/api/problem';
import { PaymentDialog } from './PaymentDialog';
import { emptyFilter, exportPaymentsCsv, namesFor, paymentStateLabel, stateTone, usePayments, type PaymentFilter } from './api';
import { isoDay } from './range';

const dateTime = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' });
const PAGE = 25;

const FILTER_KEYS = Object.keys(emptyFilter) as (keyof PaymentFilter)[];

/** /admin/payments — every payment, searchable, with refunds; export for the accountant. */
export function PaymentsPage() {
  const [params, setParams] = useSearchParams();
  const filter: PaymentFilter = { ...emptyFilter };
  for (const k of FILTER_KEYS) {
    const v = params.get(k);
    if (v) (filter as unknown as Record<string, string>)[k] = v;
  }
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0);
  const [search, setSearch] = useState(filter.q);
  const [codeText, setCodeText] = useState(filter.code);
  const [openId, setOpenId] = useState<string | null>(null);
  const [exporting, setExporting] = useState<'idle' | 'busy' | { error: string } | { truncated: boolean; rows: number }>('idle');
  const plans = useAdminPlans();
  const payments = usePayments(filter, page, PAGE);
  const ids = payments.data?.items.map((p) => p.studentId) ?? [];
  const names = useQuery({
    queryKey: ['admin-finance', 'names', [...new Set(ids)].sort()],
    queryFn: () => namesFor(ids),
    enabled: ids.length > 0,
    staleTime: 5 * 60_000,
  });

  const set = (changes: Partial<PaymentFilter>) => {
    const next = new URLSearchParams(params);
    for (const [k, v] of Object.entries(changes)) {
      if (v) next.set(k, v);
      else next.delete(k);
    }
    next.delete('page');
    setParams(next, { replace: true });
  };
  const goTo = (p: number) => {
    const next = new URLSearchParams(params);
    if (p > 0) next.set('page', String(p));
    else next.delete('page');
    setParams(next, { replace: true });
  };
  const filtered = FILTER_KEYS.some((k) => filter[k]);

  const download = async () => {
    setExporting('busy');
    try {
      const { csv, rows, truncated } = await exportPaymentsCsv(filter);
      const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
      const a = document.createElement('a');
      a.href = url;
      a.download = `payments-${filter.from || 'all'}-to-${filter.to || isoDay(new Date())}.csv`;
      document.body.append(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
      setExporting({ truncated, rows });
    } catch (e) {
      setExporting({ error: e instanceof ApiError ? e.message : 'Please try again.' });
    }
  };

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-extrabold tracking-tight">Payments</h1>
          <p className="text-muted">Every checkout, newest first. Open one to see its refunds or refund it.</p>
        </div>
        <Button variant="secondary" disabled={exporting === 'busy'} onClick={() => void download()}>
          <Download aria-hidden="true" className="size-4" />
          {exporting === 'busy' ? 'Preparing…' : 'Download CSV'}
        </Button>
      </div>
      {typeof exporting === 'object' && 'error' in exporting && (
        <Alert tone="error" title="Couldn’t prepare the CSV">
          {exporting.error}
        </Alert>
      )}
      {typeof exporting === 'object' && 'rows' in exporting && (
        <Alert tone={exporting.truncated ? 'warning' : 'success'} title={`Downloaded ${exporting.rows} payments`}>
          {exporting.truncated
            ? 'The file has the newest 5,000. Choose a shorter period to get the rest.'
            : 'With names, emails, amounts, discounts and refunds.'}
        </Alert>
      )}

      <Card className="flex flex-col gap-4 p-4">
        <form
          role="search"
          className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4"
          onSubmit={(e) => {
            e.preventDefault();
            set({ q: search.trim(), code: codeText.trim().toUpperCase() });
          }}
        >
          <TextField label="From" type="date" value={filter.from} onChange={(e) => set({ from: e.target.value })} />
          <TextField label="To" type="date" value={filter.to} onChange={(e) => set({ to: e.target.value })} />
          <SelectField
            label="Status"
            value={filter.status}
            onChange={(e) => set({ status: e.target.value as PaymentFilter['status'] })}
          >
            <option value="">All</option>
            <option value="PAID">Paid</option>
            <option value="REFUNDED">Refunded (all or part)</option>
            <option value="FREE">Free with a code</option>
            <option value="PENDING">Waiting</option>
            <option value="FAILED">Failed</option>
            <option value="CANCELLED">Cancelled</option>
          </SelectField>
          <SelectField
            label="Currency"
            value={filter.currency}
            onChange={(e) => set({ currency: e.target.value as PaymentFilter['currency'] })}
          >
            <option value="">Both</option>
            <option value="LKR">LKR</option>
            <option value="USD">USD</option>
          </SelectField>
          <SelectField label="Plan" value={filter.planId} onChange={(e) => set({ planId: e.target.value })}>
            <option value="">All plans</option>
            {(plans.data ?? []).map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </SelectField>
          <TextField
            label="Discount code"
            value={codeText}
            onChange={(e) => setCodeText(e.target.value)}
            placeholder="LAUNCH20"
          />
          <div className="flex items-end gap-2 sm:col-span-2">
            <div className="min-w-0 flex-1">
              <TextField
                label="Payment ID or PayHere payment ID"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="320025071234"
              />
            </div>
            <Button type="submit" variant="secondary">
              Search
            </Button>
          </div>
        </form>
        {filtered && (
          <div className="flex flex-wrap items-center gap-2 text-[14px] text-muted">
            {filter.studentId && <Badge tone="primary">One student</Badge>}
            <Button
              variant="ghost"
              size="sm"
              onClick={() => {
                setSearch('');
                setCodeText('');
                setParams(new URLSearchParams(), { replace: true });
              }}
            >
              Clear filters
            </Button>
          </div>
        )}
      </Card>

      {payments.isPending ? (
        <Skeleton className="h-64 w-full" />
      ) : payments.isError ? (
        <ErrorState title="We couldn’t load the payments" onRetry={() => void payments.refetch()} />
      ) : payments.data.items.length === 0 ? (
        <Card className="p-6">
          <EmptyState icon={Receipt} title={filtered ? 'No payments match' : 'No payments yet'}>
            <p>
              {filtered
                ? 'Try a longer period or fewer filters.'
                : 'Payments appear here as soon as a student starts checking out.'}
            </p>
          </EmptyState>
        </Card>
      ) : (
        <Card className="flex flex-col gap-3 p-0">
          <div className="overflow-x-auto">
            <table className="w-full min-w-[860px] border-collapse text-[14.5px]">
              <caption className="sr-only">Payments</caption>
              <thead>
                <tr className="border-b border-border bg-[#fafbfc] text-left text-[12.5px] font-bold tracking-wider text-muted uppercase">
                  <th scope="col" className="px-4 py-3">
                    Date
                  </th>
                  <th scope="col" className="px-4 py-3">
                    Student
                  </th>
                  <th scope="col" className="px-4 py-3">
                    Plan
                  </th>
                  <th scope="col" className="px-4 py-3 text-right">
                    Amount
                  </th>
                  <th scope="col" className="px-4 py-3">
                    Status
                  </th>
                  <th scope="col" className="px-4 py-3">
                    <span className="sr-only">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {payments.data.items.map((p) => {
                  const who = names.data?.get(p.studentId);
                  return (
                    <tr key={p.id} className="border-b border-border align-top last:border-b-0">
                      <td className="px-4 py-3 whitespace-nowrap">{dateTime.format(new Date(p.createdAt))}</td>
                      <td className="px-4 py-3">
                        <span className="font-semibold">{who?.displayName ?? 'Student'}</span>
                        {who && <span className="block text-[13px] break-all text-muted">{who.email}</span>}
                      </td>
                      <td className="px-4 py-3">
                        {p.planName}
                        {p.discountCode && <span className="block text-[13px] text-muted">Code {p.discountCode}</span>}
                      </td>
                      <td className="px-4 py-3 text-right whitespace-nowrap tabular-nums">
                        {formatPrice(p.amountMinor, p.currency)}
                        {p.refundedMinor > 0 && (
                          <span className="block text-[13px] text-warning-ink">
                            −{formatPrice(p.refundedMinor, p.currency)} refunded
                          </span>
                        )}
                        {p.refundPendingMinor > 0 && (
                          <span className="block text-[13px] text-muted">
                            {formatPrice(p.refundPendingMinor, p.currency)} refund waiting
                          </span>
                        )}
                      </td>
                      <td className="px-4 py-3">
                        <Badge tone={stateTone(p)}>{paymentStateLabel(p)}</Badge>
                        {p.provider === 'TEST' && <span className="block text-[13px] text-muted">Test payment</span>}
                      </td>
                      <td className="px-4 py-3 text-right">
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => setOpenId(p.id)}
                          aria-label={`Open payment of ${dateTime.format(new Date(p.createdAt))}`}
                        >
                          Open
                        </Button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          <div className="flex flex-wrap items-center justify-between gap-3 px-4 pb-4">
            <p className="text-sm text-muted" aria-live="polite">
              Showing {page * PAGE + 1}–{Math.min(payments.data.page.totalElements, (page + 1) * PAGE)} of{' '}
              {payments.data.page.totalElements}
            </p>
            {payments.data.page.totalPages > 1 && (
              <div className="flex gap-2">
                <Button size="sm" variant="secondary" disabled={page === 0} onClick={() => goTo(page - 1)}>
                  Previous
                </Button>
                <Button
                  size="sm"
                  variant="secondary"
                  disabled={page + 1 >= payments.data.page.totalPages}
                  onClick={() => goTo(page + 1)}
                >
                  Next
                </Button>
              </div>
            )}
          </div>
        </Card>
      )}
      {openId && <PaymentDialog id={openId} onClose={() => setOpenId(null)} />}
    </div>
  );
}
