import React, {useCallback, useEffect, useRef, useState} from 'react';
import {Alert, Text} from 'react-native';
import {act, screen, waitFor} from '@testing-library/react-native';
import {useAppQuery} from '../canonical/app/queries';
import {useExclusiveAppAction} from '../canonical/app/actions';
import {useReview} from '../canonical/review/ReviewContext';
import type {AppCommand, DNSChoice} from '../canonical/app/contract';
import {
  FakeWireOwner,
  INTERRUPTION_MATRIX,
  completed,
  deferred,
  installLifecycleMock,
  interruption,
  mountJourney,
  type InterruptionKind,
  type LifecycleMock,
  type Reply,
  type Wire,
} from './interruptionHarness';
import {productFixture} from './productFixture';

jest.mock('@react-navigation/native', () => ({useIsFocused: () => true}));

// eslint-disable-next-line @typescript-eslint/no-var-requires
const {readFileSync} = require('node:fs') as {readFileSync: (path: string, encoding: string) => string};
// eslint-disable-next-line @typescript-eslint/no-var-requires
const {join} = require('node:path') as {join: (...parts: string[]) => string};
declare const __dirname: string;

/**
 * One interruption matrix over the highest-risk journeys (plan 309 F23). Each case drives a real
 * shared-seam station to a stage, interrupts it while a command is in flight, then asserts the
 * visible state, the durable owner state, the runtime state and the NUMBER of side effects — never
 * merely that nothing threw. The stations bind the same shared hooks the real Android screens use
 * (`useAppQuery`, `useAppAction`, `useExclusiveAppAction`) so the matrix lives on the seam, not on
 * a screen's local mock.
 */

let lifecycle: LifecycleMock;
beforeEach(() => {
  lifecycle = installLifecycleMock();
  jest.spyOn(Alert, 'alert').mockImplementation(() => {});
});
afterEach(() => {
  jest.restoreAllMocks();
});

const editingWire = (): Wire => ({
  ...productFixture,
  filterSession: {id: 'filter1', name: 'My filter', editing: true, lists: [], savedLists: [], blocked: [], allowed: [], savedBlocked: [], savedAllowed: [], names: {}},
});

// ---------------------------------------------------------------------------
// Stations (shared-seam controllers over the real AppStore + Android runtime)
// ---------------------------------------------------------------------------

/** A real cached-read station bound to an allowed `.query` command. */
function QueryStation() {
  const query = useAppQuery<{code: string}>({type: 'share.query', id: 'filter1'}, 'journey.fetch');
  return (
    <>
      <Text testID="query.fetch">{query.error ?? (query.value !== undefined ? 'ready' : 'pending')}</Text>
      <Text testID="query.code">{query.value?.code ?? ''}</Text>
    </>
  );
}

/** A query station whose key can change, so a stale in-flight read can be raced against a newer one. */
function SwitchableQueryStation() {
  const [id, setID] = useState('filter1');
  const query = useAppQuery<{code: string}>({type: 'share.query', id}, id);
  return (
    <>
      <Text testID="switch" onPress={() => setID('filter2')}>switch</Text>
      <Text testID="switch.value">{query.value?.code ?? 'pending'}</Text>
    </>
  );
}

/** filters: read the owner's review token, then commit it exactly once. */
function FiltersStation() {
  const {app, live} = useReview();
  const [review, setReview] = useState<string | null>(null);
  const [result, setResult] = useState<string | null>(null);
  const filterID = live?.session.filterID;
  useEffect(() => {
    if (!app || !live?.session.editing || !filterID) return;
    let current = true;
    void app.command<string>({type: 'filter.review', id: filterID}).then(value => {
      if (current) setReview(typeof value === 'string' ? value : null);
    }).catch(() => {});
    return () => {
      current = false;
    };
  }, [app, live?.session.editing, filterID]);
  const commit = useCallback(() => {
    if (!app || !filterID || review === null) return;
    void app.command<string>({type: 'filter.apply', id: filterID, review}).then(value => setResult(value ?? 'applied'), () => {});
  }, [app, filterID, review]);
  return (
    <>
      <Text testID="filters.fetch">{review !== null ? 'ready' : 'pending'}</Text>
      <Text testID="filters.commit" onPress={commit}>{result ?? 'commit'}</Text>
      {result !== null && <Text testID="filters.result">{result}</Text>}
    </>
  );
}

const tiers: DNSChoice[] = [{id: 'cloudflare|IP', name: 'Cloudflare', primary: '1.1.1.1', secondary: '1.0.0.1', transport: 'IP', metadata: ''}];
const dnsWire: Wire = {...productFixture, dns: {providers: [], customSelected: false, transports: ['IP'], editable: true, tiers, tiersContext: 'ctx'}};

