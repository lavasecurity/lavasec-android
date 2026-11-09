#!/usr/bin/env node
// Hermetic canonical-source verifier (plan 309 F02, convergence C2).
//
// Reads only the committed manifest (`scripts/canonical-source.manifest.json`) and the committed
// `canonical/` tree. It does not need the canonical iOS repository, so ordinary Android CI runs it
// with `npm run canonical:check`. A hand-edited canonical file, a new unclassified file, a removed
// file, a symlink or a stale count fails with the exact offending path.

import {createHash} from 'node:crypto';
import {readFileSync} from 'node:fs';
import {readdir} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const packageRoot = path.resolve(here, '..');
// Env overrides exist only so the fail-closed contract can be exercised against a mutated copy.
const canonicalRoot = process.env.CANONICAL_ROOT ? path.resolve(process.env.CANONICAL_ROOT) : path.join(packageRoot, 'canonical');
const manifestPath = process.env.CANONICAL_MANIFEST ? path.resolve(process.env.CANONICAL_MANIFEST) : path.join(here, 'canonical-source.manifest.json');
const KNOWN_CLASSES = new Set(['upstream', 'ios-platform', 'overlay', 'android-only', 'divergence']);

const failures = [];
const fail = message => failures.push(message);
const rel = p => path.relative(packageRoot, p);
const sha256 = buffer => createHash('sha256').update(buffer).digest('hex');

async function walk(root) {
  const out = [];
  async function visit(dir) {
    for (const name of await readdir(dir, {withFileTypes: true})) {
      const full = path.join(dir, name.name);
      if (name.isSymbolicLink()) throw new Error(`symlink not allowed in canonical tree: ${full}`);
      if (name.isDirectory()) await visit(full);
      else if (name.isFile()) out.push(path.relative(canonicalRoot, full).split(path.sep).join('/'));
      else throw new Error(`unsupported entry in canonical tree: ${full}`);
    }
  }
  await visit(root);
  return out.sort();
}

const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
if (manifest.schema !== 1) fail('manifest schema must be 1');
if (!manifest.canonical?.revision) fail('manifest must pin a canonical revision');
if (!manifest.canonical?.repository) fail('manifest must record the canonical repository');

const entries = new Map();
for (const entry of manifest.files ?? []) {
  if (entries.has(entry.path)) fail(`duplicate manifest path: ${entry.path}`);
  entries.set(entry.path, entry);
  if (!KNOWN_CLASSES.has(entry.class)) fail(`unknown class for ${entry.path}: ${entry.class}`);
  if (typeof entry.sha256 !== 'string' || entry.sha256.length !== 64) fail(`missing sha256 for ${entry.path}`);
  if (!Number.isInteger(entry.bytes) || entry.bytes < 0) fail(`missing byte length for ${entry.path}`);
  if (entry.class === 'overlay' && !/\.android\./.test(entry.path)) fail(`overlay without .android name: ${entry.path}`);
  if (entry.class === 'ios-platform' && !/\.ios\./.test(entry.path)) fail(`ios-platform without .ios name: ${entry.path}`);
  if (entry.class === 'upstream' || entry.class === 'ios-platform') {
    if (entry.baseSha256 !== undefined && entry.baseSha256 !== entry.sha256) {
      // An ios-platform file may be intentionally modified; that requires a rationale.
      if (!entry.rationale) fail(`modified ${entry.class} without rationale: ${entry.path}`);
    }
  }
  if (entry.class === 'divergence' || entry.class === 'android-only' || entry.class === 'overlay') {
    if (!entry.rationale || !entry.rationale.trim()) fail(`missing rationale for ${entry.class}: ${entry.path}`);
  }
}

const actual = await walk(canonicalRoot);
for (const p of actual) {
  if (p === 'README.md') continue;
  const entry = entries.get(p);
  if (!entry) { fail(`untracked canonical file (regenerate provenance): ${p}`); continue; }
  const bytes = readFileSync(path.join(canonicalRoot, p));
  if (bytes.length !== entry.bytes) fail(`byte length drift: ${p}`);
  if (sha256(bytes) !== entry.sha256) fail(`content drift (regenerate provenance): ${p}`);
}
const tracked = new Set([...entries.keys(), 'README.md']);
for (const p of tracked) {
  if (p === 'README.md') continue;
  if (!actual.includes(p)) fail(`manifest lists a missing file: ${p}`);
}
if (!actual.includes('README.md')) fail('canonical/README.md is missing');

const counts = {};
for (const entry of entries.values()) counts[entry.class] = (counts[entry.class] ?? 0) + 1;
if (JSON.stringify(counts) !== JSON.stringify(manifest.counts)) {
  fail(`manifest counts are stale: ${JSON.stringify(manifest.counts)} != recomputed ${JSON.stringify(counts)}`);
}

if (failures.length) {
  console.error('canonical:check failed:');
  for (const message of failures) console.error(`  - ${message}`);
  process.exit(1);
}
console.log(`canonical:check ok — ${entries.size} files pinned to ${manifest.canonical.revision.slice(0, 12)} (${JSON.stringify(counts)})`);
