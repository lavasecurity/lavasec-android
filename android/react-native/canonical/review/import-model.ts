/**
 * Import presentation vocabulary and honest copy (plan 309 G4 / F11).
 *
 * Pure: no native module and no React, so the runtime adapter and the shared screen can both use
 * it. The Android adapter (`canonical/android/runtime.ts`) sends the raw candidate to the backend
 * review owner through the canonical `native.flow` seam; the owner (`FilterShareOwner.reviewImport`
 * / `ShareableFilterImportReview`) is the only reviewer and the only authority on what a code
 * contains. Nothing here decodes, reviews, or applies a payload.
 *
 * Camera and photo acquisition use Android ActivityResult adapters and on-device QR decoding.
 * Raw payloads remain native after acquisition; RN receives the reconciled review projection.
 */

export type ImportMethodId = 'scan' | 'photo' | 'code';

/** The canonical `native.flow` token one import method hands off to. */
export type ImportFlow = 'import' | 'importScan' | 'importCode';

/**
 * The Android-only reviewed-import confirmation `native.flow`. It is not part of the canonical
 * iOS flow inventory (iOS presents its own importer); the shared chooser confirms a reviewed code
 * through it. The bridge owns the reviewed content and never accepts a payload at confirm time.
 */
export const IMPORT_APPLY_FLOW = 'importApply';

export interface ImportMethod {
  id: ImportMethodId;
  title: string;
  description: string;
  flow: ImportFlow;
}

/** Canonical method rows, in the pinned `ImportScreen` order. */
export const IMPORT_METHODS: readonly ImportMethod[] = [
  {id: 'scan', title: 'Scan a QR code', description: 'Use the camera to read a code someone shared.', flow: 'importScan'},
  {id: 'photo', title: 'Import from Photo', description: 'Choose a screenshot or image of a code.', flow: 'import'},
  {id: 'code', title: 'Enter a code', description: 'Paste the setup code someone shared with you.', flow: 'importCode'},
];

/**
 * The `native.flow` tokens that carry a share/import owner refusal. Used by the runtime adapter to
 * scope the share/import reason vocabulary: `unknown-filter`/`stale-review` are ALSO emitted by
 * ordinary filter commands, so the mapper must never be consulted for those.
 */
export const IMPORT_FLOWS: ReadonlySet<string> = new Set([
  ...IMPORT_METHODS.map(method => method.flow),
  IMPORT_APPLY_FLOW,
  'importBegin', 'importTarget', 'importCancel',
]);

/** Pinned to the canonical `ImportScreen` copy: a shared filter was not reviewed by Lava. */
export const IMPORT_SOURCE_REVIEW_WARNING =
  'Check who shared this filter and review its contents carefully before importing. Shared filters are not reviewed by Lava.';

/** Pinned to the canonical "It usually starts with" code-entry hint. */
export const IMPORT_CODE_HINT =
  'Paste the setup code someone shared with you. It usually starts with "LF1-".';

/**
 * The just-reviewed contract: nothing is written to the saved library until the user confirms
 * how the code should land (as a new filter or as a replacement). The screen keeps this copy
 * beside the review so a review is never mistaken for a completed import.
 */
export const IMPORT_UNSAVED_NOTE =
  'Nothing is saved until you confirm how to import this code.';

/**
 * A screen-level transport guard, not the owner's bound. The codec caps the encoded body and the
 * canonical link wraps it, so this only rejects an absurd paste before it reaches the owner. The
 * owner stays authoritative on every real size/format rejection.
 */
export const IMPORT_INPUT_MAX = 64 * 1024;

export type ImportInputProblem = 'blank' | 'oversized';

/** The one local structural check; null means the input may be handed to the owner. */
export function validateImportInput(raw: string): ImportInputProblem | null {
  const trimmed = raw.trim();
  if (trimmed.length === 0) return 'blank';
  if (trimmed.length > IMPORT_INPUT_MAX) return 'oversized';
  return null;
}

