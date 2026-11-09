import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, rmSync, cpSync, statSync, readdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { applyNativeScreensRetirementFix, PIN } from '../apply-native-screens-retirement-fix.mjs';

const rnRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const installed = resolve(rnRoot, 'node_modules/react-native-screens');
const script = resolve(rnRoot, 'scripts/apply-native-screens-retirement-fix.mjs');
const sha = (bytes) => createHash('sha256').update(bytes).digest('hex');
const upstreamHunk = [
  '        val stack = screenStack',
  '        // A screen that is not in a stack has nothing to configure a header for, and',
  '        // `canNavigateBack()` below asserts on exactly this condition. Treating a null',
  '        // stack as "is top" let a detached screen reach that assertion and throw.',
  '        if (stack == null) {',
  '            return',
  '        }',
  '        val isTop = stack.topScreen == parent',
  '',
].join('\n');
const oldHunk = '        val stack = screenStack\n        val isTop = stack == null || stack.topScreen == parent\n';

function fixture(run) {
  const root = mkdtempSync(resolve(tmpdir(), 'lavasec-screens-retirement-'));
  const dependency = resolve(root, 'node_modules/react-native-screens');
  const source = resolve(dependency, PIN.sourcePath);
  mkdirSync(dirname(source), { recursive: true });
  cpSync(resolve(installed, 'package.json'), resolve(dependency, 'package.json'));
  const current = readFileSync(resolve(installed, PIN.sourcePath));
  // CI invokes the adapter before native compilation. Derive the exact locked
  // preimage from either supported state; any other installed source fails here.
  assert.ok([PIN.originalSha256, PIN.patchedSha256].includes(sha(current)));
  const original = sha(current) === PIN.originalSha256 ? current :
    Buffer.from(current.toString('utf8').replace(upstreamHunk, oldHunk));
  assert.equal(sha(original), PIN.originalSha256);
  writeFileSync(source, original, { mode: 0o640 });
  const fragmentName = 'android/src/main/java/com/swmansion/rnscreens/ScreenStackFragment.kt';
  cpSync(resolve(installed, fragmentName), resolve(dependency, fragmentName));
  try { run({ root, dependency, source, original, fragment: resolve(dependency, fragmentName) }); }
  finally { rmSync(root, { recursive: true, force: true }); }
}

test('applies only the pinned upstream guard and preserves mounted behavior and strict stack invariant', () => fixture(f => {
  const metadata = readFileSync(resolve(f.dependency, 'package.json'));
  const fragment = readFileSync(f.fragment);
  assert.equal(applyNativeScreensRetirementFix(f.dependency), 'patched');
  const patched = readFileSync(f.source);
  assert.equal(sha(patched), PIN.patchedSha256);
  assert.equal(patched.toString('utf8').replace(upstreamHunk, oldHunk), f.original.toString('utf8'));
  assert.deepEqual(readFileSync(resolve(f.dependency, 'package.json')), metadata);
  assert.deepEqual(readFileSync(f.fragment), fragment);
  assert.match(fragment.toString('utf8'), /check\(container is ScreenStack\)/);
  assert.equal(statSync(f.source).mode & 0o777, 0o640);
  assert.deepEqual(readdirSync(dirname(f.source)).sort(), ['ScreenStackFragment.kt', 'ScreenStackHeaderConfig.kt']);
}));

test('a second application does not rewrite the pinned patched source', () => fixture(f => {
  applyNativeScreensRetirementFix(f.dependency);
  const before = statSync(f.source);
  const bytes = readFileSync(f.source);
  assert.equal(applyNativeScreensRetirementFix(f.dependency), 'already-patched');
  assert.deepEqual(readFileSync(f.source), bytes);
  assert.equal(statSync(f.source).mtimeMs, before.mtimeMs);
  assert.equal(statSync(f.source).ino, before.ino);
}));

test('a different dependency version is refused without changing native source', () => fixture(f => {
  const path = resolve(f.dependency, 'package.json');
  const json = JSON.parse(readFileSync(path)); json.version = '5.0.0-alpha.3';
  writeFileSync(path, JSON.stringify(json));
  assert.throws(() => applyNativeScreensRetirementFix(f.dependency), /expected react-native-screens 4\.28\.0/);
  assert.deepEqual(readFileSync(f.source), f.original);
}));

test('changed package metadata at the same version is refused', () => fixture(f => {
  const path = resolve(f.dependency, 'package.json');
  writeFileSync(path, Buffer.concat([readFileSync(path), Buffer.from('\n')]));
  assert.throws(() => applyNativeScreensRetirementFix(f.dependency), /package metadata hash changed/);
  assert.deepEqual(readFileSync(f.source), f.original);
}));

test('an unrelated native source change is refused and left intact', () => fixture(f => {
  const changed = Buffer.concat([f.original, Buffer.from('\n// unrelated native change\n')]);
  writeFileSync(f.source, changed);
  assert.throws(() => applyNativeScreensRetirementFix(f.dependency), /native header source hash changed/);
  assert.deepEqual(readFileSync(f.source), changed);
}));

test('a guard with the wrong behavior is refused rather than accepted as patched', () => fixture(f => {
  applyNativeScreensRetirementFix(f.dependency);
  const changed = Buffer.from(readFileSync(f.source, 'utf8').replace('if (stack == null)', 'if (stack != null)'));
  writeFileSync(f.source, changed);
  assert.throws(() => applyNativeScreensRetirementFix(f.dependency), /native header source hash changed/);
  assert.deepEqual(readFileSync(f.source), changed);
}));

test('the actual CLI patches only its own mirrored package and is idempotent', () => fixture(f => {
  const targetScript = resolve(f.root, 'scripts/apply-native-screens-retirement-fix.mjs');
  mkdirSync(dirname(targetScript), { recursive: true }); cpSync(script, targetScript);
  for (const expected of ['patched', 'already-patched']) {
    const result = spawnSync(process.execPath, [targetScript], { encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.stdout.trim(), `Screens 4.28.0 upstream #4498: ${expected}`);
  }
  const refused = spawnSync(process.execPath, [targetScript, f.dependency], { encoding: 'utf8' });
  assert.notEqual(refused.status, 0);
  assert.match(refused.stderr, /unexpected CLI arguments/);
}));

test('missing dependencies fail rather than silently omitting the backport', () => {
  const root = mkdtempSync(resolve(tmpdir(), 'lavasec-screens-missing-'));
  try {
    assert.throws(() => applyNativeScreensRetirementFix(root), /ENOENT/);
    assert.deepEqual(readdirSync(root), []);
  } finally { rmSync(root, { recursive: true, force: true }); }
});
