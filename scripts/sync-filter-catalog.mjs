// Adopt/check a generated snapshot from one immutable canonical commit. No editable
// repository/URL override: a consumer cannot quietly redirect its source of truth.
import {existsSync, mkdirSync, readFileSync, renameSync, unlinkSync, writeFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {dirname, join, resolve} from 'node:path';
import {pathToFileURL} from 'node:url';

const repository = 'lavasecurity/lavasec-filters';
const policyKey = ids => ids == null ? 'authored' : JSON.stringify([...new Set(ids)].sort());
const pins = bytes => ({
  blob: createHash('sha1').update(`blob ${bytes.length}\0`).update(bytes).digest('hex'),
  sha256: createHash('sha256').update(bytes).digest('hex'),
});
const parseProjection = bytes => {
  const doc = JSON.parse(bytes.toString('utf8'));
  if (doc.schema_version !== 1 || !Array.isArray(doc.sources)) throw new Error('Invalid client projection');
};
function writeAtomic(path, content) {
  const temp = `${path}.${process.pid}.tmp`;
  let created = false;
  try {
    writeFileSync(temp, content, {flag: 'wx'});
    created = true;
    renameSync(temp, path);
  } finally {
    if (created && existsSync(temp)) unlinkSync(temp);
  }
}
function archivedCatalog(target, lock) {
  const index = JSON.parse(readFileSync(join(dirname(target), 'history', 'index.json'), 'utf8'));
  const snapshot = index.snapshots?.find(entry => entry.blob === lock.blob && entry.sha256 === lock.sha256 &&
    policyKey(entry.onboarding_recommended_source_ids) === policyKey(lock.onboarding_recommended_source_ids));
  const path = snapshot?.asset_path;
  if (typeof path !== 'string' || !path.startsWith('catalog/') || path.split('/').some(part => !part || part === '..')) {
    throw new Error('Interrupted adoption lacks a verified outgoing archive');
  }
  const bytes = readFileSync(join(dirname(dirname(target)), path));
  const actual = pins(bytes);
  if (actual.blob !== lock.blob || actual.sha256 !== lock.sha256) throw new Error('Outgoing archive differs from its pins');
  return bytes;
}
const readLock = (target, incomingBytes) => {
  const lock = JSON.parse(readFileSync(`${target}.source.json`, 'utf8'));
  if (lock.repository !== repository || !/^[a-f0-9]{40}$/.test(lock.commit ?? '')) throw new Error('Invalid canonical lock');
  let bytes = readFileSync(target);
  const actual = pins(bytes);
  if (lock.blob !== actual.blob || lock.sha256 !== actual.sha256) {
    // A process can stop between the two atomic replacements. Repeating the same validated
    // adoption completes that pair using its already-pinned archive; arbitrary edits still fail.
    if (!incomingBytes || !bytes.equals(incomingBytes)) throw new Error('Outgoing catalog differs from its pinned lock');
    bytes = archivedCatalog(target, lock);
  }
  if (!Array.isArray(lock.onboarding_recommended_source_ids) ||
      lock.onboarding_recommended_source_ids.some(id => typeof id !== 'string' || !id)) {
    throw new Error('Outgoing Android onboarding policy must be pinned before adoption');
  }
  return {lock, bytes};
};

/** Preserve every shipped catalog identity and its Android recommendation policy before adoption. */
export function adoptCatalog(target, bytes, ref) {
  if (!/^[a-f0-9]{40}$/.test(ref ?? '')) throw new Error('An immutable 40-character commit SHA is required');
  parseProjection(bytes);
  const {lock: outgoing, bytes: outgoingBytes} = readLock(target, bytes);
  const directory = join(dirname(target), 'history');
  const indexPath = join(directory, 'index.json');
  const index = JSON.parse(readFileSync(indexPath, 'utf8'));
  if (index.schema_version !== 1 || !Array.isArray(index.snapshots)) throw new Error('Invalid recovery history');
  const incoming = pins(bytes);
  // Archive even unchanged bytes: a later Android policy change has a distinct seed identity.
  {
    const existing = index.snapshots.find(snapshot => snapshot.blob === outgoing.blob &&
      policyKey(snapshot.onboarding_recommended_source_ids) === policyKey(outgoing.onboarding_recommended_source_ids));
    const snapshot = {
      asset_path: `catalog/history/${outgoing.blob}.json`,
      blob: outgoing.blob,
      sha256: outgoing.sha256,
      onboarding_recommended_source_ids: outgoing.onboarding_recommended_source_ids,
    };
    const archivePath = join(directory, `${outgoing.blob}.json`);
    if (existing) {
      const path = existing.asset_path;
      if (typeof path !== 'string' || !path.startsWith('catalog/') ||
          path.split('/').some(part => !part || part === '..') ||
          existing.sha256 !== outgoing.sha256 ||
          policyKey(existing.onboarding_recommended_source_ids) !== policyKey(outgoing.onboarding_recommended_source_ids) ||
          !readFileSync(join(dirname(dirname(target)), path)).equals(outgoingBytes)) {
        throw new Error('Recovery archive cannot be overwritten');
      }
    } else {
      mkdirSync(directory, {recursive: true});
      if (existsSync(archivePath) && !readFileSync(archivePath).equals(outgoingBytes)) {
        throw new Error('Recovery archive cannot be overwritten');
      }
      writeAtomic(archivePath, outgoingBytes);
      index.snapshots.push(snapshot);
      writeAtomic(indexPath, JSON.stringify(index, null, 2) + '\n');
    }
  }
  writeAtomic(target, bytes);
  writeAtomic(`${target}.source.json`, JSON.stringify({
    repository, commit: ref, ...incoming,
    onboarding_recommended_source_ids: outgoing.onboarding_recommended_source_ids,
  }, null, 2) + '\n');
  return incoming;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const args = process.argv.slice(2), target = args[0];
  const check = args.length === 2 && args[1] === '--check';
  const adopt = args.length === 3 && args[1] === '--ref';
  if (!target || (!check && !adopt)) throw new Error('Usage: sync-filter-catalog.mjs TARGET [--ref COMMIT_SHA | --check]');
  const ref = check ? readLock(target).lock.commit : args[2];
  if (!/^[a-f0-9]{40}$/.test(ref ?? '')) throw new Error('An immutable 40-character commit SHA is required');
  const response = await fetch(`https://raw.githubusercontent.com/${repository}/${ref}/dist/blocklist-catalog.json`,
    {redirect: 'error', signal: AbortSignal.timeout(30000)});
  if (!response.ok || !response.body) throw new Error('Canonical catalog unavailable');
  const chunks = []; let size = 0;
  for await (const chunk of response.body) {
    size += chunk.length;
    if (size > 1024 * 1024) throw new Error('Catalog too large');
    chunks.push(chunk);
  }
  const bytes = Buffer.concat(chunks);
  parseProjection(bytes);
  if (check) {
    if (!readFileSync(target).equals(bytes)) throw new Error('Vendored catalog differs from canonical commit; adopt it again');
  } else {
    const adopted = adoptCatalog(target, bytes, ref);
    process.stdout.write(`Update CatalogRepository and asset-test current pins: ${JSON.stringify(adopted)}\n`);
  }
}
