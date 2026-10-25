/** Catalogue fixtures shaped like catalog-service responses (contracts/openapi/catalog-service.yaml). */
export const categoryNursing = {
  id: '0190d5e2-0000-7000-8000-0000000000c1',
  name: 'Nursing',
  slug: 'nursing',
  description: null,
  displayOrder: 1,
  examCount: 1,
};

export const categoryIt = {
  id: '0190d5e2-0000-7000-8000-0000000000c2',
  name: 'IT certifications',
  slug: 'it-certifications',
  description: null,
  displayOrder: 2,
  examCount: 0,
};

export function examSummary(overrides: Record<string, unknown> = {}) {
  return {
    id: '0190d5e2-0000-7000-8000-000000000001',
    title: 'Nursing Licensure Review',
    slug: 'nursing-licensure',
    categoryId: categoryNursing.id,
    shortDescription: 'Adult health, pharmacology and safe practice.',
    coverUrl: null,
    featured: true,
    displayOrder: 1,
    modes: ['PRACTICE', 'MOCK'],
    ...overrides,
  };
}

export function examDetail(overrides: Record<string, unknown> = {}) {
  return {
    ...examSummary(),
    category: { id: categoryNursing.id, name: 'Nursing', slug: 'nursing' },
    instructions: 'Read each question carefully.\nSome questions have more than one answer.',
    seo: { title: 'Nursing Licensure Review', description: 'Adult health, pharmacology and safe practice.' },
    subjects: [
      {
        id: 's1',
        title: 'Pharmacology',
        modules: [
          {
            id: 'm1',
            title: 'Drug calculations',
            topics: [
              { id: 't1', title: 'Dosage by weight', displayOrder: 0 },
              { id: 't2', title: 'IV flow rates', displayOrder: 1 },
            ],
          },
        ],
      },
    ],
    modeSettings: [
      {
        mode: 'PRACTICE',
        enabled: true,
        defaultCount: 10,
        minCount: 5,
        maxCount: 50,
        timeLimitSeconds: null,
        perQuestionSeconds: null,
        feedbackTiming: 'IMMEDIATE',
        negativeMark: 0,
        passMarkPct: null,
        allowTopicChoice: true,
        maxRetakes: null,
      },
      {
        mode: 'MOCK',
        enabled: true,
        defaultCount: 75,
        minCount: 75,
        maxCount: 75,
        timeLimitSeconds: 5400,
        perQuestionSeconds: null,
        feedbackTiming: 'AFTER_SUBMIT',
        negativeMark: 0.25,
        passMarkPct: 60,
        allowTopicChoice: false,
        maxRetakes: null,
      },
    ],
    ...overrides,
  };
}

export const page = (items: unknown[], totalPages = items.length ? 1 : 0) => ({
  items,
  page: { page: 0, size: 25, totalElements: items.length, totalPages },
});
