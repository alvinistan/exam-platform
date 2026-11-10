import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { jsonResponse, mockApi, renderRoute, signedIn } from '@/test/render';
import { clearSession } from '@/lib/auth/session';
import { parsePrice, planCode } from '@/features/admin/billing/api';
import { describeLimits, formatPrice, formatPriceShort, perMonth } from './api';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  clearSession();
});

const OL = 'cat-ol';
const science = { examId: 'ex-sci', title: 'Science', slug: 'ol-science' };
const maths = { examId: 'ex-maths', title: 'Mathematics', slug: 'ol-maths' };
const unlimited = { practice: null, mock: null, adaptive: null };
const plan = (id: string, months: number, lkr: number | null, usd: number | null, extra: Record<string, unknown> = {}) => ({
  id,
  name: months === 12 ? '12 months' : months === 1 ? '1 month' : `${months} months`,
  description: '',
  scopeType: 'ALL',
  categoryId: null,
  exams: [],
  durationMonths: months,
  priceLkrMinor: lkr,
  priceUsdMinor: usd,
  limits: unlimited,
  salePriceLkrMinor: null,
  salePriceUsdMinor: null,
  saleEndsAt: null,
  ...extra,
});
const plans = [
  plan('p1', 1, 150000, 500),
  plan('p3', 3, 390000, 1300, { description: 'Most popular' }),
  plan('p12', 12, 1200000, null),
  plan('sci1', 1, 300000, 1500, {
    name: 'Science – 1 month',
    scopeType: 'EXAM',
    exams: [science],
    limits: { practice: null, mock: 8, adaptive: 0 },
  }),
  plan('ol1', 1, 600000, 2000, {
    name: 'O/L – 1 month',
    scopeType: 'BUNDLE',
    categoryId: OL,
    exams: [science, maths],
    limits: { practice: null, mock: 5, adaptive: 15 },
  }),
];
const catalogue = (testPayments = false, list: unknown[] = plans) => ({
  provider: testPayments ? 'TEST' : 'PAYHERE',
  testPayments,
  plans: list,
});

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
  discountMinor: 0,
  ...overrides,
});

