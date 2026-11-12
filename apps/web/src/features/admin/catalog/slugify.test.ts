import { slugify } from './api';

describe('slugify', () => {
  it.each([
    ['IELTS Academic', 'ielts-academic'],
    ['O/L Science Revision', 'ol-science-revision'],
    ['G.C.E. Ordinary Level', 'gce-ordinary-level'],
    ['A/L Combined Maths', 'al-combined-maths'],
    ["Writer's guide", 'writers-guide'],
    ['Grade 5 / Scholarship', 'grade-5-scholarship'],
    ['  Café — Résumé  ', 'cafe-resume'],
  ])('%s → %s', (title, slug) => {
    expect(slugify(title)).toBe(slug);
  });
});
