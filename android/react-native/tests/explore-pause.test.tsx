// Focused regression for #821's Explore interruption semantics in the Android
// canonical screen: an AppState interruption pauses playback but keeps the
// painted lesson step; only a focus/route change still stops and resets it.
import React from 'react';
import {AccessibilityInfo, AppState} from 'react-native';
import {act, fireEvent, render, screen} from '@testing-library/react-native';

let mockFocused = true;
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({setOptions: jest.fn(), navigate: jest.fn(), goBack: jest.fn(), getState: () => undefined}),
  useIsFocused: () => mockFocused,
  useRoute: () => ({params: undefined, key: 'k'}),
  usePreventRemove: jest.fn(),
}));
jest.mock('react-native-safe-area-context', () => ({
  SafeAreaInsetsContext:require('react').createContext({top:59,bottom:34,left:0,right:0}),
  SafeAreaProvider: ({children}: {children: React.ReactNode}) => children,
  useSafeAreaFrame: () => ({x: 0, y: 0, width: 390, height: 844}),
  useSafeAreaInsets: () => ({top: 47, left: 0, right: 0, bottom: 34}),
}));
jest.mock('../canonical/specs/NativeLavaReview', () => ({__esModule: true, default: {stopDemo: jest.fn(), speakDemo: jest.fn().mockResolvedValue(false)}}));

let mockReviewState: {app?: unknown; live?: unknown; session: unknown; setSession: jest.Mock};
jest.mock('../canonical/review/ReviewContext', () => ({...jest.requireActual('../canonical/review/ReviewContext'),useReview: () => mockReviewState}));

import {ExploreScreen} from '../canonical/review/ExploreScreen';
import {initialSession} from '../canonical/review/session';
import {connectionDemo, connectionStages} from '../canonical/review/connection-model';

afterEach(() => { jest.restoreAllMocks(); mockFocused = true; });

test('an AppState interruption pauses the demo while keeping the painted lesson step', () => {
  const previous = AppState.currentState;
  Object.defineProperty(AppState, 'currentState', {configurable: true, value: 'active'});
  const listeners: ((state: string) => void)[] = [];
  jest.spyOn(AppState, 'addEventListener').mockImplementation((_event, callback) => { listeners.push(callback as (state: string) => void); return {remove: jest.fn()}; });
  const session = initialSession();
  mockReviewState = {app: undefined, live: undefined, session, setSession: jest.fn()};
  try {
    render(<ExploreScreen />);
    const scenes = connectionDemo(connectionStages(undefined, session));
    fireEvent.press(screen.getByTestId('explore.play'));
    fireEvent.press(screen.getByTestId('explore.next'));
    const painted = screen.getByTestId(`explore.demo.caption.${scenes[1]!.id}`);

    act(() => { listeners.forEach(listener => listener('inactive')); });
    // A background interruption stops timers/speech but must not retract the step.
    expect(screen.getByTestId(`explore.demo.caption.${scenes[1]!.id}`)).toBe(painted);

    // A real focus loss still stops and resets the lesson.
    mockFocused = false;
    screen.rerender(<ExploreScreen />);
    expect(screen.queryByTestId(`explore.demo.caption.${scenes[1]!.id}`)).toBeNull();
  } finally {
    Object.defineProperty(AppState, 'currentState', {configurable: true, value: previous});
  }
});

test('an AppState round trip does not re-announce an unchanged lesson step', () => {
  const previous = AppState.currentState;
  Object.defineProperty(AppState, 'currentState', {configurable: true, value: 'active'});
  const listeners: ((state: string) => void)[] = [];
  jest.spyOn(AppState, 'addEventListener').mockImplementation((_event, callback) => { listeners.push(callback as (state: string) => void); return {remove: jest.fn()}; });
  const announce = jest.spyOn(AccessibilityInfo, 'announceForAccessibility').mockImplementation(() => {});
  const session = initialSession();
  mockReviewState = {app: undefined, live: undefined, session, setSession: jest.fn()};
  try {
    render(<ExploreScreen />);
    fireEvent.press(screen.getByTestId('explore.play'));
    fireEvent.press(screen.getByTestId('explore.next'));
    // Playing then advancing announces each painted step once.
    const announced = announce.mock.calls.length;
    expect(announced).toBeGreaterThan(0);
    // Backgrounding pauses but keeps the step painted; returning to the foreground must not
    // make TalkBack read the unchanged caption again.
    act(() => { listeners.forEach(listener => listener('inactive')); });
    act(() => { listeners.forEach(listener => listener('active')); });
    expect(announce.mock.calls.length).toBe(announced);
  } finally {
    Object.defineProperty(AppState, 'currentState', {configurable: true, value: previous});
  }
});