describe('money helpers', () => {
  it('formats exact amounts from the smallest unit', () => {
    expect(formatPrice(450000, 'LKR')).toBe('LKR 4,500.00');
    expect(formatPrice(1205, 'USD')).toBe('USD 12.05');
    expect(formatPriceShort(450000, 'LKR')).toBe('LKR 4,500');
    expect(formatPriceShort(450050, 'LKR')).toBe('LKR 4,500.50');
    expect(perMonth(1200000, 12)).toBe(100000);
    expect(describeLimits({ practice: null, mock: 8, adaptive: 0 })).toEqual(['Practice: unlimited', 'Mock exams: 8 attempts']);
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

describe('plans page', () => {
  it('groups bundles, single subjects and everything, with limits, prices and the best value', async () => {
    mockApi({
      'GET /api/plans': () => jsonResponse(catalogue()),
      'GET /api/categories': () => jsonResponse([{ id: OL, name: 'GCE Ordinary Level', slug: 'ol' }]),
    });
    renderRoute('/plans');
    expect(await screen.findByRole('heading', { name: 'Choose a plan' })).toBeInTheDocument();

    const bundles = screen.getByRole('region', { name: 'Subject bundles' });
    expect(await within(bundles).findByRole('heading', { name: 'GCE Ordinary Level' })).toBeInTheDocument();
    expect(within(bundles).getByText('2 subjects: Science, Mathematics')).toBeInTheDocument();
    expect(within(bundles).getByText('Mock exams: 5 attempts per subject')).toBeInTheDocument();

    const singles = screen.getByRole('region', { name: 'Single subjects' });
    expect(within(singles).getByRole('heading', { name: 'Science' })).toBeInTheDocument();
    expect(within(singles).getByText('Mock exams: 8 attempts')).toBeInTheDocument();
    expect(within(singles).queryByText(/Adaptive/)).not.toBeInTheDocument();

    const everything = screen.getByRole('region', { name: 'Everything' });
    expect(within(everything).getByText('LKR 1,500')).toBeInTheDocument();
    // 12 months is LKR 1,000 a month against LKR 1,500: the best value, saving 33%.
    const year = within(everything).getByRole('heading', { name: '12 months' }).closest('li') as HTMLElement;
    expect(within(year).getByText('Best value')).toBeInTheDocument();
    expect(within(year).getByText('Save 33% a month')).toBeInTheDocument();
    expect(within(year).getByRole('link', { name: /Choose 12 months/ })).toHaveAttribute('href', '/checkout/p12?currency=LKR');
    expect(screen.queryByText('Test payments')).not.toBeInTheDocument();

    // In USD the 12-month plan isn't sold; the others show dollar prices.
    await userEvent.setup().click(screen.getByRole('radio', { name: 'USD ($)' }));
    expect(within(year).getByText('Not sold in USD. Switch currency above.')).toBeInTheDocument();
    expect(within(singles).getByText('USD 15')).toBeInTheDocument();
    expect(within(singles).getByRole('link', { name: /Choose Science – 1 month/ })).toHaveAttribute(
      'href',
      '/checkout/sci1?currency=USD',
    );
  });

  it('narrows to the plans for one exam', async () => {
    const fetchMock = mockApi({
      'GET /api/plans': () => jsonResponse(catalogue(false, plans.slice(3))),
      'GET /api/categories': () => jsonResponse([]),
    });
    renderRoute('/plans?exam=ex-sci');
    expect(await screen.findByRole('heading', { name: 'Plans for Science' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'See all plans' })).toHaveAttribute('href', '/plans');
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('/api/plans?examId=ex-sci'))).toBe(true);
  });

  it('says so when payments are the test stand-in', async () => {
    mockApi({ 'GET /api/plans': () => jsonResponse(catalogue(true)), 'GET /api/categories': () => jsonResponse([]) });
    renderRoute('/plans');
    expect(await screen.findByText('Test payments')).toBeInTheDocument();
    expect(screen.getByText(/no money moves/)).toBeInTheDocument();
  });

  it('has a friendly empty state', async () => {
    mockApi({
      'GET /api/plans': () => jsonResponse({ provider: 'PAYHERE', testPayments: false, plans: [] }),
      'GET /api/categories': () => jsonResponse([]),
    });
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
      currency: 'LKR',
      code: null,
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

    expect(await screen.findByRole('heading', { name: 'Your plan has started' })).toBeInTheDocument();
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
  it('lists plans and creates a plan with an exact price', async () => {
    const adminPlan = {
      id: 'sci1',
      name: 'Science – 1 month',
      description: '',
      scopeType: 'EXAM',
      categoryId: OL,
      examIds: ['ex-sci'],
      durationMonths: 1,
      priceLkrMinor: 300000,
      priceUsdMinor: 1500,
      limits: { practice: null, mock: 8, adaptive: 0 },
      code: 'monthly-ab12',
      active: true,
      displayOrder: 1,
      version: 0,
      updatedAt: '2026-10-26T08:00:00Z',
      sale: { priceLkrMinor: null, priceUsdMinor: null, startsAt: null, endsAt: null },
    };
    let created: unknown;
    mockApi({
      ...signedIn(admin),
      'GET /api/admin/billing/plans': () => jsonResponse([adminPlan]),
      'GET /api/admin/catalog/exams': () =>
        jsonResponse({
          items: [
            { id: 'ex-sci', title: 'Science', categoryId: OL, status: 'PUBLISHED' },
            { id: 'ex-maths', title: 'Mathematics', categoryId: OL, status: 'PUBLISHED' },
            { id: 'ex-hist', title: 'History', categoryId: OL, status: 'DRAFT' },
          ],
          page: { number: 0, size: 100, totalElements: 3, totalPages: 1 },
        }),
      'GET /api/admin/catalog/categories': () => jsonResponse([{ id: OL, name: 'GCE Ordinary Level', slug: 'ol' }]),
      'POST /api/admin/billing/plans': ({ body }) => {
        created = body;
        return jsonResponse({ ...adminPlan, id: 'p-new' }, 201);
      },
    });
    renderRoute('/admin/plans');
    expect(await screen.findByRole('heading', { level: 1, name: 'Plans' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'payments and refunds' })).toHaveAttribute('href', '/admin/payments');
    const plansTable = await screen.findByRole('table', { name: 'Plans' });
    expect(within(plansTable).getByText('LKR 3,000.00')).toBeInTheDocument();
    expect(within(plansTable).getByText('USD 15.00')).toBeInTheDocument();
    expect(await within(plansTable).findByText('Science')).toBeInTheDocument();
    expect(within(plansTable).getByText('Mock exams: 8 attempts')).toBeInTheDocument();
    expect(within(plansTable).getByText('On sale')).toBeInTheDocument();

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'New plan' }));
    const dialog = await screen.findByRole('dialog', { name: 'New plan' });
    await user.type(within(dialog).getByLabelText('Name'), 'O/L bundle 6 months');
    await user.click(within(dialog).getByRole('radio', { name: 'A bundle of exams' }));
    await user.selectOptions(within(dialog).getByLabelText('Category'), OL);
    await user.click(await within(dialog).findByRole('checkbox', { name: 'Science' }));
    await user.click(within(dialog).getByRole('checkbox', { name: 'Mathematics' }));
    expect(within(dialog).getByText('(2 chosen)')).toBeInTheDocument();
    await user.selectOptions(within(dialog).getByLabelText('Length'), '6');
    await user.type(within(dialog).getByLabelText('Price in LKR'), '7,200.50');
    await user.type(within(dialog).getByLabelText('Price in USD'), '50');
    await user.selectOptions(within(dialog).getByLabelText('Mock exams: access'), 'COUNT');
    await user.type(within(dialog).getByLabelText('Mock exams: number of attempts'), '8');
    await user.selectOptions(within(dialog).getByLabelText('Adaptive exams: access'), 'NONE');
    await user.click(within(dialog).getByRole('button', { name: 'Save plan' }));

    await waitFor(() => expect(created).toBeDefined());
    expect(created).toMatchObject({
      name: 'O/L bundle 6 months',
      scopeType: 'BUNDLE',
      categoryId: OL,
      examIds: ['ex-sci', 'ex-maths'],
      durationMonths: 6,
      priceLkrMinor: 720050,
      priceUsdMinor: 5000,
      practiceLimit: null,
      mockLimit: 8,
      adaptiveLimit: 0,
      active: true,
      displayOrder: 0,
    });
    expect((created as { code: string }).code).toMatch(/^o-l-bundle-6-months-/);
  });

  it('rejects a price that isn’t one', async () => {
    mockApi({
      ...signedIn(admin),
      'GET /api/admin/billing/plans': () => jsonResponse([]),
      'GET /api/admin/billing/payments': () => jsonResponse([]),
      'GET /api/admin/catalog/exams': () =>
        jsonResponse({ items: [], page: { number: 0, size: 100, totalElements: 0, totalPages: 0 } }),
      'GET /api/admin/catalog/categories': () => jsonResponse([]),
    });
    renderRoute('/admin/plans');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'New plan' }));
    const dialog = await screen.findByRole('dialog', { name: 'New plan' });
    await user.type(within(dialog).getByLabelText('Name'), 'Monthly');
    await user.type(within(dialog).getByLabelText('Price in LKR'), '12.345');
    await user.selectOptions(within(dialog).getByLabelText('Practice: access'), 'NONE');
    await user.selectOptions(within(dialog).getByLabelText('Mock exams: access'), 'NONE');
    await user.selectOptions(within(dialog).getByLabelText('Adaptive exams: access'), 'NONE');
    await user.click(within(dialog).getByRole('button', { name: 'Save plan' }));
    expect(await within(dialog).findByText('Enter a price such as 3000 or 3000.00.')).toBeInTheDocument();
    expect(within(dialog).getByText('Choose the exam.')).toBeInTheDocument();
    expect(within(dialog).getByText('Include at least one exam type.')).toBeInTheDocument();
  });
});
