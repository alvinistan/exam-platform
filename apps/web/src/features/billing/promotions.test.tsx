import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { jsonResponse, mockApi, renderRoute, signedIn } from '@/test/render';
import { clearSession } from '@/lib/auth/session';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  clearSession();
});

const admin = { roles: ['STUDENT', 'ADMIN'] };
const unlimited = { practice: null, mock: null, adaptive: null };
const plan = (extra: Record<string, unknown> = {}) => ({
  id: 'p3',
  name: '3 months',
  description: '',
  scopeType: 'ALL',
  categoryId: null,
  exams: [],
  durationMonths: 3,
  priceLkrMinor: 390000,
  priceUsdMinor: 1300,
  limits: unlimited,
  salePriceLkrMinor: null,
  salePriceUsdMinor: null,
  saleEndsAt: null,
  ...extra,
});
const catalogue = (plans: unknown[]) => ({ provider: 'PAYHERE', testPayments: false, plans });
const quote = (extra: Record<string, unknown> = {}) => ({
  currency: 'LKR',
  listPriceMinor: 390000,
  priceMinor: 390000,
  saleEndsAt: null,
  code: null,
  codeId: null,
  discountMinor: 0,
  totalMinor: 390000,
  ...extra,
});
const invalidCode = (message: string) =>
  jsonResponse(
    {
      type: 'about:blank',
      title: 'Validation failed',
      status: 400,
      code: 'VALIDATION_FAILED',
      detail: 'One or more fields are invalid.',
      errors: [{ field: 'code', message }],
    },
    400,
  );

describe('sale prices and offers', () => {
  it('shows the regular price crossed out while a sale is on, and charges the sale price', async () => {
    mockApi({
      'GET /api/plans': () => jsonResponse(catalogue([plan({ salePriceLkrMinor: 290000, saleEndsAt: '2030-11-30T18:30:00Z' })])),
      'GET /api/categories': () => jsonResponse([]),
    });
    renderRoute('/plans');
    const card = (await screen.findByRole('heading', { name: '3 months' })).closest('div.flex.h-full') as HTMLElement;
    expect(within(card).getByText('Sale')).toBeInTheDocument();
    expect(within(card).getByText('LKR 3,900')).toHaveClass('line-through');
    expect(within(card).getByText(/LKR 2,900/)).toBeInTheDocument();
    expect(within(card).getByText(/sale ends/)).toBeInTheDocument();
    expect(within(card).getByRole('link', { name: 'Choose 3 months, LKR 2,900.00' })).toBeInTheDocument();
  });

  it('shows free access open to everyone', async () => {
    mockApi({
      'GET /api/plans': () => jsonResponse(catalogue([plan()])),
      'GET /api/categories': () => jsonResponse([]),
      'GET /api/plans/offers': () =>
        jsonResponse([
          {
            id: 'c1',
            name: 'Exam week',
            scopeType: 'EXAM',
            exams: [{ examId: 'ex-sci', title: 'Science', slug: 'ol-science' }],
            limits: { practice: null, mock: 2, adaptive: 0 },
            endsAt: '2030-11-30T18:30:00Z',
          },
        ]),
    });
    renderRoute('/plans');
    const offer = await screen.findByText('Exam week');
    const box = offer.closest('[role="alert"], [role="status"], div') as HTMLElement;
    expect(box.parentElement).toHaveTextContent(
      /Free for every student until .*: Science\. Practice: unlimited · Mock exams: 2 attempts/,
    );
    expect(screen.getByRole('link', { name: 'Sign in to use it' })).toBeInTheDocument();
  });
});

