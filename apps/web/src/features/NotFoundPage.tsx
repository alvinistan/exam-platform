import { Link } from 'react-router';
import { SearchX } from 'lucide-react';
import { buttonClasses, EmptyState } from '@/components/ui';

export function NotFoundPage() {
  return (
    <EmptyState
      headingLevel="h1"
      icon={SearchX}
      title="Page not found"
      action={
        <Link to="/" className={buttonClasses('secondary')}>
          Back to home
        </Link>
      }
    >
      <p>Check the address, or head back to the home page.</p>
    </EmptyState>
  );
}
