import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { jsonResponse, mockApi, renderRoute, signedIn } from '@/test/render';
import { clearSession } from '@/lib/auth/session';
import { parsePrice, planCode } from '@/features/admin/billing/api';
import { formatPrice, formatPriceShort, perMonth } from './api';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  clearSession();
});

const plans = [
  { id: 'p1', name: '1 month', description: '', durationMonths: 1, priceMinor: 150000, currency: 'LKR' },
  { id: 'p3', name: '3 months', description: 'Most popular', durationMonths: 3, priceMinor: 390000, currency: 'LKR' },
  { id: 'p12', name: '12 months', description: '', durationMonths: 12, priceMinor: 1200000, currency: 'LKR' },
];
const catalogue = (testPayments = false) => ({ provider: testPayments ? 'TEST' : 'PAYHERE', testPayments, plans });

const payment = (overrides: Record<string, unknown> = {}) => ({
  id: 'tx-1',
  planName: '3 months',
  durationMonths: 3,
  amountMinor: 390000,
  currency: 'LKR',
  provider: 'TEST',
  status: 'PENDING',
  failureReason: null,
  createdAt: '2026-10-26T08:00:00Z',
  premiumStartsAt: null,
  premiumEndsAt: null,
  ...overrides,
});

describe('money helpers', () => {
  it('formats exact amounts from the smallest unit', () => {
    expect(formatPrice(450000, 'LKR')).toBe('LKR 4,500.00');
    expect(formatPrice(1205, 'USD')).toBe('USD 12.05');
    expect(formatPriceShort(450000, 'LKR')).toBe('LKR 4,500');
    expect(formatPriceShort(450050, 'LKR')).toBe('LKR 4,500.50');
    expect(perMonth({ priceMinor: 1200000, durationMonths: 12 })).toBe(100000);
  });

  it('reads prices typed by an administrator', () => {
    expect(parsePrice('4500')).toBe(450000);
    expect(parsePrice('4,500.5')).toBe(450050);
    expect(parsePrice(' 12.05 ')).toBe(1205);
    expect(parsePrice('12.345')).toBeNull();
    expect(parsePrice('-3')).toBeNull();
    expect(parsePrice('')).toBeNull();
    expect(planCode('3 Months (Best!)')).toMatch(/^3-months-best-[a-z0-9]{1,4}$/);
    expect(planCode('රු')).toMatch(/^plan-/);
  });
});

describe('Premium plans page', () => {
  it('shows the plans on sale with prices, the monthly price and the best value', async () => {
    mockApi({ 'GET /api/plans': () => jsonResponse(catalogue()) });
    renderRoute('/plans');
    expect(await screen.findByRole('heading', { name: 'Keep practising without limits' })).toBeInTheDocument();
    expect(screen.getByText('LKR 1,500')).toBeInTheDocument();
    expect(screen.getByText('LKR 12,000')).toBeInTheDocument();
    // 12 months is LKR 1,000 a month against LKR 1,500: the best value, saving 33%.
    const year = screen.getByRole('heading', { name: '12 months' }).closest('li') as HTMLElement;
    expect(within(year).getByText('Best value')).toBeInTheDocument();
    expect(within(year).getByText('Save 33% a month')).toBeInTheDocument();
    expect(within(year).getByText(/LKR 1,000.00 a month/)).toBeInTheDocument();
    expect(within(year).getByRole('link', { name: /Choose 12 months/ })).toHaveAttribute('href', '/checkout/p12');
    expect(screen.queryByText('Test payments')).not.toBeInTheDocument();
  });

  it('says so when payments are the test stand-in', async () => {
    mockApi({ 'GET /api/plans': () => jsonResponse(catalogue(true)) });
    renderRoute('/plans');
    expect(await screen.findByText('Test payments')).toBeInTheDocument();
    expect(screen.getByText(/no money moves/)).toBeInTheDocument();
  });

  it('has a friendly empty state', async () => {
    mockApi({ 'GET /api/plans': () => jsonResponse({ provider: 'PAYHERE', testPayments: false, plans: [] }) });
    renderRoute('/plans');
    expect(await screen.findByRole('heading', { name: 'No plans on sale right now' })).toBeInTheDocument();
  });
});

