import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { jsonResponse, mockApi, renderRoute, signedIn } from '@/test/render';
import { clearSession } from '@/lib/auth/session';
import { csvCell, exportPaymentsCsv, emptyFilter, paymentStateLabel } from './api';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  clearSession();
});

const admin = { roles: ['STUDENT', 'ADMIN'] };

const payment = (extra: Record<string, unknown> = {}) => ({
  id: 'tx-1',
  studentId: 'stu-1',
  planId: 'p1',
  planName: '3 months',
  durationMonths: 3,
  provider: 'PAYHERE',
  providerRef: '320027150501',
  amountMinor: 390000,
  currency: 'LKR',
  status: 'SUCCEEDED',
  failureReason: null,
  listPriceMinor: 450000,
  discountMinor: 60000,
  discountCode: 'LAUNCH20',
  refundedMinor: 0,
  refundPendingMinor: 0,
  refundState: 'NONE',
  periodStatus: 'ACTIVE',
  periodStartsAt: '2026-11-01T08:00:00Z',
  periodEndsAt: '2027-02-01T08:00:00Z',
  createdAt: '2026-11-01T08:00:00Z',
  updatedAt: '2026-11-01T08:00:00Z',
  ...extra,
});
const page = (items: unknown[], total = items.length) => ({
  items,
  page: { page: 0, size: 25, totalElements: total, totalPages: Math.max(1, Math.ceil(total / 25)) },
});
const contacts = () =>
  jsonResponse([
    { id: 'stu-1', displayName: 'Nimal Perera', email: 'nimal@example.lk' },
    { id: 'stu-2', displayName: '=HYPERLINK("x")', email: 'evil@example.lk' },
  ]);

describe('finance summary', () => {
  it('shows money per currency, payments, a chart, subscribers and plans ending soon', async () => {
    const asked: URL[] = [];
    mockApi({
      ...signedIn(admin),
      'GET /api/admin/billing/finance/summary': ({ url }) => {
        asked.push(url);
        return jsonResponse({
          from: url.searchParams.get('from'),
          to: url.searchParams.get('to'),
          tz: 'Asia/Colombo',
          money: [
            {
              currency: 'LKR',
              paidMinor: 1290000,
              refundedMinor: 150000,
              netMinor: 1140000,
              discountMinor: 60000,
              paidPayments: 4,
              refunds: 1,
            },
            { currency: 'USD', paidMinor: 4500, refundedMinor: 0, netMinor: 4500, discountMinor: 0, paidPayments: 1, refunds: 0 },
          ],
          payments: { paid: 5, free: 1, pending: 2, failed: 3, cancelled: 0 },
          byPlan: [{ planName: '3 months', currency: 'LKR', payments: 3, paidMinor: 1170000 }],
          days: [{ date: url.searchParams.get('to'), currency: 'LKR', paidMinor: 390000, refundedMinor: 0, payments: 1 }],
          subscribers: {
            activeNow: 12,
            endingIn7Days: 2,
            endingIn30Days: 5,
            newBuyers: 4,
            returningBuyers: 1,
            freeAccessNow: 30,
          },
          endingSoon: [{ studentId: 'stu-1', planName: '1 month', endsAt: '2026-11-14T08:00:00Z' }],
          apiRefunds: false,
        });
      },
      'GET /api/admin/users/contacts': contacts,
    });
    renderRoute('/admin/finance');
    expect(await screen.findByRole('heading', { level: 1, name: 'Finance' })).toBeInTheDocument();
    expect(await screen.findByText('LKR 12,900.00')).toBeInTheDocument();
    expect(screen.getByText('−LKR 1,500.00')).toBeInTheDocument();
    expect(screen.getByText('LKR 11,400.00')).toBeInTheDocument();
    expect(screen.getByText('USD 45.00', { selector: 'dd' })).toBeInTheDocument();
    expect(screen.getByText('LKR 600.00 given in discount codes.')).toBeInTheDocument();
    expect(screen.getByText('Refunds are recorded by hand')).toBeInTheDocument();
    expect(screen.getByText('With a paid plan now').nextSibling).toHaveTextContent('12');
    const ending = screen.getByRole('table', { name: 'Plans ending within 30 days' });
    expect(await within(ending).findByText('Nimal Perera')).toBeInTheDocument();
    expect(within(ending).getByText('nimal@example.lk')).toBeInTheDocument();
    expect(within(screen.getByRole('table', { name: 'Paid by plan' })).getByText('LKR 11,700.00')).toBeInTheDocument();
    // The chart has a bar per day, labelled, and a table for screen readers.
    expect(screen.getAllByRole('button', { name: /: paid LKR/ })).toHaveLength(30);
    expect(screen.getByRole('table', { name: 'Paid and refunded per day' })).toBeInTheDocument();
    expect(asked[0]?.searchParams.get('tz')).toBeTruthy();

    const user = userEvent.setup();
    await user.click(screen.getByRole('radio', { name: 'USD' }));
    expect(screen.getByRole('heading', { name: 'Paid per day' }).nextSibling).toHaveTextContent('In USD.');
    await user.click(screen.getByRole('radio', { name: 'Last 7 days' }));
    await waitFor(() => expect(asked).toHaveLength(2));
    const from = new Date(`${asked[1]?.searchParams.get('from')}T12:00:00`);
    const to = new Date(`${asked[1]?.searchParams.get('to')}T12:00:00`);
    expect(Math.round((to.getTime() - from.getTime()) / 86_400_000)).toBe(6);
    await user.click(screen.getByRole('radio', { name: 'Custom' }));
    expect(screen.getByLabelText('From')).toBeInTheDocument();
  });
});

