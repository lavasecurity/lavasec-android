import type {
  LavaActionButtonProps,
  LavaCardProps,
  LavaTextProps,
  LavaToggleRowProps,
} from './contracts';

// Metro selects components.android.tsx on Android. Keep the default explicit
// so another platform never silently borrows Android visuals before it has a
// reviewed mapping (mirrors the iOS components.tsx guard).
function unavailable(): never {
  throw new Error(
    'Lava UI currently has an Android implementation only. This platform needs its native component mapping.',
  );
}
export function LavaText(_props: LavaTextProps): never {
  return unavailable();
}
export function LavaCard(_props: LavaCardProps): never {
  return unavailable();
}
export function LavaToggleRow(_props: LavaToggleRowProps): never {
  return unavailable();
}
export function LavaActionButton(_props: LavaActionButtonProps): never {
  return unavailable();
}