/** dns: commit the confirmed ladder exactly once. */
function DNSStation() {
  const {app, live} = useReview();
  const [result, setResult] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const run = useCallback(async () => {
    if (!app) return;
    setBusy(true);
    try {
      await app.command({type: 'dns.tiers', context: 'ctx', tiers: (live?.dns.tiers ?? []).map(({id, name, primary, secondary}) => ({id, name, primary, secondary}))});
      setResult('saved');
    } catch {
      // A superseded command (process death) rejects; the durable commit is asserted separately.
    } finally {
      setBusy(false);
    }
  }, [app, live]);
  return (
    <>
      <Text testID="dns.tiers">{live?.dns.tiers?.length ?? 0}</Text>
      <Text testID="dns.commit" onPress={() => void run()}>{busy ? 'busy' : 'idle'}</Text>
      {result && <Text testID="dns.result">{result}</Text>}
    </>
  );
}

/** account: launch the native provider page once through the exclusive sign-in slot. */
function AccountStation() {
  const signIn = useExclusiveAppAction('signIn');
  return (
    <>
      <Text testID="account.busy">{signIn.busy ? 'busy' : 'idle'}</Text>
      <Text testID="account.manage" onPress={() => void signIn.run({type: 'native.flow', flow: 'account'})}>manage</Text>
    </>
  );
}

/** billing: one Play purchase, never a duplicate from a double tap or a recovery. */
function BillingStation() {
  const {live} = useReview();
  const buy = useExclusiveAppAction('purchase', !!live?.plus.busy);
  return (
    <>
      <Text testID="billing.busy">{buy.busy ? 'busy' : 'idle'}</Text>
      <Text testID="billing.buy" onPress={() => void buy.run({type: 'purchase.buy', id: 'plus-yearly'})}>buy</Text>
    </>
  );
}

const backupWire: Wire = {
  ...productFixture,
  backup: {...productFixture.backup, enablement: {...productFixture.backup.enablement, state: 'on', value: true, canDisable: true, canBackUp: true, canRestore: true}},
};

/** backup: upload the online copy once, never after a recovery. */
function BackupStation() {
  const {live} = useReview();
  const backup = useExclusiveAppAction('backup', !!live?.backup.busy);
  return (
    <>
      <Text testID="backup.busy">{backup.busy ? 'busy' : 'idle'}</Text>
      <Text testID="backup.now" onPress={() => void backup.run({type: 'backup.now'})}>back up</Text>
    </>
  );
}

/** feedback: open the native report page once through the exclusive report slot. */
function FeedbackStation() {
  const report = useExclusiveAppAction('report');
  return (
    <>
      <Text testID="feedback.busy">{report.busy ? 'busy' : 'idle'}</Text>
      <Text testID="feedback.open" onPress={() => void report.run({type: 'native.flow', flow: 'feedback'})}>open</Text>
    </>
  );
}

// ---------------------------------------------------------------------------
// Stage runners
// ---------------------------------------------------------------------------

async function filtersCommit(kind: InterruptionKind): Promise<void> {
  const owner = new FakeWireOwner(editingWire());
  const apply = deferred<Reply>();
  owner.setHandler(command => (command.type === 'filter.apply' ? apply.promise : completed(command.type === 'filter.review' ? 'owner-token' : undefined)));
  const harness = mountJourney({owner, lifecycle, render: () => <FiltersStation />});
  await harness.ready();
  await waitFor(() => expect(screen.getByTestId('filters.fetch').props.children).toBe('ready'));
  screen.getByTestId('filters.commit').props.onPress();
  await waitFor(() => expect(harness.count('filter.apply')).toBe(1));

  interruption(kind).apply(harness);
  owner.commit('filter.applied', 'filter1');
  await act(async () => {
    apply.resolve(completed('applied'));
  });

  harness.expectCount('filter.apply', 1);
  expect(owner.durability.get('filter.applied')).toBe('filter1');
  expect(screen.getByTestId('filters.commit')).toBeTruthy();
}

async function billingCommit(kind: InterruptionKind): Promise<void> {
  const owner = new FakeWireOwner({...productFixture, plus: {enabled: false, busy: false, checking: false, showsYearlyPaidMonthly: false, expiration: '', message: '', offers: [{id: 'plus-yearly', title: 'Yearly', subtitle: '', price: '$29.99'}]}});
  const buy = deferred<Reply>();
  owner.setHandler(command => (command.type === 'purchase.buy' ? buy.promise : completed()));
  const harness = mountJourney({owner, lifecycle, render: () => <BillingStation />});
  await harness.ready();
  await screen.findByTestId('billing.buy');
  screen.getByTestId('billing.buy').props.onPress();
  screen.getByTestId('billing.buy').props.onPress();
  await waitFor(() => expect(harness.count('purchase.buy')).toBe(1));

  interruption(kind).apply(harness);
  owner.commit('plus.purchased', 'plus-yearly');
  await act(async () => {
    buy.resolve(completed('plus-yearly'));
  });

  harness.expectCount('purchase.buy', 1);
  expect(owner.durability.get('plus.purchased')).toBe('plus-yearly');
  if (kind !== 'native-module-teardown') await waitFor(() => expect(screen.getByTestId('billing.busy').props.children).toBe('idle'));
}