describe('discount codes at checkout', () => {
  it('applies a code checked by the server and sends it with the payment', async () => {
    vi.spyOn(HTMLFormElement.prototype, 'submit').mockImplementation(() => {});
    let checkoutBody: unknown;
    mockApi({
      ...signedIn(),
      'GET /api/plans': () => jsonResponse(catalogue([plan()])),
      'POST /api/subscriptions/quote': ({ body }) => {
        const code = (body as { code: string }).code.trim().toUpperCase();
        return code === 'LAUNCH20'
          ? jsonResponse(quote({ code: 'LAUNCH20', codeId: 'd1', discountMinor: 78000, totalMinor: 312000 }))
          : invalidCode('This code has expired.');
      },
      'POST /api/subscriptions/checkout': ({ body }) => {
        checkoutBody = body;
        return jsonResponse(
          {
            transactionId: 'tx-1',
            provider: 'PAYHERE',
            status: 'PENDING',
            action: 'https://sandbox.payhere.lk/pay/checkout',
            method: 'POST',
            fields: {},
          },
          201,
        );
      },
    });
    renderRoute('/checkout/p3');
    const user = userEvent.setup();
    const box = await screen.findByLabelText('Discount code');
    await user.type(box, 'old10');
    await user.click(screen.getByRole('button', { name: 'Apply' }));
    expect(await screen.findByText('This code has expired.')).toBeInTheDocument();

    await user.clear(box);
    await user.type(box, 'launch20');
    await user.click(screen.getByRole('button', { name: 'Apply' }));
    expect(await screen.findByText('LAUNCH20 applied')).toBeInTheDocument();
    expect(screen.getByText('Code LAUNCH20')).toBeInTheDocument();
    expect(screen.getByText('−LKR 780.00')).toBeInTheDocument();

    await user.type(screen.getByLabelText('Phone'), '0771234567');
    await user.type(screen.getByLabelText('Address'), '12 Galle Road');
    await user.type(screen.getByLabelText('City'), 'Colombo');
    await user.click(screen.getByRole('button', { name: 'Pay LKR 3,120.00 with PayHere' }));
    await waitFor(() => expect(checkoutBody).toMatchObject({ planId: 'p3', currency: 'LKR', code: 'LAUNCH20' }));
  });

  it('starts the plan without payment details when the code makes it free', async () => {
    let checkoutBody: unknown;
    mockApi({
      ...signedIn(),
      'GET /api/plans': () => jsonResponse(catalogue([plan()])),
      'POST /api/subscriptions/quote': () =>
        jsonResponse(quote({ code: 'FREEMONTH', codeId: 'd2', discountMinor: 390000, totalMinor: 0 })),
      'POST /api/subscriptions/checkout': ({ body }) => {
        checkoutBody = body;
        return jsonResponse(
          {
            transactionId: 'tx-free',
            provider: 'FREE',
            status: 'SUCCEEDED',
            action: 'https://x/checkout/return?tx=tx-free',
            method: 'GET',
            fields: {},
          },
          201,
        );
      },
      'GET /api/subscriptions/checkout/tx-free': () =>
        jsonResponse({
          id: 'tx-free',
          planName: '3 months',
          durationMonths: 3,
          amountMinor: 0,
          currency: 'LKR',
          provider: 'FREE',
          status: 'SUCCEEDED',
          failureReason: null,
          createdAt: '2026-11-09T08:00:00Z',
          premiumStartsAt: '2026-11-09T08:00:00Z',
          premiumEndsAt: '2027-02-09T08:00:00Z',
          discountMinor: 390000,
        }),
    });
    renderRoute('/checkout/p3');
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText('Discount code'), 'FREEMONTH');
    await user.click(screen.getByRole('button', { name: 'Apply' }));
    expect(await screen.findByRole('heading', { name: 'Nothing to pay' })).toBeInTheDocument();
    expect(screen.queryByLabelText('Phone')).not.toBeInTheDocument();
    expect(screen.getByText('Free')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Start my plan' }));
    expect(await screen.findByRole('heading', { name: 'Your plan has started' })).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('3 months is free with your discount code.');
    expect(checkoutBody).toEqual({ planId: 'p3', currency: 'LKR', code: 'FREEMONTH', customer: null });
  });
});

