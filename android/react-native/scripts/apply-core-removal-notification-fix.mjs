// Pinned compatibility repair for @react-navigation/core 7.21.13 (MIT).
// Retain upstream route validation, parent propagation and beforeRemove hooks.
// Unchanged notifications never schedule state. Changed requests retain LIVE
// registry reads and an exact commit receipt, including queued reversals.
// This local repair is a runtime candidate until the exact packaged cohort passes.
import { createHash, randomUUID } from 'node:crypto';
import { readFileSync, writeFileSync, renameSync, unlinkSync, statSync, realpathSync, existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const PIN = Object.freeze({
  version: '7.21.13',
  packageSha256: '10390877104b2fcfaee3210197554b89ca900b69530f79bb220a7c7b4d160b71',
});
const sourceMapFooter = '//# sourceMappingURL=PreventRemoveProvider.js.map';
const plans = [
  {
    "relativePath": "src/PreventRemoveProvider.tsx",
    "originalSha256": "50d7ae890ce598e90f8f67cbee9354e154bd038d25af25c07fba72e422c3de03",
    "patchedSha256": "6469f698f5210aeebd66f2f4038ce8f7ebdd7938d11f49c33da4d20fd1d54a33",
    "hunks": [
      {
        "before": "type PreventedRoutesMap = Map<string, string>;",
        "after": "type PreventedRoutesMap = Map<string, string>;\ntype Publication = {map: PreventedRoutesMap; request: object};"
      },
      {
        "before": "  const [preventedRoutesMap, setPreventedRoutesMap] =\n    React.useState<PreventedRoutesMap>(() => new Map());\n\n",
        "after": "  const [publication, setPublication] = React.useState<Publication>(() => ({map: new Map(), request: {}}));\n  const preventedRoutesMap = publication.map;\n  const requested = React.useRef(publication);\n  // Acknowledge only this exact committed request; an older commit must not\n  // erase a newer queued target. Preserve the updater's live registry read.\n  React.useLayoutEffect(() => {\n    if (requested.current.request === publication.request) requested.current = publication;\n  }, [publication]);\n\n"
      },
      {
        "before": "  const notifyPreventRemove = useLatestCallback(() => {\n    // eslint-disable-next-line @eslint-react/hooks-extra/no-direct-set-state-in-use-effect\n    setPreventedRoutesMap((prevPrevented) => {\n      const nextPrevented = registry.current;\n\n      const hasChanged =\n        prevPrevented.size !== nextPrevented.size ||\n        [...nextPrevented].some(\n          ([id, routeKey]) => prevPrevented.get(id) !== routeKey\n        );\n\n      // values haven't changed - do nothing\n      if (!hasChanged) {\n        return prevPrevented;\n      }\n\n      return new Map(nextPrevented);\n    });\n  });\n",
        "after": "  const notifyPreventRemove = useLatestCallback(() => {\n    const previous = requested.current.map;\n    const next = registry.current;\n    const hasRequestedChange =\n      previous.size !== next.size ||\n      [...next].some(([id, routeKey]) => previous.get(id) !== routeKey);\n    if (!hasRequestedChange) return;\n\n    const request = {};\n    requested.current = {map: new Map(next), request};\n    // Genuine requested deltas receive a commit receipt even if insertion\n    // cleanup has already reverted the registry when this updater executes.\n    setPublication((prev) => {\n      const current = registry.current;\n      const hasChanged =\n        prev.map.size !== current.size ||\n        [...current].some(([id, routeKey]) => prev.map.get(id) !== routeKey);\n      return {map: hasChanged ? new Map(current) : prev.map, request};\n    });\n  });\n"
      }
    ],
    "removeStaleSourceMap": false
  },
  {
    "relativePath": "lib/module/PreventRemoveProvider.js",
    "originalSha256": "db38c8d36d8ad63abf079c8444a55014254f4502ed6d9cbd28cf3b02760e7c5c",
    "patchedSha256": "6df237fdb5f1f465cf6e93615df7ea5c3f29add6177be853d6f07292a9474e91",
    "hunks": [
      {
        "before": "  const [preventedRoutesMap, setPreventedRoutesMap] = React.useState(() => new Map());\n",
        "after": "  const [publication, setPublication] = React.useState(() => ({map: new Map(), request: {}}));\n  const preventedRoutesMap = publication.map;\n  const requested = React.useRef(publication);\n  // Acknowledge only this exact committed request; an older commit must not\n  // erase a newer queued target. Preserve the updater's live registry read.\n  React.useLayoutEffect(() => {\n    if (requested.current.request === publication.request) requested.current = publication;\n  }, [publication]);\n"
      },
      {
        "before": "  const notifyPreventRemove = useLatestCallback(() => {\n    // eslint-disable-next-line @eslint-react/hooks-extra/no-direct-set-state-in-use-effect\n    setPreventedRoutesMap(prevPrevented => {\n      const nextPrevented = registry.current;\n      const hasChanged = prevPrevented.size !== nextPrevented.size || [...nextPrevented].some(([id, routeKey]) => prevPrevented.get(id) !== routeKey);\n\n      // values haven't changed - do nothing\n      if (!hasChanged) {\n        return prevPrevented;\n      }\n      return new Map(nextPrevented);\n    });\n  });\n",
        "after": "  const notifyPreventRemove = useLatestCallback(() => {\n    const previous = requested.current.map;\n    const next = registry.current;\n    const hasRequestedChange =\n      previous.size !== next.size ||\n      [...next].some(([id, routeKey]) => previous.get(id) !== routeKey);\n    if (!hasRequestedChange) return;\n\n    const request = {};\n    requested.current = {map: new Map(next), request};\n    // Genuine requested deltas receive a commit receipt even if insertion\n    // cleanup has already reverted the registry when this updater executes.\n    setPublication((prev) => {\n      const current = registry.current;\n      const hasChanged =\n        prev.map.size !== current.size ||\n        [...current].some(([id, routeKey]) => prev.map.get(id) !== routeKey);\n      return {map: hasChanged ? new Map(current) : prev.map, request};\n    });\n  });\n"
      }
    ],
    "removeStaleSourceMap": true
  }
];
export const FILES = Object.freeze(plans.map(plan => Object.freeze({
  relativePath: plan.relativePath,
  originalSha256: plan.originalSha256,
  patchedSha256: plan.patchedSha256,
})));
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const refuse = message => { throw new Error(`Core removal notification repair refused: ${message}`); };
function planFor(relativePath) {
  const plan = plans.find(item => item.relativePath === relativePath);
  if (!plan) refuse('unknown provider path');
  return plan;
}
function replaceOnce(text, before, after) {
  if (text.split(before).length !== 2) refuse('pinned hunk is not unique');
  return text.replace(before, after);
}
function transform(bytes, plan, reverse = false) {
  let text = bytes.toString('utf8');
  if (reverse && plan.removeStaleSourceMap) text += sourceMapFooter;
  for (const hunk of reverse ? [...plan.hunks].reverse() : plan.hunks) {
    text = replaceOnce(text, reverse ? hunk.after : hunk.before, reverse ? hunk.before : hunk.after);
  }
  if (!reverse && plan.removeStaleSourceMap) {
    if (!text.endsWith(sourceMapFooter)) refuse('compiled source-map footer changed');
    text = text.slice(0, -sourceMapFooter.length);
  }
  const result = Buffer.from(text, 'utf8');
  if (digest(result) !== (reverse ? plan.originalSha256 : plan.patchedSha256)) refuse('transformed source hash changed');
  return result;
}

// Pure, hash-checked reconstruction for temporary fixtures after a CI install
// already applied the repair. Never writes or accepts an unknown source image.
export function lockedOriginalCoreProvider(relativePath, bytes) {
  const plan = planFor(relativePath);
  const hash = digest(bytes);
  if (hash === plan.originalSha256) return Buffer.from(bytes);
  if (hash === plan.patchedSha256) return transform(bytes, plan, true);
  refuse('fixture provider source hash changed');
}

// Exported for owned temporary fixtures. CLI accepts no target argument.
export function applyCoreRemovalNotificationFix(dependencyDir) {
  const metadataPath = resolve(dependencyDir, 'package.json');
  const metadataBytes = readFileSync(metadataPath);
  const metadata = JSON.parse(metadataBytes.toString('utf8'));
  if (metadata.name !== '@react-navigation/core' || metadata.version !== PIN.version) refuse('expected @react-navigation/core 7.21.13');
  if (digest(metadataBytes) !== PIN.packageSha256) refuse('package metadata hash changed');

  // Preflight BOTH actual module and source before writing either. Accept only
  // pinned before/after images, including a restart after one atomic rename.
  const inputs = plans.map(plan => {
    const target = resolve(dependencyDir, plan.relativePath);
    const bytes = readFileSync(target);
    const hash = digest(bytes);
    if (hash !== plan.originalSha256 && hash !== plan.patchedSha256) refuse(`provider source hash changed (${plan.relativePath})`);
    return {plan, target, bytes, hash, temporary: null};
  });
  const pending = inputs.filter(input => input.hash === input.plan.originalSha256);
  if (!pending.length) return 'already-patched';
  try {
    for (const input of pending) {
      const patched = transform(input.bytes, input.plan);
      input.temporary = `${input.target}.lavasec-${randomUUID()}.tmp`;
      writeFileSync(input.temporary, patched, {flag:'wx',mode:statSync(input.target).mode & 0o777});
    }
    if (digest(readFileSync(metadataPath)) !== PIN.packageSha256) refuse('package changed during repair');
    for (const input of inputs) if (digest(readFileSync(input.target)) !== input.hash) refuse('source changed during repair');
    for (const input of pending) {
      if (digest(readFileSync(input.target)) !== input.hash) refuse('source changed before atomic rename');
      renameSync(input.temporary, input.target);
      if (digest(readFileSync(input.target)) !== input.plan.patchedSha256) refuse('written source hash changed');
    }
  } finally {
    for (const input of pending) if (input.temporary) {
      try { unlinkSync(input.temporary); } catch (error) { if (error?.code !== 'ENOENT') throw error; }
    }
  }
  return 'patched';
}

if (process.argv[1] && existsSync(resolve(process.argv[1])) && fileURLToPath(import.meta.url) === realpathSync(resolve(process.argv[1]))) {
  if (process.argv.length !== 2) refuse('unexpected CLI arguments');
  const rnRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
  const result = applyCoreRemovalNotificationFix(resolve(rnRoot, 'node_modules/@react-navigation/core'));
  console.log(`Core 7.21.13 removal queue repair: ${result}`);
}
