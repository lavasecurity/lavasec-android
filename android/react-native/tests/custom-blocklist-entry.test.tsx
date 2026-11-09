import React from 'react';
import {fireEvent, render, screen, waitFor} from '@testing-library/react-native';

const mockNavigate = jest.fn();
const mockGoBack = jest.fn();
const mockCommand = jest.fn();
const mockRoute: {params: {id: string; kind: 'dns' | 'blocklist'}} = {params: {id: 'token-1', kind: 'blocklist'}};
const mockApp = {command: mockCommand};

jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({setOptions: jest.fn(), goBack: mockGoBack}),
  useIsFocused: () => true,
  useRoute: () => mockRoute,
  usePreventRemove: jest.fn(),
}));
jest.mock('../canonical/review/navigation', () => ({
  useReviewNavigation: () => ({navigate: mockNavigate, goBack: mockGoBack}),
}));
jest.mock('../canonical/review/ReviewContext', () => ({...jest.requireActual('../canonical/review/ReviewContext'),useReview: () => ({app: mockApp})}));

import {CustomBlocklistEntryScreen} from '../canonical/android/CustomBlocklistEntry';

beforeEach(() => {
  jest.clearAllMocks();
  mockCommand.mockResolvedValue('custom-2');
  mockRoute.params.id = 'token-1';
});

test('collects the name and address and saves through filter.addCustomList', async () => {
  render(<CustomBlocklistEntryScreen />);
  fireEvent.changeText(screen.getByTestId('custom.blocklist.name'), ' My list ');
  fireEvent.changeText(screen.getByTestId('custom.blocklist.url'), ' https://example.com/list.txt ');
  fireEvent.press(screen.getByRole('button', {name: 'Add blocklist'}));
  await waitFor(() => expect(mockCommand).toHaveBeenCalledWith({
    type: 'filter.addCustomList', token: 'token-1', name: 'My list', url: 'https://example.com/list.txt', format: 'auto',
  }));
  await waitFor(() => expect(mockGoBack).toHaveBeenCalled());
});

test('keeps the save control inert until an address is entered', () => {
  render(<CustomBlocklistEntryScreen />);
  expect(screen.getByRole('button', {name: 'Add blocklist'}).props.accessibilityState.disabled).toBe(true);
  fireEvent.changeText(screen.getByTestId('custom.blocklist.name'), 'My list');
  fireEvent.press(screen.getByRole('button', {name: 'Add blocklist'}));
  expect(mockCommand).not.toHaveBeenCalledWith(expect.objectContaining({type: 'filter.addCustomList'}));
});

test('defaults to auto and sends the format the user chooses', async () => {
  render(<CustomBlocklistEntryScreen />);
  expect(screen.getByTestId('custom.blocklist.format.auto').props.accessibilityState.selected).toBe(true);
  fireEvent.press(screen.getByTestId('custom.blocklist.format.hosts'));
  expect(screen.getByTestId('custom.blocklist.format.hosts').props.accessibilityState.selected).toBe(true);
  fireEvent.changeText(screen.getByTestId('custom.blocklist.url'), 'https://example.com/list.txt');
  fireEvent.press(screen.getByRole('button', {name: 'Add blocklist'}));
  await waitFor(() => expect(mockCommand).toHaveBeenCalledWith({
    type: 'filter.addCustomList', token: 'token-1', name: '', url: 'https://example.com/list.txt', format: 'hosts',
  }));
});

test('validates the address before saving', async () => {
  render(<CustomBlocklistEntryScreen />);
  fireEvent.changeText(screen.getByTestId('custom.blocklist.url'), 'ftp://example.com/list.txt');
  fireEvent.press(screen.getByRole('button', {name: 'Add blocklist'}));
  expect(await screen.findByText('Blocklist addresses must start with https://.')).toBeTruthy();
  expect(mockCommand).not.toHaveBeenCalledWith(expect.objectContaining({type: 'filter.addCustomList'}));
});

test('surfaces a refused owner save and preserves the open editor', async () => {
  mockCommand.mockImplementation((request: {type: string}) => request.type === 'filter.addCustomList'
    ? Promise.reject(new Error('That custom URL is already added.'))
    : Promise.resolve('custom-2'));
  render(<CustomBlocklistEntryScreen />);
  fireEvent.changeText(screen.getByTestId('custom.blocklist.url'), 'https://example.com/list.txt');
  fireEvent.press(screen.getByRole('button', {name: 'Add blocklist'}));
  expect(await screen.findByText('That custom blocklist is already added.')).toBeTruthy();
  expect(mockGoBack).not.toHaveBeenCalled();
});

test('dismisses the editor session on teardown', async () => {
  const view = render(<CustomBlocklistEntryScreen />);
  view.unmount();
  await waitFor(() => expect(mockCommand).toHaveBeenCalledWith({type: 'customEntry.dismiss', id: 'token-1'}));
});
