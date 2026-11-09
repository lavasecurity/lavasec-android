import React from 'react';
import {fireEvent, render} from '@testing-library/react-native';

const mockNavigate = jest.fn();
const mockGoBack = jest.fn();
let mockFocused = true;
let mockReview: {app: object | null; live: {qaTools: boolean}} = {app: {}, live: {qaTools: true}};
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({navigate: mockNavigate, goBack: mockGoBack, setOptions: jest.fn()}),
  useIsFocused: () => mockFocused,
  useRoute: () => ({params: undefined}),
  usePreventRemove: jest.fn(),
}));
jest.mock('../canonical/review/navigation', () => ({
  useReviewNavigation: () => ({navigate: mockNavigate, goBack: mockGoBack, getState: () => ({routes: [], index: 0})}),
}));
jest.mock('../canonical/review/ReviewContext', () => ({...jest.requireActual('../canonical/review/ReviewContext'),useReview: () => mockReview}));
jest.mock('../canonical/review/FeedbackScreen', () => ({FeedbackScreen: () => null}));
jest.mock('../canonical/android/VpnChainingScreen', () => ({VpnChainingScreen: () => null}));
import {DeviceQAScreen} from '../canonical/review/NativePageScreen';

beforeEach(() => {
  mockNavigate.mockClear();
  mockGoBack.mockClear();
  mockFocused = true;
  mockReview = {app: {}, live: {qaTools: true}};
});

test('Device QA uses the admitted native phoneQA host and only forwards the existing chained editor destination', () => {
  const screen = render(<DeviceQAScreen/>);
  const page = screen.getByTestId('device-qa-page');
  expect(page.props.page).toBe('phoneQA');
  expect(page.props.focused).toBe(true);
  fireEvent(page, 'navigate', {nativeEvent: {destination: 'VPNChaining'}});
  expect(mockNavigate).toHaveBeenCalledTimes(1);
  expect(mockNavigate).toHaveBeenCalledWith('VPNChaining');
  for (const destination of ['Security', 'DeviceQA', 'https://lavasecurity.app/qa/', 'Upgrade']) {
    fireEvent(page, 'navigate', {nativeEvent: {destination}});
  }
  expect(mockNavigate).toHaveBeenCalledTimes(1);
  fireEvent(page, 'back');
  expect(mockGoBack).toHaveBeenCalledTimes(1);
});

test('blur is forwarded to native admission and absent QA capability renders no debug page', () => {
  const screen = render(<DeviceQAScreen/>);
  mockFocused = false;
  screen.rerender(<DeviceQAScreen/>);
  expect(screen.getByTestId('device-qa-page').props.focused).toBe(false);
  mockReview = {app: {}, live: {qaTools: false}};
  screen.rerender(<DeviceQAScreen/>);
  expect(screen.queryByTestId('device-qa-page')).toBeNull();
  mockReview = {app: null, live: {qaTools: true}};
  screen.rerender(<DeviceQAScreen/>);
  expect(screen.queryByTestId('device-qa-page')).toBeNull();
});