describe('checkout', () => {
  it('prefills the student’s details, checks them, and sends PayHere a signed form', async () => {
    const submit = vi.spyOn(HTMLFormElement.prototype, 'submit').mockImplementation(() => {});
    const fetchMock = mockApi({
      ...signedIn(),
      'GET /api/plans': () => jsonResponse(catalogue()),
      'POST /api/subscriptions/checkout': () =>
        jsonResponse(
          {
            transactionId: 'tx-1',
            provider: 'PAYHERE',
            status: 'PENDING',
            action: 'https://sandbox.payhere.lk/pay/checkout',
            method: 'POST',
            fields: { merchant_id: '1211149', order_id: 'tx-1', amount: '3900.00', currency: 'LKR', hash: 'ABC123' },
          },
          201,
        ),
    });
    renderRoute('/checkout/p3');
    expect(await screen.findByRole('heading', { name: 'Get Premium' })).toBeInTheDocument();
    expect(await screen.findByLabelText('First name')).toHaveValue('Amaya');
    expect(screen.getByLabelText('Last name')).toHaveValue('Perera');
    expect(screen.getByLabelText('Email')).toHaveValue('amaya@example.com');
    expect(screen.getByLabelText('Country')).toHaveValue('Sri Lanka');

    const user = userEvent.setup();
    const pay = screen.getByRole('button', { name: 'Pay LKR 3,900.00 with PayHere' });
    await user.click(pay);
    expect(await screen.findByText('Enter a phone number, for example +94 77 123 4567.')).toBeInTheDocument();
    expect(screen.getAllByText('Required.')).toHaveLength(2); // address and city
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('/subscriptions/checkout'))).toBe(false);

    await user.type(screen.getByLabelText('Phone'), '0771234567');
    await user.type(screen.getByLabelText('Address'), '12 Galle Road');
    await user.type(screen.getByLabelText('City'), 'Colombo');
    await user.click(pay);

    await waitFor(() => expect(submit).toHaveBeenCalledTimes(1));
    const call = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST' && String(init.body).includes('planId'));
    expect(new Headers(call?.[1]?.headers).get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/);
    expect(JSON.parse(String(call?.[1]?.body))).toEqual({
      planId: 'p3',
      customer: {
        firstName: 'Amaya',
        lastName: 'Perera',
        email: 'amaya@example.com',
        phone: '0771234567',
        address: '12 Galle Road',
        city: 'Colombo',
        country: 'Sri Lanka',
      },
    });
    const form = document.querySelector('form[action="https://sandbox.payhere.lk/pay/checkout"]') as HTMLFormElement;
    expect(form.method).toBe('post');
    expect((form.elements.namedItem('hash') as HTMLInputElement).value).toBe('ABC123');
    expect((form.elements.namedItem('order_id') as HTMLInputElement).value).toBe('tx-1');
  });

  it('runs the labelled test payment through to Premium', async () => {
    let status = 'PENDING';
    const fetchMock = mockApi({
      ...signedIn(),
      'GET /api/plans': () => jsonResponse(catalogue(true)),
      'POST /api/subscriptions/checkout': () =>
        jsonResponse(
          {
            transactionId: 'tx-1',
            provider: 'TEST',
            status: 'PENDING',
            action: 'https://x/checkout/test/tx-1',
            method: 'GET',
            fields: {},
          },
          201,
        ),
      'GET /api/subscriptions/checkout/tx-1': () =>
        jsonResponse(
          payment(
            status === 'SUCCEEDED'
              ? { status, premiumStartsAt: '2026-10-26T08:01:00Z', premiumEndsAt: '2027-01-26T08:01:00Z' }
              : { status },
          ),
        ),
      'POST /api/payments/test/tx-1': () => {
        status = 'SUCCEEDED';
        return jsonResponse({ result: 'ACTIVATED' });
      },
    });
    renderRoute('/checkout/p3');
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText('Phone'), '0771234567');
    await user.type(screen.getByLabelText('Address'), '12 Galle Road');
    await user.type(screen.getByLabelText('City'), 'Colombo');
    await user.click(screen.getByRole('button', { name: 'Continue to the test payment' }));

    expect(await screen.findByRole('heading', { name: 'Test payment' })).toBeInTheDocument();
    expect(screen.getByText('No money moves on this page')).toBeInTheDocument();
    expect(await screen.findByText('LKR 3,900.00')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Pay (test)' }));

    expect(await screen.findByRole('heading', { name: 'You have Premium' })).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent(/Payment of LKR 3,900.00 received for 3 months/);
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('/api/payments/test/tx-1'))).toBe(true);
  });

  it('waits for PayHere’s confirmation and reports a failed payment', async () => {
    mockApi({
      ...signedIn(),
      'GET /api/subscriptions/checkout/tx-1': () => jsonResponse(payment({ provider: 'PAYHERE' })),
      'GET /api/subscriptions/checkout/tx-2': () => jsonResponse(payment({ id: 'tx-2', provider: 'PAYHERE', status: 'FAILED' })),
    });
    renderRoute('/checkout/return?tx=tx-1');
    expect(await screen.findByRole('heading', { name: 'Confirming your payment…' })).toBeInTheDocument();
    vi.unstubAllGlobals();
    clearSession();

    mockApi({
      ...signedIn(),
      'GET /api/subscriptions/checkout/tx-2': () => jsonResponse(payment({ id: 'tx-2', provider: 'PAYHERE', status: 'FAILED' })),
    });
    renderRoute('/checkout/return?tx=tx-2');
    expect(await screen.findByRole('heading', { name: 'The payment didn’t go through' })).toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: 'Try again' })[0]).toHaveAttribute('href', '/plans');
  });

  it('tells the student nothing was charged when they cancel', async () => {
    mockApi({});
    renderRoute('/checkout/cancel?tx=tx-1');
    expect(await screen.findByRole('heading', { name: 'Payment cancelled' })).toBeInTheDocument();
    expect(screen.getByText(/Nothing was charged/)).toBeInTheDocument();
  });

  it('explains when a plan is no longer on sale', async () => {
    mockApi({ ...signedIn(), 'GET /api/plans': () => jsonResponse(catalogue()) });
    renderRoute('/checkout/gone');
    expect(await screen.findByRole('heading', { name: 'This plan isn’t on sale' })).toBeInTheDocument();
  });
});

