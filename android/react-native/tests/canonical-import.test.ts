import {
  IMPORT_APPLY_FLOW,
  IMPORT_FLOWS,
  IMPORT_METHODS,
  IMPORT_UNSAVED_NOTE,
  importInputProblemCopy,
  importReviewSummary,
  ownerReasonCopy,
  validateImportInput,
} from '../canonical/review/import-model';

test('exposes the canonical import methods bound to their owner flow seams', () => {
  expect(IMPORT_METHODS.map(method => [method.id, method.flow])).toEqual([
    ['scan', 'importScan'],
    ['photo', 'import'],
    ['code', 'importCode'],
  ]);
  // The Android-only confirmation flow is scoped with the import vocabulary so its refusal
  // copy is mapped precisely, and it never collides with an ordinary filter reason.
  expect([...IMPORT_FLOWS].sort()).toEqual(['import', 'importApply', 'importBegin', 'importCancel', 'importCode', 'importScan', 'importTarget']);
  expect(IMPORT_APPLY_FLOW).toBe('importApply');
});

test('validates the code structurally before handing it to the owner', () => {
  expect(validateImportInput('   ')).toBe('blank');
  expect(validateImportInput('LF1-shared')).toBeNull();
  expect(validateImportInput('x'.repeat(70 * 1024))).toBe('oversized');
  expect(importInputProblemCopy('blank')).toMatch(/setup code/);
  expect(importInputProblemCopy('oversized')).toMatch(/too long/);
});

test('maps every missing platform adapter and code failure to explicit copy', () => {
  expect(ownerReasonCopy('share-camera-unavailable')).toMatch(/camera is not available/);
  expect(ownerReasonCopy('share-document-unavailable')).toMatch(/photo or file is not available/);
  expect(ownerReasonCopy('not-a-code')).toMatch(/setup code/);
  expect(ownerReasonCopy('unsupported-version')).toMatch(/newer version/);
  expect(ownerReasonCopy('integrity-check-failed')).toMatch(/damaged/);
  expect(ownerReasonCopy('import-presentation-unavailable')).toMatch(/not connected/);
  // The acquisition refusals are distinct from a missing feature: denial and cancellation
  // are user/OS facts, so they never read as unavailability.
  expect(ownerReasonCopy('share-permission-denied')).toMatch(/permission/i);
  expect(ownerReasonCopy('share-cancelled')).toMatch(/cancelled/);
  expect(ownerReasonCopy('share-adapter-unavailable')).toMatch(/photo or file is not available/);
  // A code this mapper does not own stays null so the runtime can apply its own fallback.
  expect(ownerReasonCopy('stale-generation')).toBeNull();
});

test('summarizes only what the owner reviewed and states the apply boundary', () => {
  expect(importReviewSummary({blocklistIds: ['a', 'b'], blockedDomainCount: 3, allowedDomainCount: 1, droppedCount: 0, unknownSourceIds: [], canConfirm: true}))
    .toBe('This code is a valid Lava filter: 2 blocklists, 3 blocked domains, 1 allowed exception.');
  expect(importReviewSummary({blocklistIds: [], blockedDomainCount: 0, allowedDomainCount: null, droppedCount: 2, unknownSourceIds: [], canConfirm: false}))
    .toMatch(/2 entries cannot be imported/);
  // The review is not a save: the unsaved contract is stated until the user confirms.
  expect(IMPORT_UNSAVED_NOTE).toMatch(/Nothing is saved until you confirm/);
});

test('maps the confirmation refusals to honest copy', () => {
  expect(ownerReasonCopy('stale-review')).toMatch(/filter changed since it was reviewed/);
  expect(ownerReasonCopy('plan-not-fully-accepted')).toMatch(/cannot be imported, so nothing was changed/);
  expect(ownerReasonCopy('active-replacement-not-confirmed')).toMatch(/explicit confirmation/);
  expect(ownerReasonCopy('nothing-to-import')).toMatch(/nothing in this code to import/);
});
