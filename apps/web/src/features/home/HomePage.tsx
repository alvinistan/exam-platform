import { Link } from 'react-router';
import { ArrowRight, BookOpenCheck, Sparkles, Timer } from 'lucide-react';
import { Card, Skeleton, buttonClasses } from '@/components/ui';
import { useExamAccess } from '@/features/access/api';
import { ExamCard } from '@/features/exams/ExamCard';
import { useCategories, useExams } from '@/features/exams/api';

const modes = [
  {
    icon: BookOpenCheck,
    title: 'Practice',
    text: 'Choose subjects and topics, answer at your own pace and see the explanation after each question.',
  },
  {
    icon: Timer,
    title: 'Mock exam',
    text: 'Sit a timed paper under exam conditions, then review every answer once you submit.',
  },
  {
    icon: Sparkles,
    title: 'Guided practice',
    text: 'Pick how many questions or how much time you have; the session adapts to what you need to revise.',
  },
];

export function HomePage() {
  return (
    <>
      <section className="border-b border-border bg-surface">
        <div className="mx-auto flex max-w-[1200px] flex-col gap-4 px-4 py-16 sm:px-8 sm:py-20">
          <p className="text-sm font-semibold tracking-wide text-primary">Many exams, one platform</p>
          <h1 className="max-w-3xl text-4xl font-extrabold tracking-tight text-balance sm:text-5xl">
            Practise smarter and walk into your exam ready.
          </h1>
          <p className="max-w-2xl text-lg text-muted">
            Practice questions, timed mock exams and a study plan that adapts to your results.
          </p>
          <div>
            <Link to="/exams" className={buttonClasses('primary', 'lg')}>
              Browse exams <ArrowRight aria-hidden="true" className="size-5" />
            </Link>
          </div>
        </div>
      </section>
      <FeaturedExams />
      <section aria-labelledby="modes-heading" className="mx-auto max-w-[1200px] px-4 py-14 sm:px-8">
        <h2 id="modes-heading" className="text-2xl font-bold">
          Three ways to study
        </h2>
        <ul className="mt-6 grid gap-5 [grid-template-columns:repeat(auto-fit,minmax(min(260px,100%),1fr))]">
          {modes.map(({ icon: Icon, title, text }) => (
            <li key={title}>
              <Card className="flex h-full flex-col gap-3 p-6">
                <span className="flex size-10 items-center justify-center rounded-md bg-primary-soft text-primary">
                  <Icon aria-hidden="true" className="size-5" />
                </span>
                <h3 className="text-lg font-bold">{title}</h3>
                <p className="text-muted">{text}</p>
              </Card>
            </li>
          ))}
        </ul>
      </section>
    </>
  );
}

/**
 * Featured exams and categories come from the catalogue API (FR-CAT-14), never from hard-coded cards. When nothing
 * is published yet the section simply isn't shown.
 */
function FeaturedExams() {
  const featured = useExams({ featured: true, size: 10 });
  const categories = useCategories();
  const exams = featured.data?.items.slice(0, 6) ?? [];
  const access = useExamAccess(exams.map((e) => e.id));
  const activeCategories = categories.data?.filter((c) => (c.examCount ?? 0) > 0) ?? [];
  const categoryName = (id: string) => categories.data?.find((c) => c.id === id)?.name;

  if (featured.isPending) {
    return (
      <div aria-busy="true" className="mx-auto grid max-w-[1200px] gap-5 px-4 pt-14 sm:px-8 [grid-template-columns:repeat(auto-fill,minmax(min(280px,100%),1fr))]">
        <p role="status" className="sr-only">
          Loading featured exams…
        </p>
        {Array.from({ length: 3 }, (_, i) => (
          <Skeleton key={i} className="h-56 w-full rounded-lg" />
        ))}
      </div>
    );
  }
  if (exams.length === 0 && activeCategories.length === 0) return null;

  return (
    <div className="mx-auto flex max-w-[1200px] flex-col gap-10 px-4 pt-14 sm:px-8">
      {exams.length > 0 && (
        <section aria-labelledby="featured-heading" className="flex flex-col gap-5">
          <div className="flex flex-wrap items-end justify-between gap-3">
            <h2 id="featured-heading" className="text-2xl font-bold">
              Featured exams
            </h2>
            <Link to="/exams" className="font-semibold">
              See all exams
            </Link>
          </div>
          <ul className="grid gap-5 [grid-template-columns:repeat(auto-fill,minmax(min(280px,100%),1fr))]">
            {exams.map((exam) => (
              <li key={exam.id}>
                <ExamCard exam={exam} categoryName={categoryName(exam.categoryId)} access={access.data?.[exam.id]} />
              </li>
            ))}
          </ul>
        </section>
      )}
      {activeCategories.length > 0 && (
        <section aria-labelledby="categories-heading" className="flex flex-col gap-4">
          <h2 id="categories-heading" className="text-2xl font-bold">
            Browse by category
          </h2>
          <ul className="flex flex-wrap gap-2.5">
            {activeCategories.map((c) => (
              <li key={c.id}>
                <Link
                  to={`/exams?category=${encodeURIComponent(c.slug)}`}
                  className="inline-flex min-h-11 items-center gap-2 rounded-full border border-border bg-surface px-4 py-2 font-semibold text-ink no-underline hover:border-primary hover:text-primary"
                >
                  {c.name}
                  <span className="text-[13px] font-medium text-muted">{c.examCount}</span>
                </Link>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}
