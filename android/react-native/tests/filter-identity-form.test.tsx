import React from 'react';
import {Alert, Modal} from 'react-native';
import {act, fireEvent, render, screen} from '@testing-library/react-native';
import {FilterIdentityFormHost, dismissFilterIdentityForm, presentFilterIdentityForm, type FilterIdentityResult} from '../canonical/android/FilterIdentityForm';

const templates = [{id: 'core', name: 'Core', emoji: '🌿'}, {id: 'custom', name: 'Custom', emoji: '🪴'}];
const rename = {kind: 'rename' as const, initialName: 'Old name', initialEmoji: '🌿'};
afterEach(() => { act(() => dismissFilterIdentityForm()); jest.restoreAllMocks(); });

function open(request: Parameters<typeof presentFilterIdentityForm>[0]): Promise<FilterIdentityResult> {
  let pending!: Promise<FilterIdentityResult>;
  act(() => { pending = presentFilterIdentityForm(request); });
  return pending;
}
async function expectPending(pending: Promise<FilterIdentityResult>) {
  let settled = false;
  void pending.then(() => { settled = true; });
  await Promise.resolve();
  expect(settled).toBe(false);
}

test('Create new is the default template and creates no typed name or write before confirmation', async () => {
  render(<FilterIdentityFormHost />);
  const pending = open({kind: 'create', templates});
  expect(screen.queryByTestId('filter.identity.name')).toBeNull();
  expect(screen.queryByTestId('filter.identity.emoji')).toBeNull();
  expect(screen.getByTestId('filter.identity.template.new').props.accessibilityState.selected).toBe(true);
  await expectPending(pending);
  fireEvent.press(screen.getByTestId('filter.identity.confirm'));
  await expect(pending).resolves.toEqual({kind: 'create', duplicateFromID: undefined});
});

test('chooses the exact saved template ID without inferring entitlement or changing its identity', async () => {
  render(<FilterIdentityFormHost />);
  const pending = open({kind: 'create', templates});
  fireEvent.press(screen.getByTestId('filter.identity.template.saved.custom'));
  expect(screen.getByTestId('filter.identity.template.saved.custom').props.accessibilityState.selected).toBe(true);
  expect(screen.getByTestId('filter.identity.template.new').props.accessibilityState.selected).toBe(false);
  fireEvent.press(screen.getByTestId('filter.identity.confirm'));
  await expect(pending).resolves.toEqual({kind: 'create', duplicateFromID: 'custom'});
});

test('captures templates when the chooser opens and cancelling a selection emits no creation', async () => {
  render(<FilterIdentityFormHost />);
  const mutable = [{id: 'original', name: 'Original'}];
  const pending = open({kind: 'create', templates: mutable});
  mutable[0] = {id: 'replacement', name: 'Replacement'};
  fireEvent.press(screen.getByTestId('filter.identity.template.saved.original'));
  expect(screen.queryByTestId('filter.identity.template.saved.replacement')).toBeNull();
  fireEvent.press(screen.getByTestId('filter.identity.cancel'));
  await expect(pending).resolves.toBeNull();
});

test('prefills and confirms both identity fields, retaining raw name for native legacy policy', async () => {
  render(<FilterIdentityFormHost />);
  const pending = open(rename);
  expect(screen.getByTestId('filter.identity.name').props.value).toBe('Old name');
  expect(screen.getByTestId('filter.identity.emoji').props.value).toBe('🌿');
  fireEvent.changeText(screen.getByTestId('filter.identity.name'), '  Focus  ');
  fireEvent.changeText(screen.getByTestId('filter.identity.emoji'), '👩🏽‍💻');
  fireEvent.press(screen.getByTestId('filter.identity.confirm'));
  await expect(pending).resolves.toEqual({kind: 'rename', name: '  Focus  ', emoji: '👩🏽‍💻'});
});