describe('payments and refunds', () => {
  it('lists and filters payments, and records a manual refund that stops the plan', async () => {
    const queries: URL[] = [];
    let refunded: unknown;
    mockApi({
      ...signedIn(admin),
      'GET /api/admin/billing/plans': () => jsonResponse([]),
      'GET /api/admin/billing/payments': ({ url }) => {
        queries.push(url);
        return jsonResponse(
          page([payment(), payment({ id: 'tx-2', studentId: 'stu-2', status: 'FAILED', providerRef: null })], 2),
        );
      },
      'GET /api/admin/users/contacts': contacts,
      'GET /api/admin/billing/payments/tx-1': () =>
        jsonResponse({ payment: payment(), refunds: [], refundableMinor: 390000, apiRefunds: false }),
      'POST /api/admin/billing/payments/tx-1/refunds': ({ body }) => {
        refunded = body;
        return jsonResponse({ id: 'r1', status: 'SUCCEEDED' }, 201);
      },
    });
    renderRoute('/admin/payments');
    const table = await screen.findByRole('table', { name: 'Payments' });
    expect(await within(table).findByText('Nimal Perera')).toBeInTheDocument();
    expect(within(table).getByText('nimal@example.lk')).toBeInTheDocument();
    expect(within(table).getAllByText('Code LAUNCH20')).toHaveLength(2);
    expect(within(table).getByText('Paid')).toBeInTheDocument();
    expect(within(table).getByText('Failed')).toBeInTheDocument();

    const user = userEvent.setup();
    await user.selectOptions(screen.getByLabelText('Status'), 'REFUNDED');
    await waitFor(() => expect(queries.at(-1)?.searchParams.get('status')).toBe('REFUNDED'));
    await user.type(screen.getByLabelText('Payment ID or PayHere payment ID'), '320027150501');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    await waitFor(() => expect(queries.at(-1)?.searchParams.get('q')).toBe('320027150501'));

    await user.click(within(table).getAllByRole('button', { name: /Open payment/ })[0] as HTMLElement);
    const dialog = await screen.findByRole('dialog', { name: 'Payment' });
    expect(await within(dialog).findByText('Nimal Perera · nimal@example.lk')).toBeInTheDocument();
    expect(within(dialog).getByText('320027150501')).toBeInTheDocument();
    expect(within(dialog).getByText(/list price LKR 4,500.00, code LAUNCH20/)).toBeInTheDocument();
    expect(within(dialog).getByText('Refund in PayHere first')).toBeInTheDocument();
    expect(within(dialog).getByLabelText('Amount (LKR)')).toHaveValue('3900.00');

    await user.click(within(dialog).getByRole('button', { name: 'Review refund' }));
    expect(within(dialog).getByText('Say why, in a few words. Students don’t see this.')).toBeInTheDocument();
    await user.clear(within(dialog).getByLabelText('Amount (LKR)'));
    await user.type(within(dialog).getByLabelText('Amount (LKR)'), '5000');
    await user.type(within(dialog).getByLabelText('Reason'), 'Paid twice by mistake');
    await user.click(within(dialog).getByRole('button', { name: 'Review refund' }));
    expect(within(dialog).getByText('At most LKR 3,900.00 can be refunded.')).toBeInTheDocument();

    await user.clear(within(dialog).getByLabelText('Amount (LKR)'));
    await user.type(within(dialog).getByLabelText('Amount (LKR)'), '3900');
    await user.type(within(dialog).getByLabelText('PayHere refund number or note (optional)'), '560034010257');
    await user.click(within(dialog).getByRole('button', { name: 'Review refund' }));
    expect(
      within(dialog).getByText(/This records a refund of LKR 3,900.00 made outside this site. The plan stops now./),
    ).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Record the refund' }));
    await waitFor(() => expect(refunded).toBeDefined());
    expect(refunded).toEqual({
      amountMinor: null,
      reason: 'Paid twice by mistake',
      stopAccess: true,
      method: 'MANUAL',
      reference: '560034010257',
    });
  });

  it('refunds part of a payment through PayHere when it is set up', async () => {
    let refunded: unknown;
    mockApi({
      ...signedIn(admin),
      'GET /api/admin/billing/plans': () => jsonResponse([]),
      'GET /api/admin/billing/payments': () => jsonResponse(page([payment()])),
      'GET /api/admin/users/contacts': contacts,
      'GET /api/admin/billing/payments/tx-1': () =>
        jsonResponse({ payment: payment(), refunds: [], refundableMinor: 390000, apiRefunds: true }),
      'POST /api/admin/billing/payments/tx-1/refunds': ({ body }) => {
        refunded = body;
        return jsonResponse(
          {
            id: 'r1',
            amountMinor: 100000,
            currency: 'LKR',
            status: 'FAILED',
            failureReason: 'PayHere: Error processing refund',
            stopAccess: false,
          },
          201,
        );
      },
    });
    renderRoute('/admin/payments');
    const user = userEvent.setup();
    await user.click((await screen.findAllByRole('button', { name: /Open payment/ }))[0] as HTMLElement);
    const dialog = await screen.findByRole('dialog', { name: 'Payment' });
    expect(await within(dialog).findByRole('radio', { name: /Refund through PayHere now/ })).toBeChecked();
    await user.clear(within(dialog).getByLabelText('Amount (LKR)'));
    await user.type(within(dialog).getByLabelText('Amount (LKR)'), '1000');
    await user.type(within(dialog).getByLabelText('Reason'), 'Goodwill refund');
    await user.click(within(dialog).getByRole('checkbox', { name: /Stop the plan now/ }));
    await user.click(within(dialog).getByRole('button', { name: 'Review refund' }));
    expect(
      within(dialog).getByText(/PayHere will send LKR 1,000.00 back to the student. This can’t be undone. The plan continues./),
    ).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Refund LKR 1,000.00' }));
    expect(await within(dialog).findByText('PayHere didn’t refund it')).toBeInTheDocument();
    expect(within(dialog).getByText('PayHere: Error processing refund')).toBeInTheDocument();
    expect(refunded).toEqual({
      amountMinor: 100000,
      reason: 'Goodwill refund',
      stopAccess: false,
      method: 'PAYHERE_API',
      reference: null,
    });
  });

  it('settles a refund PayHere never answered', async () => {
    let resolved: unknown;
    const pending = {
      id: 'r9',
      transactionId: 'tx-1',
      amountMinor: 390000,
      currency: 'LKR',
      reason: 'Duplicate payment',
      method: 'PAYHERE_API',
      status: 'PENDING',
      providerRef: null,
      failureReason: null,
      stopAccess: true,
      createdBy: 'admin-1',
      createdAt: '2026-11-02T08:00:00Z',
      updatedAt: '2026-11-02T08:00:00Z',
    };
    mockApi({
      ...signedIn(admin),
      'GET /api/admin/billing/plans': () => jsonResponse([]),
      'GET /api/admin/billing/payments': () => jsonResponse(page([payment({ refundPendingMinor: 390000 })])),
      'GET /api/admin/users/contacts': contacts,
      'GET /api/admin/billing/payments/tx-1': () =>
        jsonResponse({
          payment: payment({ refundPendingMinor: 390000 }),
          refunds: [pending],
          refundableMinor: 0,
          apiRefunds: true,
        }),
      'POST /api/admin/billing/refunds/r9/resolve': ({ body }) => {
        resolved = body;
        return jsonResponse({ ...pending, status: 'SUCCEEDED' });
      },
    });
    renderRoute('/admin/payments');
    expect(await screen.findByText('LKR 3,900.00 refund waiting')).toBeInTheDocument();
    const user = userEvent.setup();
    await user.click(screen.getAllByRole('button', { name: /Open payment/ })[0] as HTMLElement);
    const dialog = await screen.findByRole('dialog', { name: 'Payment' });
    expect(await within(dialog).findByText('Waiting for PayHere')).toBeInTheDocument();
    expect(within(dialog).queryByRole('button', { name: 'Review refund' })).not.toBeInTheDocument();
    await user.type(within(dialog).getByLabelText('PayHere refund number (optional)'), '560034010999');
    await user.click(within(dialog).getByRole('button', { name: 'It was refunded' }));
    await waitFor(() => expect(resolved).toEqual({ outcome: 'SUCCEEDED', reference: '560034010999', note: null }));
  });
});

