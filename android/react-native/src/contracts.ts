import type {ReactNode} from 'react';

// Android's role-based Lava control contract. The names mirror the iOS
// contract (`ReactNative/src/contracts.ts`) so a shared screen can describe
// intent, while this file stays self-contained and never imports iOS-only
// assets or generated tokens. Callers name roles, never raw platform styles.

export type LavaTextRole =
  | 'title'
  | 'headline'
  | 'subheadline'
  | 'body'
  | 'caption'
  | 'metricNumeral';

export type LavaTextTone = 'primary' | 'secondary' | 'warning' | 'danger';

export interface LavaTextProps {
  children: string;
  role?: LavaTextRole;
  /** Defaults to the role's ink; overrides paint semantic state. */
  tone?: LavaTextTone;
  testID?: string;
}

export interface LavaCardProps {
  children: ReactNode;
  role?: 'card' | 'panel';
  testID?: string;
}

export interface LavaToggleRowProps {
  /** Resolved text; the host owns locale/catalog selection. */
  title: string;
  value: boolean;
  onValueChange: (value: boolean) => void;
  disabled?: boolean;
  accessibilityHint?: string;
  testID?: string;
}

export interface LavaActionButtonProps {
  title: string;
  role?: 'primary' | 'panel' | 'secondary';
  onPress: () => void;
  disabled?: boolean;
  accessibilityHint?: string;
  testID?: string;
}
