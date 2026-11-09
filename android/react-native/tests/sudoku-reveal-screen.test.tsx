import React from 'react';
import {fireEvent, render, screen} from '@testing-library/react-native';

// Plan 309 F20: the solved-board brush reveal is a shared Sudoku behavior, not a
// platform fork. These assertions are structural; Jest has no Yoga, so this is
// not device evidence (F25).
const mockSetOptions = jest.fn();
const mockNavigation = {setOptions: mockSetOptions, navigate: jest.fn(), goBack: jest.fn(), getState: () => undefined};
let mockFrame = {x: 0, y: 0, width: 390, height: 844};
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => mockNavigation,
  useIsFocused: () => true,
  useRoute: () => ({params: undefined, key: 'k'}),
  usePreventRemove: jest.fn(),
}));
jest.mock('react-native-safe-area-context', () => ({
  SafeAreaInsetsContext:require('react').createContext({top:59,bottom:34,left:0,right:0}),
  SafeAreaProvider: ({children}: {children: React.ReactNode}) => children,
  useSafeAreaFrame: () => mockFrame,
  useSafeAreaInsets: () => ({top: 47, left: 0, right: 0, bottom: 34}),
}));

let mockReviewState: {app?: unknown; live?: unknown; session: unknown; setSession: jest.Mock};
jest.mock('../canonical/review/ReviewContext', () => ({
  ...jest.requireActual('../canonical/review/ReviewContext'),
  useReview: () => mockReviewState,
}));

import {SudokuScreen} from '../canonical/review/SudokuScreen';
import {initialSession} from '../canonical/review/session';
import {editCell, newGame, referencePuzzle, type SudokuGame} from '../canonical/review/sudoku-model';
import {revealEmojiFor} from '../canonical/review/sudoku-reveal';

afterEach(() => {
  jest.clearAllMocks();
  mockFrame = {x: 0, y: 0, width: 390, height: 844};
});

/** A solved board whose givens are all zero, so every cell is brushable. */
function solvedGame(): SudokuGame {
  let game = newGame({...referencePuzzle, givens: Array<number>(81).fill(0)});
  referencePuzzle.solution.forEach((digit, index) => {
    game = editCell(game, index, digit);
  });
  return game;
}

function mount(game: SudokuGame) {
  const session = {...initialSession(), sudoku: game};
  mockReviewState = {app: undefined, live: undefined, session, setSession: jest.fn()};
  return render(<SudokuScreen />);
}

test('a solved board reveals one brush tile per touched cell with the game glyph', () => {
  const game = solvedGame();
  mount(game);
  fireEvent.press(screen.getByTestId('sudoku-cell-2'));
  expect(screen.getByTestId('sudoku-reveal-tile-2', {includeHiddenElements: true})).toBeTruthy();
  expect(screen.getByText(revealEmojiFor(game.puzzle.givens,game.puzzle.revealVersion), {includeHiddenElements: true})).toBeTruthy();
});

test('the reveal image is one glyph per game across a remount', () => {
  const game = solvedGame();
  const expected = revealEmojiFor(game.puzzle.givens,game.puzzle.revealVersion);
  const first = mount(game);
  fireEvent.press(screen.getByTestId('sudoku-cell-2'));
  expect(screen.getByTestId('sudoku-reveal-tile-2', {includeHiddenElements: true})).toBeTruthy();
  expect(screen.getByText(expected, {includeHiddenElements: true})).toBeTruthy();
  first.unmount();
  mount(game);
  fireEvent.press(screen.getByTestId('sudoku-cell-4'));
  expect(screen.getByTestId('sudoku-reveal-tile-4', {includeHiddenElements: true})).toBeTruthy();
  expect(screen.getByText(expected, {includeHiddenElements: true})).toBeTruthy();
});

test('the status bar is visible in portrait and hidden only in landscape', () => {
  const game = solvedGame();
  const view = mount(game);
  expect(mockSetOptions).toHaveBeenLastCalledWith({statusBarHidden: false});
  mockFrame = {x: 0, y: 0, width: 844, height: 390};
  view.rerender(<SudokuScreen />);
  expect(mockSetOptions).toHaveBeenLastCalledWith({statusBarHidden: true});
  mockFrame = {x: 0, y: 0, width: 390, height: 844};
  view.rerender(<SudokuScreen />);
  expect(mockSetOptions).toHaveBeenLastCalledWith({statusBarHidden: false});
});