test('unchanged rename cancels without a discard prompt or write intent', async () => {
  const alert = jest.spyOn(Alert, 'alert');
  render(<FilterIdentityFormHost />);
  const pending = open(rename);
  fireEvent.press(screen.getByTestId('filter.identity.cancel'));
  await expect(pending).resolves.toBeNull();
  expect(alert).not.toHaveBeenCalled();
});

test('never confirms a blank name or missing emoji', async () => {
  render(<FilterIdentityFormHost />);
  const pending = open(rename);
  fireEvent.changeText(screen.getByTestId('filter.identity.name'), '   ');
  expect(screen.getByTestId('filter.identity.confirm').props.accessibilityState?.disabled).toBe(true);
  fireEvent.press(screen.getByTestId('filter.identity.confirm'));
  fireEvent.changeText(screen.getByTestId('filter.identity.name'), 'Name');
  fireEvent.changeText(screen.getByTestId('filter.identity.emoji'), '');
  expect(screen.getByTestId('filter.identity.confirm').props.accessibilityState?.disabled).toBe(true);
  fireEvent.press(screen.getByTestId('filter.identity.confirm'));
  await expectPending(pending);
});

test('dirty back dismissal preserves editing until the actual discard action', async () => {
  const alert = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
  render(<FilterIdentityFormHost />);
  const pending = open(rename);
  fireEvent.changeText(screen.getByTestId('filter.identity.emoji'), '🪴');
  act(() => screen.UNSAFE_getByType(Modal).props.onRequestClose());
  const buttons = alert.mock.calls[0]![2]!;
  expect(alert.mock.calls[0]![0]).toBe('Discard changes?');
  act(() => buttons.find(button => button.style === 'cancel')?.onPress?.());
  await expectPending(pending);
  expect(screen.getByTestId('filter.identity.emoji').props.value).toBe('🪴');
  act(() => buttons.find(button => button.style === 'destructive')?.onPress?.());
  await expect(pending).resolves.toBeNull();
});

test('a superseding request cancels the first form and its stale confirm cannot settle the new form', async () => {
  render(<FilterIdentityFormHost />);
  const first = open({kind: 'create', templates});
  const staleConfirm = screen.UNSAFE_getAllByProps({testID: 'filter.identity.confirm'}).find(node => typeof node.props.onPress === 'function')!.props.onPress;
  const second = open(rename);
  await expect(first).resolves.toBeNull();
  act(() => staleConfirm());
  await expectPending(second);
  fireEvent.press(screen.getByTestId('filter.identity.confirm'));
  await expect(second).resolves.toEqual({kind: 'rename', name: 'Old name', emoji: '🌿'});
});

test('a stale discard alert cannot dismiss a replacement chooser', async () => {
  const alert = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
  render(<FilterIdentityFormHost />);
  const first = open(rename);
  fireEvent.changeText(screen.getByTestId('filter.identity.name'), 'Changed');
  fireEvent.press(screen.getByTestId('filter.identity.cancel'));
  const staleDiscard = alert.mock.calls[0]![2]!.find(button => button.style === 'destructive')!.onPress!;
  const second = open({kind: 'create', templates});
  await expect(first).resolves.toBeNull();
  act(() => staleDiscard());
  await expectPending(second);
  fireEvent.press(screen.getByTestId('filter.identity.cancel'));
  await expect(second).resolves.toBeNull();
});

test('disposing the host cancels a pending request instead of stranding its command', async () => {
  const view = render(<FilterIdentityFormHost />);
  const pending = open({kind: 'create', templates});
  expect(screen.getByTestId('filter.identity.template.new')).toBeTruthy();
  view.unmount();
  await expect(pending).resolves.toBeNull();
});

test('a dismissed form does not reopen on a later mount', async () => {
  const first = render(<FilterIdentityFormHost />);
  const pending = open({kind: 'create', templates});
  fireEvent.press(screen.getByTestId('filter.identity.cancel'));
  await expect(pending).resolves.toBeNull();
  first.unmount();
  render(<FilterIdentityFormHost />);
  expect(screen.queryByTestId('filter.identity.confirm')).toBeNull();
});