describe('payments CSV', () => {
  it('quotes cells and stops spreadsheets running formulas', () => {
    expect(csvCell('3 months')).toBe('3 months');
    expect(csvCell('Perera, Nimal')).toBe('"Perera, Nimal"');
    expect(csvCell('say "hi"')).toBe('"say ""hi"""');
    expect(csvCell('=HYPERLINK("x")')).toBe(`"'=HYPERLINK(""x"")"`);
    expect(csvCell('-12.50')).toBe('-12.50');
    expect(csvCell('+94 77')).toBe(`"'+94 77"`);
  });

  it('exports every page with names and emails', async () => {
    const pages: string[] = [];
    mockApi({
      'GET /api/admin/billing/payments': ({ url }) => {
        pages.push(url.searchParams.get('page') ?? '');
        const n = Number(url.searchParams.get('page'));
        return jsonResponse({
          items: [
            n === 0
              ? payment({ refundedMinor: 100000, refundState: 'PARTIAL' })
              : payment({ id: 'tx-2', studentId: 'stu-2', status: 'FAILED' }),
          ],
          page: { page: n, size: 100, totalElements: 2, totalPages: 2 },
        });
      },
      'GET /api/admin/users/contacts': contacts,
    });
    const { csv, rows, truncated } = await exportPaymentsCsv({ ...emptyFilter, status: '' });
    expect(pages).toEqual(['0', '1']);
    expect(rows).toBe(2);
    expect(truncated).toBe(false);
    const lines = csv.replace('﻿', '').split('\r\n');
    expect(lines[0]).toBe(
      'Date,Payment ID,PayHere payment ID,Student,Email,Plan,Months,Currency,List price,Discount,Code,Paid,Refunded,Net,Status,Provider',
    );
    expect(lines[1]).toBe(
      '2026-11-01T08:00:00Z,tx-1,320027150501,Nimal Perera,nimal@example.lk,3 months,3,LKR,4500.00,600.00,LAUNCH20,3900.00,1000.00,2900.00,Partly refunded,PAYHERE',
    );
    expect(lines[2]).toContain(`"'=HYPERLINK(""x"")",evil@example.lk`);
    expect(lines[2]).toContain(',0.00,0.00,0.00,Failed,PAYHERE');
  });

  it('labels every payment state', () => {
    const base = {
      status: 'SUCCEEDED' as const,
      refundState: 'NONE' as const,
      provider: 'PAYHERE' as const,
      failureReason: null,
    };
    expect(paymentStateLabel(base)).toBe('Paid');
    expect(paymentStateLabel({ ...base, refundState: 'FULL' })).toBe('Refunded');
    expect(paymentStateLabel({ ...base, provider: 'FREE' })).toBe('Free with a code');
    expect(paymentStateLabel({ ...base, status: 'CANCELLED', failureReason: 'CHARGEDBACK' })).toBe('Charged back');
    expect(paymentStateLabel({ ...base, status: 'PENDING' })).toBe('Waiting');
  });
});