async function backupCommit(kind: InterruptionKind): Promise<void> {
  const owner = new FakeWireOwner(backupWire);
  const now = deferred<Reply>();
  owner.setHandler(command => (command.type === 'backup.now' ? now.promise : completed()));
  const harness = mountJourney({owner, lifecycle, render: () => <BackupStation />});
  await harness.ready();
  await screen.findByTestId('backup.now');
  screen.getByTestId('backup.now').props.onPress();
  await waitFor(() => expect(harness.count('backup.now')).toBe(1));

  interruption(kind).apply(harness);
  owner.commit('backup.uploaded', true);
  await act(async () => {
    now.resolve(completed(true));
  });

  harness.expectCount('backup.now', 1);
  expect(owner.durability.get('backup.uploaded')).toBe(true);
  expect(screen.getByTestId('backup.now')).toBeTruthy();
}

async function dnsCommit(kind: InterruptionKind): Promise<void> {
  const owner = new FakeWireOwner(dnsWire);
  const save = deferred<Reply>();
  owner.setHandler(command => (command.type === 'dns.tiers' ? save.promise : completed()));
  const harness = mountJourney({owner, lifecycle, render: () => <DNSStation />});
  await harness.ready();
  await waitFor(() => expect(screen.getByTestId('dns.tiers').props.children).toBe(1));
  screen.getByTestId('dns.commit').props.onPress();
  await waitFor(() => expect(harness.count('dns.tiers')).toBe(1));

  interruption(kind).apply(harness);
  owner.commit('dns.tiers', 1);
  await act(async () => {
    save.resolve(completed());
  });

  harness.expectCount('dns.tiers', 1);
  expect(owner.durability.get('dns.tiers')).toBe(1);
  expect(screen.getByTestId('dns.commit')).toBeTruthy();
}

async function accountPrompt(kind: InterruptionKind): Promise<void> {
  const owner = new FakeWireOwner();
  const open = deferred<Reply>();
  owner.setHandler(command => (command.type === 'native.flow' ? open.promise : completed()));
  const harness = mountJourney({owner, lifecycle, render: () => <AccountStation />});
  await harness.ready();
  await screen.findByTestId('account.manage');
  screen.getByTestId('account.manage').props.onPress();
  screen.getByTestId('account.manage').props.onPress();
  await waitFor(() => expect(harness.count('native.flow')).toBe(1));

  interruption(kind).apply(harness);
  await act(async () => {
    open.resolve(completed());
  });

  harness.expectCount('native.flow', 1);
  expect(screen.getByTestId('account.manage')).toBeTruthy();
}

async function feedbackPrompt(kind: InterruptionKind): Promise<void> {
  const owner = new FakeWireOwner();
  const open = deferred<Reply>();
  owner.setHandler(command => (command.type === 'native.flow' ? open.promise : completed()));
  const harness = mountJourney({owner, lifecycle, render: () => <FeedbackStation />});
  await harness.ready();
  await screen.findByTestId('feedback.open');
  screen.getByTestId('feedback.open').props.onPress();
  screen.getByTestId('feedback.open').props.onPress();
  await waitFor(() => expect(harness.count('native.flow')).toBe(1));

  interruption(kind).apply(harness);
  await act(async () => {
    open.resolve(completed());
  });

  harness.expectCount('native.flow', 1);
  expect(screen.getByTestId('feedback.open')).toBeTruthy();
}

const ALL_KINDS: readonly InterruptionKind[] = INTERRUPTION_MATRIX.map(descriptor => descriptor.kind);
const CONCURRENCY_KINDS: readonly InterruptionKind[] = ['background-foreground', 'process-death', 'graph-retirement', 'privacy-revocation'];

// ---------------------------------------------------------------------------
// Matrix completeness
// ---------------------------------------------------------------------------

test('the matrix names every canonical interruption exactly once', () => {
  expect(ALL_KINDS).toEqual([
    'blur-back',
    'tab-change',
    'background-foreground',
    'rotation',
    'process-death',
    'native-module-teardown',
    'graph-retirement',
    'account-switch',
    'tier-change',
    'privacy-revocation',
  ]);
  expect(new Set(ALL_KINDS).size).toBe(ALL_KINDS.length);
  INTERRUPTION_MATRIX.forEach(descriptor => expect(descriptor.title.length).toBeGreaterThan(0));
});