export function importInputProblemCopy(problem: ImportInputProblem): string {
  return problem === 'blank'
    ? 'Enter or paste a setup code.'
    : 'That is far too long to be a Lava setup code.';
}

/**
 * The reviewed subset the owner reports for a decoded code. Counts only; the owner never sends the
 * raw payload back and the screen never decodes it.
 */
export interface ImportReview {
  blocklistIds: string[];
  blockedDomainCount: number;
  allowedDomainCount: number | null;
  droppedCount: number;
  unknownSourceIds: string[];
  canConfirm: boolean;
}

/** A one-line honest summary of what the owner reviewed. */
export function importReviewSummary(review: ImportReview): string {
  const lists = review.blocklistIds.length;
  const blocked = review.blockedDomainCount;
  const allowed = review.allowedDomainCount ?? 0;
  const parts = [
    `${lists} blocklist${lists === 1 ? '' : 's'}`,
    `${blocked} blocked domain${blocked === 1 ? '' : 's'}`,
  ];
  if (allowed > 0) parts.push(`${allowed} allowed exception${allowed === 1 ? '' : 's'}`);
  const dropped = review.droppedCount + review.unknownSourceIds.length;
  const note = dropped > 0 ? ` ${dropped} entr${dropped === 1 ? 'y' : 'ies'} cannot be imported here.` : '';
  return `This code is a valid Lava filter: ${parts.join(', ')}.${note}`;
}

/**
 * Stable owner refusal codes -> honest copy. Shared by the runtime error mapper and the screen, so
 * no owner code ever reaches the UI raw. Returns null for a code this mapper does not own, letting
 * the caller apply its own generic/stale/unsupported handling.
 */
export function ownerReasonCopy(reason: string | null | undefined): string | null {
  switch (reason) {
    case 'unknown-filter':
      return 'This filter is no longer saved on this device.';
    case 'share-empty':
      return 'This filter has nothing to share yet. Add a blocklist or a blocked domain first.';
    case 'share-private-source':
      return 'A custom blocklist in this filter carries a private link, so Lava will not share it.';
    case 'share-exceeds-capacity':
      return 'This filter is too large to share as a setup code.';
    case 'share-code-unavailable':
      return 'Lava could not build a setup code for this filter.';
    case 'share-card-unavailable':
      return 'Sharing a filter card is not available in this build. Copy the setup code instead.';
    case 'share-card-not-ready':
      return 'The filter card is still being prepared. Try sharing again.';
    case 'stale-share':
      return 'This filter changed while the card was being prepared. Open it again before sharing.';
    case 'share-document-unavailable':
      return 'Importing from a photo or file is not available in this build yet.';
    case 'share-camera-unavailable':
      return 'Scanning a QR code with the camera is not available in this build yet.';
    case 'share-permission-denied':
      return 'Lava needs camera or photo permission to import. You can enter a setup code instead.';
    case 'share-cancelled':
      return 'Import cancelled.';
    case 'share-adapter-unavailable':
      return 'Importing from a photo or file is not available in this build yet.';
    case 'import-presentation-unavailable':
      return 'Importing a filter is not connected in this build yet.';
    case 'not-a-code':
      return 'That does not look like a Lava setup code.';
    case 'invalid-link':
      return 'That is not a Lava filter link.';
    case 'unsupported-version':
      return 'That code was made by a newer version of Lava. Update the app and try again.';
    case 'integrity-check-failed':
      return 'That code looks damaged. Ask for a fresh copy.';
    case 'payload-too-large':
      return 'That code is too large to import.';
    case 'malformed-payload':
      return 'That code could not be read.';
    case 'nothing-to-import':
      return 'There is nothing in this code to import.';
    case 'plan-not-fully-accepted':
      return 'Part of this filter cannot be imported, so nothing was changed.';
    case 'active-replacement-not-confirmed':
      return 'Replacing the filter in effect needs your explicit confirmation.';
    case 'stale-review':
      return 'The filter changed since it was reviewed. Review the import again.';
    default:
      return null;
  }
}
