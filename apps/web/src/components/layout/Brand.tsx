import { Link } from 'react-router';
import logoMark from '@/assets/brand/logo-mark.webp';

/** Light-background brand lock-up: the "L" mark plus the typed wordmark (Phase 2 logo rules). */
export function Brand() {
  return (
    <Link to="/" className="flex items-center gap-2 text-[17px] sm:gap-2.5 sm:text-[19px] font-extrabold tracking-tight text-ink no-underline hover:text-ink">
      <img src={logoMark} alt="" width={34} height={34} className="size-[34px] flex-none object-contain" />
      <span className="whitespace-nowrap">Leenex Labs</span>
    </Link>
  );
}