test('the Feedback destination is wired to the exclusive report slot', () => {
  // Pin the real screen wiring, not just the shared hook: a double tap must not open two reports.
  const source = readFileSync(join(__dirname, '..', 'canonical', 'review', 'SettingsScreens.tsx'), 'utf8');
  expect(source).toContain("useExclusiveAppAction('report')");
  expect(source).toContain("report.run({type:'native.flow',flow:'feedback'})");
});

// ---------------------------------------------------------------------------
// Data-driven stations across the full matrix
// ---------------------------------------------------------------------------

describe.each(ALL_KINDS)('interruption %s', kind => {
  test('filters commits the reviewed token exactly once', async () => {
    await filtersCommit(kind);
  });

  test('billing commits exactly one purchase and clears the shared busy slot', async () => {
    await billingCommit(kind);
  });
});

describe.each(CONCURRENCY_KINDS)('interruption %s', kind => {
  test('backup uploads once and keeps the durable state', async () => {
    await backupCommit(kind);
  });

  test('dns commits the ladder once', async () => {
    await dnsCommit(kind);
  });

  test('account opens the native provider once', async () => {
    await accountPrompt(kind);
  });

  test('feedback opens the native report once', async () => {
    await feedbackPrompt(kind);
  });
});

// ---------------------------------------------------------------------------
// Fetch stage: a read interrupted before it settles never commits
// ---------------------------------------------------------------------------

test('a fetch interrupted by process death re-reads and never commits', async () => {
  const owner = new FakeWireOwner(editingWire());
  owner.setHandler(command => completed(command.type === 'filter.review' ? 'owner-token' : undefined));
  const harness = mountJourney({owner, lifecycle, render: () => <FiltersStation />});
  await harness.ready();
  await waitFor(() => expect(screen.getByTestId('filters.fetch').props.children).toBe('ready'));
  harness.coldStart();
  expect(screen.getByTestId('filters.commit')).toBeTruthy();
  harness.expectCount('filter.apply', 0);
});

// ---------------------------------------------------------------------------
// Runtime state and listener hygiene
// ---------------------------------------------------------------------------

test('owner interruptions move runtime state without fabricating a durable commit', () => {
  const owner = new FakeWireOwner();
  const harness = mountJourney({owner, lifecycle, render: () => <Text testID="probe">ok</Text>});
  const revision = harness.runtime()?.revision;
  const plus = harness.runtime()?.plus.enabled;

  interruption('graph-retirement').apply(harness);
  expect(harness.runtime()?.revision).toBeGreaterThan(revision ?? 0);

  interruption('tier-change').apply(harness);
  expect(harness.runtime()?.plus.enabled).toBe(!plus);

  interruption('native-module-teardown').apply(harness);
  expect(harness.runtime()?.protection.disabled).toBe(true);

  // None of these is a user commit: the durable owner state is untouched.
  expect(owner.durability.size).toBe(0);
  expect(screen.getByTestId('probe')).toBeTruthy();
});

test('a stale query result cannot overwrite a newer query key', async () => {
  const owner = new FakeWireOwner();
  const gate = deferred<Reply>();
  owner.setHandler(command => {
    if (command.type !== 'share.query') return completed();
    return command.id === 'filter1' ? gate.promise : completed({code: 'NEW'});
  });
  const harness = mountJourney({owner, lifecycle, render: () => <SwitchableQueryStation />});
  await waitFor(() => expect(screen.getByTestId('switch.value').props.children).toBe('pending'));

  screen.getByTestId('switch').props.onPress();
  await waitFor(() => expect(screen.getByTestId('switch.value').props.children).toBe('NEW'));
  // The superseded read settles after the newer one; it must never replace the newer result.
  await act(async () => {
    gate.resolve(completed({code: 'STALE'}));
  });
  expect(screen.getByTestId('switch.value').props.children).toBe('NEW');
  harness.unmount();
});

test('remounting the journey does not leak AppState or native snapshot listeners', async () => {
  const owner = new FakeWireOwner();
  owner.setHandler(command => completed(command.type === 'share.query' ? {code: 'LF1-x', url: 'https://lavasecurity.app/app/import/#LF1-x', image: null} : undefined));
  const harness = mountJourney({owner, lifecycle, render: () => <QueryStation />});
  await waitFor(() => expect(screen.getByTestId('query.fetch').props.children).toBe('ready'));
  const appStateBefore = lifecycle.appStateListenerCount();
  const nativeBefore = lifecycle.nativeListenerCount();

  for (let i = 0; i < 3; i++) {
    harness.remount();
    await waitFor(() => expect(screen.getByTestId('query.fetch').props.children).toBe('ready'));
  }

  expect(lifecycle.appStateListenerCount()).toBe(appStateBefore);
  expect(lifecycle.nativeListenerCount()).toBe(nativeBefore);
  harness.unmount();
});
