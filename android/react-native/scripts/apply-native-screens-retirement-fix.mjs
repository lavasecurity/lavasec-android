// Exact MIT-licensed upstream backport for locked react-native-screens 4.28.0.
// https://github.com/software-mansion/react-native-screens/commit/01de75b711f46926a4d3c951c7ee07784f479e9b
// Upstream #4498: do not mutate a retained header after its Screen leaves ScreenStack.
// Remove this compatibility adapter when the locked release includes that upstream guard.
import { createHash, randomUUID } from 'node:crypto';
import { readFileSync, writeFileSync, renameSync, unlinkSync, statSync, realpathSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const PIN = Object.freeze({
  version: '4.28.0',
  packageSha256: '8ad5eba133366d879b9b8474925fc6d078721d31aa6096aa9b304175461b52d0',
  originalSha256: '1c94acaeff477b7e9009b5c6009e8dd5445a4c393a4a2949b749da276a91a3db',
  patchedSha256: '0080324adf1c0bb45f95c87c488a7570a49c37a70996f1bf77d402a5780ab354',
  sourcePath: 'android/src/main/java/com/swmansion/rnscreens/ScreenStackHeaderConfig.kt',
});
const before = '        val stack = screenStack\n        val isTop = stack == null || stack.topScreen == parent\n';
const after = [
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
const digest = (bytes) => createHash('sha256').update(bytes).digest('hex');
const refuse = (message) => { throw new Error(`Screens retirement backport refused: ${message}`); };

// Exported only to exercise the exact adapter against owned temporary dependency trees.
// The normal CLI selects its own package's node_modules and takes no target-path argument.
export function applyNativeScreensRetirementFix(dependencyDir) {
  const packageBytes = readFileSync(resolve(dependencyDir, 'package.json'));
  const metadata = JSON.parse(packageBytes.toString('utf8'));
  if (metadata.name !== 'react-native-screens' || metadata.version !== PIN.version) {
    refuse('expected react-native-screens 4.28.0');
  }
  if (digest(packageBytes) !== PIN.packageSha256) refuse('package metadata hash changed');
  const source = resolve(dependencyDir, PIN.sourcePath);
  const original = readFileSync(source);
  const originalDigest = digest(original);
  if (originalDigest === PIN.patchedSha256) return 'already-patched';
  if (originalDigest !== PIN.originalSha256) refuse('native header source hash changed');
  const text = original.toString('utf8');
  if (text.split(before).length !== 2) refuse('upstream hunk is not unique');
  const patched = Buffer.from(text.replace(before, after), 'utf8');
  if (digest(patched) !== PIN.patchedSha256) refuse('patched source hash changed');
  const temporary = `${source}.lavasec-${randomUUID()}.tmp`;
  try {
    writeFileSync(temporary, patched, { flag: 'wx', mode: statSync(source).mode & 0o777 });
    // Do not overwrite a concurrent install or another repair with a stale read.
    if (digest(readFileSync(source)) !== originalDigest) refuse('source changed during patch');
    renameSync(temporary, source);
  } finally {
    try { unlinkSync(temporary); } catch (error) { if (error?.code !== 'ENOENT') throw error; }
  }
  if (digest(readFileSync(source)) !== PIN.patchedSha256) refuse('written source hash changed');
  return 'patched';
}

if (process.argv[1] && fileURLToPath(import.meta.url) === realpathSync(resolve(process.argv[1]))) {
  if (process.argv.length !== 2) refuse('unexpected CLI arguments');
  const rnRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
  const result = applyNativeScreensRetirementFix(resolve(rnRoot, 'node_modules/react-native-screens'));
  console.log(`Screens 4.28.0 upstream #4498: ${result}`);
}
