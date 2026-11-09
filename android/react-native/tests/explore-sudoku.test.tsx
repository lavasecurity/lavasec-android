import React from 'react';
import {Alert, AppState} from 'react-native';
import {act, fireEvent, render, screen} from '@testing-library/react-native';

// Plan 309 F20: Explore/Sudoku adopters over the canonical model.
//
// Sudoku treats the owner `sudoku` snapshot as the durable authority: a saved game is
// restored without issuing `sudoku.new`, and a new puzzle is generated ONLY on the explicit
// New action. Explore's demo narration comes from the shared `connection-model`; the screen
// owns no per-platform caption fork. Assertions are structural — Jest has no Yoga, so this
// is not device evidence (F25).
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({setOptions: jest.fn(), navigate: jest.fn(), goBack: jest.fn(), getState: () => undefined}),
  useIsFocused: () => true,
  useRoute: () => ({params: undefined, key: 'k'}),
  usePreventRemove: jest.fn(),
}));
jest.mock('react-native-safe-area-context', () => ({
  SafeAreaInsetsContext:require('react').createContext({top:59,bottom:34,left:0,right:0}),
  SafeAreaProvider: ({children}: {children: React.ReactNode}) => children,
  useSafeAreaFrame: () => ({x: 0, y: 0, width: 390, height: 844}),
  useSafeAreaInsets: () => ({top: 47, left: 0, right: 0, bottom: 34}),
}));

const mockCommand = jest.fn();
let mockReviewState: {app?: unknown; live?: unknown; session: unknown; setSession: jest.Mock};
jest.mock('../canonical/review/ReviewContext', () => ({
  ...jest.requireActual('../canonical/review/ReviewContext'),
  useReview: () => mockReviewState,
}));

import {ExploreScreen} from '../canonical/review/ExploreScreen';
import {SudokuScreen} from '../canonical/review/SudokuScreen';
import {initialSession} from '../canonical/review/session';
import {newGame, referencePuzzle} from '../canonical/review/sudoku-model';
import {activeFilterSummary, connectionDemo, connectionStages} from '../canonical/review/connection-model';
import {parseProductSnapshot} from '../src/productContract';
import {projectAndroidSnapshot} from '../canonical/android/runtime';
import {productFixture} from './productFixture';
import {configurePresentation, localized} from '../canonical/app/presentation';

// eslint-disable-next-line @typescript-eslint/no-var-requires
const {readFileSync} = require('node:fs') as {readFileSync: (path: string, encoding: string) => string};
// eslint-disable-next-line @typescript-eslint/no-var-requires
const {join} = require('node:path') as {join: (...parts: string[]) => string};
declare const __dirname: string;

afterEach(() => jest.restoreAllMocks());

/** A saved game whose first cell is a given, unlike `newGame()`'s reference board. */
const savedSudoku = (() => {
  const givens = [...referencePuzzle.givens];
  givens[0] = 7;
  return {puzzle: {givens, solution: referencePuzzle.solution}, values: Array<number>(81).fill(0), notes: Array.from({length: 81}, () => [] as number[])};
})();

/** Project the owner wire exactly as the Android runtime does, then bind the review session. */
function ownerBackedReview(): {session: unknown; live: ReturnType<typeof projectAndroidSnapshot>} {
  const wire = {
    ...productFixture,
    logging: {keepCounts: true, keepHistory: true, keepNetworkActivity: true, keepLavaGuardProgress: true},
    sudoku: savedSudoku,
  };
  const live = projectAndroidSnapshot(parseProductSnapshot(JSON.stringify(wire)) as never);
  return {live, session: {...initialSession(), sudoku: live.sudoku}};
}

function sudokuNewCalls(): number {
  return mockCommand.mock.calls.filter(call => (call[0] as {type?: string})?.type === 'sudoku.new').length;
}

test('a saved game is restored from the owner snapshot without issuing sudoku.new', () => {
  const {live, session} = ownerBackedReview();
  mockReviewState = {app: {command: mockCommand}, live, session, setSession: jest.fn()};
  render(<SudokuScreen />);
  expect(screen.getByTestId('sudoku-screen')).toBeTruthy();
  // The restored given comes from the owner snapshot, not `newGame()`'s reference board.
  expect(screen.getByTestId('sudoku-cell-0').props.accessibilityLabel).toBe('Row 1, column 1: 7, given');
  expect(sudokuNewCalls()).toBe(0);
});

test('sudoku.new is issued exactly once, only on the explicit New action', async () => {
  const alert = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
  const {live, session} = ownerBackedReview();
  mockCommand.mockResolvedValue(newGame());
  mockReviewState = {app: {command: mockCommand}, live, session, setSession: jest.fn()};
  render(<SudokuScreen />);

  // Ordinary interaction never generates a new puzzle.
  fireEvent.press(screen.getByTestId('sudoku-notes-toggle'));
  fireEvent.press(screen.getByTestId('sudoku-correctness-toggle'));
  fireEvent.press(screen.getByTestId('sudoku-cell-0'));
  expect(sudokuNewCalls()).toBe(0);

  fireEvent.press(screen.getByTestId('sudoku-refresh'));
  const buttons = alert.mock.calls[alert.mock.calls.length - 1]?.[2] as {text?: string; style?: string; onPress?: () => void}[] | undefined;
  expect(buttons?.map(button => button.text)).toEqual(['Cancel', 'New puzzle']);
  const confirm = buttons?.find(button => button.style === 'destructive');
  expect(confirm).toBeDefined();
  await act(async () => {
    confirm?.onPress?.();
  });
  expect(sudokuNewCalls()).toBe(1);
});

test('Explore demo captions are the canonical connection-model strings, not a platform fork', () => {
  AppState.currentState = 'active';
  const session = initialSession();
  mockReviewState = {app: undefined, live: undefined, session, setSession: jest.fn()};
  render(<ExploreScreen />);

  const scenes = connectionDemo(connectionStages(undefined, session));
  expect(scenes[0]!.caption.startsWith('It all starts here')).toBe(true);
  fireEvent.press(screen.getByTestId('explore.play'));
  expect(screen.getByTestId(`explore.demo.caption.${scenes[0]!.id}`).props.children).toBe(scenes[0]!.caption);
  for (let frame = 1; frame < scenes.length; frame += 1) {
    act(() => {
      fireEvent.press(screen.getByTestId('explore.next'));
    });
    expect(screen.getByTestId(`explore.demo.caption.${scenes[frame]!.id}`).props.children).toBe(scenes[frame]!.caption);
  }

  // The captions live in the shared model; the screen only selects them.
  const source = readFileSync(join(__dirname, '..', 'canonical', 'review', 'ExploreScreen.tsx'), 'utf8');
  expect(source).toContain("from './connection-model'");
  expect(source).toContain('connectionDemo(stages)');
  expect(source).not.toContain('It all starts here');
});


test('the unavailable active-filter name is localized before it enters verbatim route labels', () => {
  configurePresentation({locale:'de', textScales:null});
  try {
    const {live} = ownerBackedReview();
    const missing = {...live, filters:[]};
    expect(activeFilterSummary(missing, initialSession()).name).toBe(localized('Filter unavailable'));
    expect(activeFilterSummary(missing, initialSession()).name).not.toBe('Filter unavailable');
  } finally {configurePresentation();}
});
