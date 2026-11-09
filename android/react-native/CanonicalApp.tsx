import {LavaUIReview} from './canonical/review/LavaUIReview.android';

// The Android host passes the stored appearance preference as a launch option so
// the first frame already uses it instead of flashing the system scheme.
export default function CanonicalApp({initialAppearance,nativeHostID}: {initialAppearance?: string;nativeHostID?:string} = {}) {
  return <LavaUIReview fullApp initialAppearance={initialAppearance} nativeHostID={nativeHostID} />;
}