describe('admin promotions', () => {
  const adminPlan = {
    id: 'p3',
    code: 'three-ab12',
    name: '3 months',
    description: '',
    scopeType: 'ALL',
    categoryId: null,
    examIds: [],
    durationMonths: 3,
    priceLkrMinor: 390000,
    priceUsdMinor: 1300,
    limits: unlimited,
    active: true,
    displayOrder: 1,
    version: 0,
    updatedAt: '2026-11-01T00:00:00Z',
    sale: { priceLkrMinor: null, priceUsdMinor: null, startsAt: null, endsAt: null },
  };
  const baseRoutes = {
    'GET /api/admin/billing/plans': () => jsonResponse([adminPlan]),
    'GET /api/admin/catalog/exams': () =>
      jsonResponse({
        items: [{ id: 'ex-sci', title: 'Science', categoryId: 'cat-ol', status: 'PUBLISHED' }],
        page: { number: 0, size: 100, totalElements: 1, totalPages: 1 },
      }),
    'GET /api/admin/catalog/categories': () => jsonResponse([]),
  };

  it('lists codes with their use, and creates a percentage code', async () => {
    let created: unknown;
    mockApi({
      ...signedIn(admin),
      ...baseRoutes,
      'GET /api/admin/billing/campaigns': () => jsonResponse([]),
      'GET /api/admin/billing/discount-codes': () =>
        jsonResponse([
          {
            id: 'd1',
            code: 'LAUNCH20',
            description: 'Facebook post',
            percentOff: 20,
            amountOffLkrMinor: null,
            amountOffUsdMinor: null,
            allPlans: true,
            planIds: [],
            startsAt: '2026-11-01T00:00:00Z',
            endsAt: '2030-12-01T00:00:00Z',
            maxUses: 100,
            maxUsesPerStudent: 1,
            active: true,
            version: 0,
            uses: 12,
          },
        ]),
      'POST /api/admin/billing/discount-codes': ({ body }) => {
        created = body;
        return jsonResponse({}, 201);
      },
    });
    renderRoute('/admin/promotions');
    expect(await screen.findByRole('heading', { name: 'Promotions' })).toBeInTheDocument();
    const table = await screen.findByRole('table', { name: 'Discount codes' });
    expect(within(table).getByText('LAUNCH20')).toBeInTheDocument();
    expect(within(table).getByText('20% off')).toBeInTheDocument();
    expect(within(table).getByText('of 100')).toBeInTheDocument();
    expect(within(table).getByText('Working')).toBeInTheDocument();
    expect(await screen.findByText('No campaigns yet')).toBeInTheDocument();

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'New code' }));
    const dialog = await screen.findByRole('dialog', { name: 'New discount code' });
    await user.type(within(dialog).getByLabelText('Code'), 'exam-week');
    await user.type(within(dialog).getByLabelText('Percent off'), '15');
    await user.click(within(dialog).getByRole('checkbox', { name: 'Works on every plan' }));
    await user.click(within(dialog).getByRole('button', { name: 'Save code' }));
    expect(await within(dialog).findByText('Choose at least one plan.')).toBeInTheDocument();
    expect(within(dialog).getByText('Choose when the code stops working.')).toBeInTheDocument();

    await user.click(within(dialog).getByRole('checkbox', { name: /3 months/ }));
    await user.type(within(dialog).getByLabelText('Works until'), '2030-12-31T23:59');
    await user.type(within(dialog).getByLabelText('Uses in total (optional)'), '50');
    await user.click(within(dialog).getByRole('button', { name: 'Save code' }));
    await waitFor(() => expect(created).toBeDefined());
    expect(created).toMatchObject({
      code: 'EXAM-WEEK',
      percentOff: 15,
      amountOffLkrMinor: null,
      allPlans: false,
      planIds: ['p3'],
      maxUses: 50,
      maxUsesPerStudent: 1,
      active: true,
    });
    expect(new Date((created as { endsAt: string }).endsAt).getFullYear()).toBe(2030);
  });

  it('gives chosen students free access, and ends a campaign', async () => {
    let created: unknown;
    let ended = false;
    const running = {
      id: 'c1',
      name: 'Top students',
      scopeType: 'ALL',
      categoryId: null,
      examIds: [],
      limits: unlimited,
      audience: 'SELECTED',
      startsAt: '2026-11-01T00:00:00Z',
      endsAt: null,
      durationMonths: 3,
      endedAt: null,
      createdBy: 'admin-1',
      createdAt: '2026-11-01T00:00:00Z',
      students: 4,
    };
    mockApi({
      ...signedIn(admin),
      ...baseRoutes,
      'GET /api/admin/billing/discount-codes': () => jsonResponse([]),
      'GET /api/admin/billing/campaigns': () => jsonResponse([ended ? { ...running, endedAt: '2026-11-09T09:00:00Z' } : running]),
      'GET /api/admin/users': ({ url }) =>
        jsonResponse({
          items: url.searchParams.get('q')
            ? [{ id: 'stu-1', email: 'nimal@example.lk', displayName: 'Nimal Perera', roles: ['STUDENT'], status: 'ACTIVE' }]
            : [],
          page: { page: 0, size: 25, totalElements: 1, totalPages: 1 },
        }),
      'POST /api/admin/billing/campaigns': ({ body }) => {
        created = body;
        return jsonResponse(running, 201);
      },
      'POST /api/admin/billing/campaigns/c1/end': () => {
        ended = true;
        return jsonResponse({ ...running, endedAt: '2026-11-09T09:00:00Z' });
      },
    });
    renderRoute('/admin/promotions');
    const table = await screen.findByRole('table', { name: 'Free access campaigns' });
    expect(within(table).getByText('Top students')).toBeInTheDocument();
    expect(within(table).getByText('4 students')).toBeInTheDocument();
    expect(within(table).getByText('Running')).toBeInTheDocument();

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'New campaign' }));
    const dialog = await screen.findByRole('dialog', { name: 'New free access campaign' });
    await user.type(within(dialog).getByLabelText('Name'), 'Scholarship');
    await user.click(within(dialog).getByRole('radio', { name: 'One exam' }));
    await user.selectOptions(await within(dialog).findByLabelText('Exam'), 'ex-sci');
    await user.selectOptions(within(dialog).getByLabelText('Mock exams: access'), 'COUNT');
    await user.type(within(dialog).getByLabelText('Mock exams: number of attempts'), '5');
    await user.click(within(dialog).getByRole('radio', { name: 'Students I choose' }));
    await user.click(within(dialog).getByRole('button', { name: 'Start campaign' }));
    expect(await within(dialog).findByText('Add at least one student.')).toBeInTheDocument();

    await user.selectOptions(within(dialog).getByLabelText('How long each student has it'), '6');
    await user.type(within(dialog).getByLabelText('Students'), 'nimal');
    await user.click(await within(dialog).findByRole('button', { name: 'Add Nimal Perera' }));
    expect(within(dialog).getByRole('list', { name: 'Chosen students' })).toHaveTextContent('Nimal Perera');
    await user.click(within(dialog).getByRole('button', { name: 'Start campaign' }));
    await waitFor(() => expect(created).toBeDefined());
    expect(created).toEqual({
      name: 'Scholarship',
      scopeType: 'EXAM',
      categoryId: null,
      examIds: ['ex-sci'],
      practiceLimit: null,
      mockLimit: 5,
      adaptiveLimit: null,
      audience: 'SELECTED',
      startsAt: null,
      endsAt: null,
      durationMonths: 6,
      studentIds: ['stu-1'],
    });

    await user.click(within(table).getByRole('button', { name: 'End' }));
    const confirm = await screen.findByRole('dialog', { name: 'End Top students?' });
    await user.click(within(confirm).getByRole('button', { name: 'End campaign' }));
    expect(await within(table).findByText('Ended early')).toBeInTheDocument();
  });
});
