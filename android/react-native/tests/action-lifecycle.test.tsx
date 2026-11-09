import React from 'react';
import {AccessibilityInfo, Alert, Text} from 'react-native';
import {act, screen, waitFor} from '@testing-library/react-native';
import {
  FakeWireOwner, completed, deferred, installLifecycleMock, mountJourney, rejected,
  type JourneyHarness, type LifecycleMock, type Reply, type Wire,
} from './interruptionHarness';
import {useAppAction, useExclusiveAppAction} from '../canonical/app/actions';
import {AppCommandDetachedError} from '../canonical/app/store';
import {useReview} from '../canonical/review/ReviewContext';
import {productFixture} from './productFixture';

jest.mock('@react-navigation/native', () => ({useIsFocused: () => true}));

let lifecycle: LifecycleMock;
let harness: JourneyHarness | undefined;
beforeEach(() => {
  lifecycle = installLifecycleMock();
  jest.spyOn(Alert, 'alert').mockImplementation(() => {});
  jest.spyOn(AccessibilityInfo, 'announceForAccessibility').mockImplementation(() => {});
});
afterEach(() => {
  harness?.unmount();
  harness = undefined;
  jest.restoreAllMocks();
});

const clearLogs = {type: 'logs.clear', kind: 'Clear domain history', surface: 'activityViewing'} as const;
const logWire = (): Wire => ({...productFixture, hasDomainHistory: true, domainHistoryCount: 5} as Wire);

function LogActionStation() {
  const run = useAppAction();
  const {live} = useReview();
  return <>
    <Text testID="history.count">{live?.domainHistoryCount ?? 'pending'}</Text>
    <Text testID="history.clear" onPress={() => run(clearLogs)}>Clear</Text>
  </>;
}

function ExclusiveActionStation() {
  const action = useExclusiveAppAction('report');
  return <>
    <Text testID="report.busy">{action.busy ? 'busy' : 'idle'}</Text>
    <Text testID="report.open" onPress={() => action.run({type: 'native.flow', flow: 'feedback'})}>Open</Text>
  </>;
}

// Model the actual race: the owner has committed SQLite effects, but the native
// reply and adapter's refreshed snapshot have not returned to the old JS caller.
function commitClearedHistory(owner: FakeWireOwner) {
  owner.commit('history.count', 0);
  owner.wire = {...owner.wire, revision: owner.wire.revision + 1, hasDomainHistory: false, domainHistoryCount: 0} as Wire;
}

test('durable log clear completing after screen reconnection cannot alert or announce on the new screen', async () => {
  const owner = new FakeWireOwner(logWire());
  const reply = deferred<Reply>();
  owner.setHandler(() => {commitClearedHistory(owner); return reply.promise;});
  harness = mountJourney({owner, lifecycle, render: () => <LogActionStation />});
  await harness.ready();
  await waitFor(() => expect(screen.getByTestId('history.count').props.children).toBe(5));
  let completion!: Promise<void>;
  act(() => {completion = screen.getByTestId('history.clear').props.onPress();});
  await waitFor(() => expect(owner.count('logs.clear')).toBe(1));
  const retiredStore = harness.store;
  const retiredSnapshot = retiredStore.getSnapshot().snapshot;
  expect(owner.durability.get('history.count')).toBe(0);

  harness.coldStart();
  await waitFor(() => expect(screen.getByTestId('history.count').props.children).toBe(0));
  await act(async () => {reply.resolve(completed('Domain history cleared.')); await completion;});

  expect(Alert.alert).not.toHaveBeenCalled();
  expect(AccessibilityInfo.announceForAccessibility).not.toHaveBeenCalled();
  expect(retiredStore.getSnapshot().snapshot).toBe(retiredSnapshot);
  expect(harness.runtime()?.domainHistoryCount).toBe(0);
  expect(owner.durability.get('history.count')).toBe(0);
  expect(owner.count('logs.clear')).toBe(1);
});

