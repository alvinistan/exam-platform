import { Suspense } from 'react';
import { NavLink, Outlet } from 'react-router';
import { FileQuestion, FileText, FolderTree, Users } from 'lucide-react';
import { cn } from '@/lib/cn';
import { PageLoading } from './PageLoading';

/**
 * Administration shell: side navigation plus content. Sections are added as their phases deliver them, so every
 * link here leads somewhere that works.
 */
export function AdminLayout() {
  const linkClass = ({ isActive }: { isActive: boolean }) =>
    cn(
      'flex min-h-10 items-center gap-2.5 rounded-sm px-3 py-2 text-[14.5px] font-medium no-underline',
      isActive ? 'bg-primary-soft font-semibold text-primary-ink' : 'text-ink-2 hover:bg-surface-2 hover:text-ink',
    );
  return (
    <div className="mx-auto flex w-full max-w-[1200px] flex-col gap-6 px-4 py-8 sm:px-8 md:flex-row">
      <aside className="md:w-56 md:flex-none">
        <p className="px-3 pb-2 text-xs font-bold tracking-wider text-subtle uppercase">Administration</p>
        <nav aria-label="Administration" className="flex flex-row flex-wrap gap-1 md:flex-col">
          <NavLink to="/admin/exams" className={linkClass}>
            <FileText aria-hidden="true" className="size-4" /> Exams
          </NavLink>
          <NavLink to="/admin/questions" className={linkClass}>
            <FileQuestion aria-hidden="true" className="size-4" /> Question bank
          </NavLink>
          <NavLink to="/admin/categories" className={linkClass}>
            <FolderTree aria-hidden="true" className="size-4" /> Categories
          </NavLink>
          <NavLink to="/admin/users" className={linkClass}>
            <Users aria-hidden="true" className="size-4" /> Users
          </NavLink>
        </nav>
      </aside>
      <div className="min-w-0 flex-1">
        <Suspense fallback={<PageLoading />}>
          <Outlet />
        </Suspense>
      </div>
    </div>
  );
}
