import { BookOpenCheck, Sparkles, Timer } from 'lucide-react';
import { Card } from '@/components/ui';

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
        </div>
      </section>
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