describe('student payments and receipts', () => {
  const mine = (extra: Record<string, unknown> = {}) => ({
    id: 'tx-1',
    planName: '3 months',
    durationMonths: 3,
    amountMinor: 390000,
    currency: 'LKR',
    provider: 'PAYHERE',
    status: 'SUCCEEDED',
    failureReason: null,
    createdAt: '2026-11-01T08:00:00Z',
    premiumStartsAt: '2026-11-01T08:00:00Z',
    premiumEndsAt: '2027-02-01T08:00:00Z',
    discountMinor: 60000,
    paymentRef: '320027150501',
    listPriceMinor: 450000,
    refundedMinor: 100000,
    refunds: [{ amountMinor: 100000, refundedAt: '2026-11-03T08:00:00Z' }],
    ...extra,
  });

  it('lists payments with refunds and links receipts only for those that went through', async () => {
    mockApi({
      ...signedIn(),
      'GET /api/subscriptions/payments': () =>
        jsonResponse([mine(), mine({ id: 'tx-2', status: 'FAILED', refunds: [], refundedMinor: 0 })]),
    });
    renderRoute('/account/payments');
    expect(await screen.findByRole('heading', { name: 'Payments and receipts' })).toBeInTheDocument();
    expect(await screen.findByText('Partly refunded')).toBeInTheDocument();
    expect(screen.getByText(/LKR 1,000.00 refunded on/)).toBeInTheDocument();
    expect(screen.getByText('Didn’t go through')).toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: 'Receipt' })).toHaveLength(1);
    expect(screen.getByRole('link', { name: 'Receipt' })).toHaveAttribute('href', '/account/payments/tx-1/receipt');
  });

  it('shows a printable receipt with the sale, the code and refunds', async () => {
    const print = vi.fn();
    vi.stubGlobal('print', print);
    mockApi({
      ...signedIn(),
      'GET /api/subscriptions/checkout/tx-1': () => jsonResponse(mine({ listPriceMinor: 500000 })),
    });
    renderRoute('/account/payments/tx-1/receipt');
    expect(await screen.findByRole('heading', { name: 'Receipt' })).toBeInTheDocument();
    expect(screen.getByText('PayHere · payment 320027150501')).toBeInTheDocument();
    const table = screen.getByRole('table', { name: 'What was paid' });
    expect(within(table).getByText('LKR 5,000.00')).toBeInTheDocument();
    expect(within(table).getByText('Sale price').nextSibling).toHaveTextContent('−LKR 500.00');
    expect(within(table).getByText('Discount code').nextSibling).toHaveTextContent('−LKR 600.00');
    expect(within(table).getByText('LKR 3,900.00')).toBeInTheDocument();
    expect(within(table).getByText('Net paid').nextSibling).toHaveTextContent('LKR 2,900.00');
    await userEvent.click(screen.getByRole('button', { name: 'Print or save as PDF' }));
    expect(print).toHaveBeenCalled();
  });

  it('has no receipt for a payment that didn’t go through', async () => {
    mockApi({ ...signedIn(), 'GET /api/subscriptions/checkout/tx-1': () => jsonResponse(mine({ status: 'FAILED' })) });
    renderRoute('/account/payments/tx-1/receipt');
    expect(await screen.findByText('This payment didn’t go through, so there’s no receipt for it.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Print or save as PDF' })).not.toBeInTheDocument();
  });
});
