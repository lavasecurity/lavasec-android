#!/usr/bin/env node
// Canonical React Native source provenance for Android (plan 309 F02, convergence C2).
//
// The Android package reuses the canonical iOS `ReactNative/` tree with a bounded, reviewed set of
// Android platform overlays. This script derives a manifest that pins every committed canonical
// file by content hash and classifies it relative to the pinned canonical revision:
//
//   upstream      byte-identical to the canonical file at the same path
//   ios-platform  Android copy of an iOS platform file, retained under a `.ios` name so Metro
//                 resolves the extensionless import to the `.android` sibling
//   overlay       Android `.android.*` platform override
//   android-only  no canonical counterpart
//   divergence    same-path canonical file with reviewed Android edits
//
// Only `upstream` and identical `ios-platform` files carry no rationale; every other file must
// carry a non-empty reviewed rationale, so a new divergence cannot be added silently.
//
// Usage:
//   node scripts/canonical-provenance.mjs --from-checkout <ios-repo>   # regenerate the manifest
//   node scripts/canonical-provenance.mjs audit --from-checkout <ios>  # classify without writing
//
// The hermetic verifier (`check-canonical-source.mjs`, run by `npm run canonical:check`) reads only
// the committed manifest and tree, so ordinary Android CI never needs the canonical repository.

import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {mkdirSync, readFileSync, rmSync, writeFileSync} from 'node:fs';
import {readdir} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const packageRoot = path.resolve(here, '..');
const canonicalRoot = path.join(packageRoot, 'canonical');
const manifestPath = path.join(here, 'canonical-source.manifest.json');

export const MANIFEST = {
  repository: 'lavasecurity/lavasec-ios-internal',
  packageDirectory: 'ReactNative',
  license: 'AGPL-3.0-only',
};
export const ROOTS = ['app', 'assets', 'gallery', 'review', 'src', 'specs'];
// Directories that are Android-authored and have no canonical counterpart.
const ANDROID_ONLY_DIRS = ['android'];

const sha256 = buffer => createHash('sha256').update(buffer).digest('hex');
const readBytes = p => readFileSync(p);

async function walk(root) {
  const out = [];
  async function visit(dir) {
    for (const name of await readdir(dir, {withFileTypes: true})) {
      const full = path.join(dir, name.name);
      if (name.isSymbolicLink()) throw new Error(`canonical tree contains a symlink: ${full}`);
      if (name.isDirectory()) await visit(full);
      else if (name.isFile()) out.push(path.relative(root, full));
      else throw new Error(`canonical tree contains an unsupported entry: ${full}`);
    }
  }
  await visit(root);
  return out.sort();
}

function platformBase(rel) {
  // X.ios.EXT -> X.EXT ; X.android.EXT -> X.EXT or X.ios.EXT
  const ios = rel.replace(/\.ios\./, '.');
  if (ios !== rel) return ios;
  const android = rel.replace(/\.android\./, '.');
  if (android !== rel) return android;
  return null;
}

function rationaleFor(kind, rel, base) {
  if (kind === 'overlay') {
    return base
      ? `Android platform override for ${base}; reviewed leaf/route adaptation kept behind the shared contract.`
      : 'Android-only platform module with no iOS sibling; reviewed.';
  }
  if (kind === 'android-only') {
    return 'Android-only module with no canonical iOS counterpart; reviewed.';
  }
  if (kind === 'ios-platform') {
    return `Canonical iOS platform file retained under a .ios name so Android extensionless imports resolve to the .android implementation (base ${base}).`;
  }
  if (kind === 'divergence') {
    if (rel.startsWith('review/')) return 'Retained/adapted Android screen and native-owner integration; differs from the pinned iOS target. Adoption scope and remaining work are recorded in docs/android-ios-latest-sync-2026-11-09.md.';
    if (rel.startsWith('app/')) return 'Canonical app module adapted for Android (locale/action/contract projection).';
    if (rel.startsWith('src/')) return 'Shared foundation file with Android platform adaptation.';
    if (rel.startsWith('gallery/')) return 'Debug-only component-gallery fixture adapted for Android.';
    return 'Reviewed Android adaptation of a canonical file.';
  }
  return null;
}

