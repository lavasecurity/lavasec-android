import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createHash} from 'node:crypto';
import {adoptCatalog} from './sync-filter-catalog.mjs';

const projection = name => Buffer.from(JSON.stringify({schema_version: 1, categories: [], sources: [], name}) + '\n');
const pins = bytes => ({
  blob: createHash('sha1').update(`blob ${bytes.length}\0`).update(bytes).digest('hex'),
  sha256: createHash('sha256').update(bytes).digest('hex'),
});
function fixture(run) {
  const root = mkdtempSync(join(tmpdir(), 'catalog-adoption-'));
  const target = join(root, 'catalog', 'blocklist-catalog.json');
  mkdirSync(join(root, 'catalog', 'history'), {recursive: true});
  const first = projection('first');
  writeFileSync(target, first);
  writeFileSync(`${target}.source.json`, JSON.stringify({repository: 'lavasecurity/lavasec-filters', commit: '1'.repeat(40),
    ...pins(first), onboarding_recommended_source_ids: ['android-recommendation']}));
  writeFileSync(join(root, 'catalog', 'history', 'index.json'), JSON.stringify({schema_version: 1, snapshots: []}));
  try {run({root, target, first});} finally {rmSync(root, {recursive: true, force: true});}
}

test('successive adoptions retain all prior exact bytes and onboarding policies; retries are idempotent', () => fixture(({root, target, first}) => {
  const second = projection('second'), third = projection('third');
  adoptCatalog(target, second, '2'.repeat(40));
  adoptCatalog(target, second, '2'.repeat(40));
  adoptCatalog(target, third, '3'.repeat(40));
  const history = JSON.parse(readFileSync(join(root, 'catalog', 'history', 'index.json')));
  assert.equal(history.snapshots.length, 2);
  for (const [position, bytes] of [first, second].entries()) {
    const snapshot = history.snapshots[position];
    assert.equal(snapshot.blob, pins(bytes).blob);
    assert.equal(snapshot.sha256, pins(bytes).sha256);
    assert.deepEqual(snapshot.onboarding_recommended_source_ids, ['android-recommendation']);
    assert.deepEqual(readFileSync(join(root, snapshot.asset_path)), bytes);
  }
  assert.deepEqual(readFileSync(target), third);
  assert.equal(JSON.parse(readFileSync(`${target}.source.json`)).blob, pins(third).blob);
}));

test('an edited outgoing catalog is refused without replacing the catalog or history', () => fixture(({root, target}) => {
  const edited = projection('uncommitted edit');
  writeFileSync(target, edited);
  assert.throws(() => adoptCatalog(target, projection('new'), '2'.repeat(40)), /pinned lock/);
  assert.deepEqual(readFileSync(target), edited);
  assert.equal(JSON.parse(readFileSync(join(root, 'catalog', 'history', 'index.json'))).snapshots.length, 0);
}));

test('invalid incoming data is refused before any outgoing file changes', () => fixture(({root, target, first}) => {
  assert.throws(() => adoptCatalog(target, Buffer.from('{"schema_version":2}'), '2'.repeat(40)), /Invalid client/);
  assert.deepEqual(readFileSync(target), first);
  assert.equal(JSON.parse(readFileSync(join(root, 'catalog', 'history', 'index.json'))).snapshots.length, 0);
}));

test('tampered historical archives are refused without replacing the current catalog', () => fixture(({root, target, first}) => {
  const second = projection('second');
  adoptCatalog(target, second, '2'.repeat(40));
  // Re-adopting an older supported catalog is allowed, but cannot mutate its recovery proof.
  adoptCatalog(target, first, '1'.repeat(40));
  writeFileSync(join(root, 'catalog', 'history', `${pins(first).blob}.json`), projection('tampered'));
  assert.throws(() => adoptCatalog(target, second, '2'.repeat(40)), /cannot be overwritten/);
  assert.deepEqual(readFileSync(target), first);
}));

test('unchanged bytes retain distinct outgoing policies before a policy-only update', () => fixture(({root, target, first}) => {
  adoptCatalog(target, first, '1'.repeat(40));
  const lockPath = `${target}.source.json`;
  const lock = JSON.parse(readFileSync(lockPath));
  lock.onboarding_recommended_source_ids = ['new-android-recommendation'];
  writeFileSync(lockPath, JSON.stringify(lock));
  adoptCatalog(target, first, '1'.repeat(40));
  adoptCatalog(target, first, '1'.repeat(40));
  const history = JSON.parse(readFileSync(join(root, 'catalog', 'history', 'index.json')));
  assert.equal(history.snapshots.length, 2);
  assert.deepEqual(history.snapshots.map(entry => entry.onboarding_recommended_source_ids),
    [['android-recommendation'], ['new-android-recommendation']]);
  assert.equal(history.snapshots[0].blob, history.snapshots[1].blob);
  for (const snapshot of history.snapshots) assert.deepEqual(readFileSync(join(root, snapshot.asset_path)), first);
}));

test('a legacy authored policy does not block a later explicit-policy forward adoption', () => fixture(({root, target, first}) => {
  const identity = pins(first);
  const path = `catalog/history/${identity.blob}.json`;
  writeFileSync(join(root, path), first);
  writeFileSync(join(root, 'catalog', 'history', 'index.json'), JSON.stringify({schema_version: 1, snapshots: [{
    asset_path: path, ...identity, onboarding_recommended_source_ids: null,
  }]}));
  const second = projection('second');
  adoptCatalog(target, first, '1'.repeat(40));
  adoptCatalog(target, second, '2'.repeat(40));
  const history = JSON.parse(readFileSync(join(root, 'catalog', 'history', 'index.json')));
  assert.equal(history.snapshots.length, 2);
  assert.equal(history.snapshots[0].onboarding_recommended_source_ids, null);
  assert.deepEqual(history.snapshots[1].onboarding_recommended_source_ids, ['android-recommendation']);
  assert.deepEqual(readFileSync(target), second);
}));

test('repeating an interrupted catalog replacement finishes from the pinned outgoing archive', () => fixture(({root, target, first}) => {
  adoptCatalog(target, first, '1'.repeat(40));
  const second = projection('second');
  // Simulate a stop after replacing target, before replacing its lock.
  writeFileSync(target, second);
  adoptCatalog(target, second, '2'.repeat(40));
  const lock = JSON.parse(readFileSync(`${target}.source.json`));
  assert.equal(lock.commit, '2'.repeat(40));
  assert.equal(lock.blob, pins(second).blob);
  assert.equal(lock.sha256, pins(second).sha256);
  assert.deepEqual(readFileSync(join(root, 'catalog', 'history', `${pins(first).blob}.json`)), first);
  assert.deepEqual(readFileSync(target), second);
}));