test('a native rejection arriving after reconnection belongs to the disconnected action', async () => {
  const owner = new FakeWireOwner(logWire());
  const reply = deferred<Reply>();
  owner.setHandler(() => reply.promise);
  harness = mountJourney({owner, lifecycle, render: () => <LogActionStation />});
  await harness.ready();
  await waitFor(() => expect(screen.getByTestId('history.count').props.children).toBe(5));
  let completion!: Promise<void>;
  act(() => {completion = screen.getByTestId('history.clear').props.onPress();});
  await waitFor(() => expect(owner.count('logs.clear')).toBe(1));
  harness.coldStart();
  await act(async () => {reply.reject(new Error('Native log storage failed.')); await completion;});
  expect(Alert.alert).not.toHaveBeenCalled();
  expect(AccessibilityInfo.announceForAccessibility).not.toHaveBeenCalled();
  expect(owner.durability.has('history.count')).toBe(false);
  expect(owner.count('logs.clear')).toBe(1);
});

test('an active native refusal remains visible and does not announce success', async () => {
  const owner = new FakeWireOwner(logWire());
  owner.setHandler(() => rejected('storage-write-failed'));
  harness = mountJourney({owner, lifecycle, render: () => <LogActionStation />});
  await harness.ready();
  await waitFor(() => expect(screen.getByTestId('history.count').props.children).toBe(5));
  await act(async () => {await screen.getByTestId('history.clear').props.onPress();});
  expect(Alert.alert).toHaveBeenCalledWith('Lava', 'This action could not be completed. Try again.', undefined, undefined);
  expect(AccessibilityInfo.announceForAccessibility).not.toHaveBeenCalled();
  expect(owner.durability.has('history.count')).toBe(false);
  expect(harness.runtime()?.domainHistoryCount).toBe(5);
});

test('an active successful log clear still announces its confirmed result', async () => {
  const owner = new FakeWireOwner(logWire());
  owner.setHandler(() => {commitClearedHistory(owner); return completed('Domain history cleared.');});
  harness = mountJourney({owner, lifecycle, render: () => <LogActionStation />});
  await harness.ready();
  await waitFor(() => expect(screen.getByTestId('history.count').props.children).toBe(5));
  await act(async () => {await screen.getByTestId('history.clear').props.onPress();});
  expect(AccessibilityInfo.announceForAccessibility).toHaveBeenCalledWith('Domain history cleared.');
  expect(Alert.alert).not.toHaveBeenCalled();
  expect(harness.runtime()?.domainHistoryCount).toBe(0);
});

test('AppStore rejects late committed results and never replays an old queued action after reconnection', async () => {
  const owner = new FakeWireOwner(logWire());
  const reply = deferred<Reply>();
  owner.setHandler(() => {commitClearedHistory(owner); return reply.promise;});
  harness = mountJourney({owner, lifecycle, render: () => <LogActionStation />});
  await harness.ready();
  await waitFor(() => expect(screen.getByTestId('history.count').props.children).toBe(5));
  const retiredStore = harness.store;
  // Capture rejection immediately; both promises must settle as cancellation,
  // rather than accepting a stale success or leaving queued callers suspended.
  const first = retiredStore.command(clearLogs).catch(error => error);
  const queued = retiredStore.command(clearLogs).catch(error => error);
  await waitFor(() => expect(owner.count('logs.clear')).toBe(1));
  const retiredSnapshot = retiredStore.getSnapshot().snapshot;
  harness.coldStart();
  await act(async () => {reply.resolve(completed('Domain history cleared.'));});
  expect(await first).toBeInstanceOf(AppCommandDetachedError);
  expect(await queued).toBeInstanceOf(AppCommandDetachedError);
  expect(retiredStore.getSnapshot().snapshot).toBe(retiredSnapshot);
  expect(owner.count('logs.clear')).toBe(1);
  expect(owner.durability.get('history.count')).toBe(0);
});

test.each([true, false])('exclusive action refusal is shown only while its connection is current (reconnect=%s)', async reconnect => {
  const owner = new FakeWireOwner();
  const reply = deferred<Reply>();
  owner.setHandler(() => reply.promise);
  harness = mountJourney({owner, lifecycle, render: () => <ExclusiveActionStation />});
  await harness.ready();
  let completion!: Promise<void>;
  act(() => {completion = screen.getByTestId('report.open').props.onPress();});
  await waitFor(() => expect(owner.count('native.flow')).toBe(1));
  if (reconnect) harness.coldStart();
  await act(async () => {reply.reject(new Error('The report page could not open.')); await completion;});
  if (reconnect) expect(Alert.alert).not.toHaveBeenCalled();
  else expect(Alert.alert).toHaveBeenCalledWith('Lava', 'The report page could not open.', undefined, undefined);
  expect(screen.getByTestId('report.busy').props.children).toBe('idle');
  expect(owner.count('native.flow')).toBe(1);
});