function extractCanonical(repo, revision) {
  const dir = path.join(tmpdir(), `canonical-${revision.slice(0, 12)}-${process.pid}`);
  rmSync(dir, {recursive: true, force: true});
  mkdirSync(dir, {recursive: true});
  const roots = ROOTS.map(r => `${MANIFEST.packageDirectory}/${r}`);
  const archive = execFileSync('git', ['-C', repo, 'archive', revision, ...roots], {maxBuffer: 32 * 1024 * 1024});
  execFileSync('tar', ['-x', '-C', dir], {input: archive, stdio: ['pipe', 'ignore', 'inherit']});
  return dir;
}

async function classify(repo, revision) {
  const work = extractCanonical(repo, revision);
  const iosRoot = path.join(work, MANIFEST.packageDirectory);
  const iosFiles = new Set(await walk(iosRoot));
  const iosHash = new Map();
  for (const rel of iosFiles) iosHash.set(rel, sha256(readBytes(path.join(iosRoot, rel))));

  const rels = await walk(canonicalRoot);
  const files = [];
  for (const rel of rels) {
    if (rel === 'README.md') continue;
    const bytes = readBytes(path.join(canonicalRoot, rel));
    const sha = sha256(bytes);
    const top = rel.split(path.sep)[0];
    const entry = {path: rel.split(path.sep).join('/'), bytes: bytes.length, sha256: sha};
    if (ANDROID_ONLY_DIRS.includes(top)) {
      entry.class = 'android-only';
      entry.rationale = rationaleFor('android-only', entry.path, null);
    } else if (/\.ios\./.test(rel)) {
      // Since 2.2 the upstream tree itself has .ios siblings. Prefer that exact
      // file; older imports renamed a generic file and still use platformBase.
      const base = (iosHash.has(rel) ? rel : platformBase(rel)).split(path.sep).join('/');
      const baseSha = iosHash.get(base);
      entry.class = 'ios-platform';
      entry.base = base;
      if (baseSha) entry.baseSha256 = baseSha;
      if (baseSha !== sha) entry.rationale = rationaleFor('ios-platform', entry.path, base);
    } else if (/\.android\./.test(rel)) {
      const base = platformBase(rel).split(path.sep).join('/');
      const baseSha = iosHash.get(base) ?? null;
      entry.class = 'overlay';
      if (baseSha) { entry.base = base; entry.baseSha256 = baseSha; }
      entry.rationale = rationaleFor('overlay', entry.path, baseSha ? base : null);
    } else {
      const baseSha = iosHash.get(rel);
      if (baseSha === undefined) {
        entry.class = 'android-only';
        entry.rationale = rationaleFor('android-only', entry.path, null);
      } else if (baseSha === sha) {
        entry.class = 'upstream';
        entry.baseSha256 = baseSha;
      } else {
        entry.class = 'divergence';
        entry.base = rel.split(path.sep).join('/');
        entry.baseSha256 = baseSha;
        entry.rationale = rationaleFor('divergence', entry.path, entry.base);
      }
    }
    if (!entry.rationale && entry.class !== 'upstream' && entry.class !== 'ios-platform') {
      throw new Error(`unclassified canonical file: ${entry.path}`);
    }
    files.push(entry);
  }
  rmSync(work, {recursive: true, force: true});
  const counts = files.reduce((acc, f) => ((acc[f.class] = (acc[f.class] ?? 0) + 1), acc), {});
  return {
    schema: 1,
    generatedBy: 'scripts/canonical-provenance.mjs',
    canonical: {...MANIFEST, revision},
    roots: ROOTS,
    counts,
    files,
  };
}

const args = process.argv.slice(2);
const audit = args[0] === 'audit';
const flag = action => args.includes(action);
function flagValue(name) {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : undefined;
}
const fromCheckout = flagValue('--from-checkout');
if (!fromCheckout) {
  console.error('canonical-provenance: --from-checkout <ios-repo> is required to regenerate provenance.');
  process.exit(2);
}
const explicitRevision = flagValue('--revision');
let existingRevision;
try { existingRevision = JSON.parse(readFileSync(manifestPath, 'utf8')).canonical.revision; } catch {}
const revision = explicitRevision ?? existingRevision;
if (!revision) {
  console.error('canonical-provenance: --revision <ios-sha> is required on first generation.');
  process.exit(2);
}
const manifest = await classify(fromCheckout, revision);
if (audit) {
  console.log(JSON.stringify(manifest.counts, null, 2));
} else {
  writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`);
  console.log(`wrote ${path.relative(packageRoot, manifestPath)} (${manifest.files.length} files)`);
  console.log(JSON.stringify(manifest.counts));
}
