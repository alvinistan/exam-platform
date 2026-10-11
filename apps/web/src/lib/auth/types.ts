export type Role = 'STUDENT' | 'ADMIN';

/** The signed-in user (identity-service User schema). */
export interface Me {
  id: string;
  email: string;
  displayName: string;
  photoUrl?: string | null;
  roles: Role[];
  status: 'ACTIVE' | 'SUSPENDED';
  preferences?: Record<string, unknown>;
  createdAt?: string;
  lastLoginAt?: string | null;
  version: number;
}

export type AuthState =
  | { status: 'loading'; user: null }
  /** `endedByUser`: they just signed out (or deleted their account) here, rather than never having signed in. */
  | { status: 'signedOut'; user: null; endedByUser?: boolean }
  | { status: 'signedIn'; user: Me };