const admin = { id: 'admin-1', roles: ['ADMIN', 'STUDENT'], displayName: 'Kani Admin', email: 'kani@example.com' };

describe('admin plans and payments', () => {
  it('lists plans and payments and creates a plan with an exact price', async () => {
    const adminPlan = {
      ...plans[0],
      code: 'monthly-ab12',
      active: true,
      displayOrder: 1,
      version: 0,
      updatedAt: '2026-10-26T08:00:00Z',
    };
    let created: unknown;
    mockApi({
      ...signedIn(admin),
      'GET /api/admin/billing/plans': () => jsonResponse([adminPlan]),
      'POST /api/admin/billing/plans': ({ body }) => {
        created = body;
        return jsonResponse({ ...adminPlan, id: 'p-new' }, 201);
      },
      'GET /api/admin/billing/payments': () =>
        jsonResponse([
          {
            id: 'tx-9',
            studentId: 'stu-1',
            planId: 'p1',
            planName: '1 month',
            durationMonths: 1,
            provider: 'PAYHERE',
            providerRef: '320027150501',
            amountMinor: 150000,
            currency: 'LKR',
            status: 'SUCCEEDED',
            failureReason: null,
            createdAt: '2026-10-26T08:00:00Z',
            updatedAt: '2026-10-26T08:00:00Z',
          },
        ]),
      'GET /api/admin/users/names': () => jsonResponse([{ id: 'stu-1', displayName: 'Nimal Perera' }]),
    });
    renderRoute('/admin/plans');
    expect(await screen.findByRole('heading', { name: 'Plans & payments' })).toBeInTheDocument();
    const plansTable = await screen.findByRole('table', { name: 'Plans' });
    expect(within(plansTable).getByText('LKR 1,500.00')).toBeInTheDocument();
    expect(within(plansTable).getByText('On sale')).toBeInTheDocument();
    const paymentsTable = await screen.findByRole('table', { name: 'Payments' });
    expect(await within(paymentsTable).findByText('Nimal Perera')).toBeInTheDocument();
    expect(within(paymentsTable).getByText('Paid')).toBeInTheDocument();
    expect(within(paymentsTable).getByText('320027150501')).toBeInTheDocument();

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'New plan' }));
    const dialog = await screen.findByRole('dialog', { name: 'New plan' });
    await user.type(within(dialog).getByLabelText('Name'), '6 months');
    await user.selectOptions(within(dialog).getByLabelText('Length'), '6');
    await user.type(within(dialog).getByLabelText('Price'), '7,200.50');
    await user.click(within(dialog).getByRole('button', { name: 'Save plan' }));

    await waitFor(() => expect(created).toBeDefined());
    expect(created).toMatchObject({
      name: '6 months',
      durationMonths: 6,
      priceMinor: 720050,
      currency: 'LKR',
      active: true,
      displayOrder: 0,
    });
    expect((created as { code: string }).code).toMatch(/^6-months-/);
  });

  it('rejects a price that isn’t one', async () => {
    mockApi({
      ...signedIn(admin),
      'GET /api/admin/billing/plans': () => jsonResponse([]),
      'GET /api/admin/billing/payments': () => jsonResponse([]),
    });
    renderRoute('/admin/plans');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'New plan' }));
    const dialog = await screen.findByRole('dialog', { name: 'New plan' });
    await user.type(within(dialog).getByLabelText('Name'), 'Monthly');
    await user.type(within(dialog).getByLabelText('Price'), '12.345');
    await user.click(within(dialog).getByRole('button', { name: 'Save plan' }));
    expect(await within(dialog).findByText('Enter a price such as 4500 or 4500.00.')).toBeInTheDocument();
  });
});
