import React from 'react';
import {ScrollView,StyleSheet} from 'react-native';
import {act,fireEvent, render, screen, waitFor} from '@testing-library/react-native';
import type {ReviewState} from '../canonical/review/ReviewContext';
import {initialSession} from '../canonical/review/session';
import {initialPreviewDraft} from '../canonical/review/preview-model';

const mockNavigate = jest.fn();
const mockGoBack = jest.fn();
const mockCommand = jest.fn();
const mockQuery = jest.fn();
const mockSetOptions = jest.fn();
let mockReview: ReviewState;
let mockQueryResult: {value?: unknown; refreshing: boolean; error?: string};

jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({setOptions: mockSetOptions, goBack: mockGoBack}),
  useIsFocused: () => true,
  useRoute: () => ({params: {id: 'filter-a'}}),
  usePreventRemove: jest.fn(),
}));
jest.mock('../canonical/review/navigation', () => ({
  useReviewNavigation: () => ({
    navigate: mockNavigate,
    goBack: mockGoBack,
    navigateToUpgrade: (params: () => unknown) => { mockNavigate('Upgrade', typeof params === 'function' ? params() : params); },
  }),
  previewNotice: jest.fn(),
}));
jest.mock('../canonical/review/ReviewContext', () => ({...jest.requireActual('../canonical/review/ReviewContext'),useReview: () => mockReview}));
jest.mock('../canonical/review/filter-route', () => ({useFilterRoute: () => ({id: 'filter-a', ready: true})}));
jest.mock('../canonical/app/queries', () => ({useAppQuery: (...args: unknown[]) => {mockQuery(...args); return mockQueryResult;}}));

import {AddBlocklistScreen, FilterScreen} from '../canonical/review/FilterScreens';
import {PlusIntentProvider} from '../canonical/review/plus-intents';

beforeEach(() => {
  jest.clearAllMocks();
  mockCommand.mockResolvedValue(null);
  mockReview = {
    app: {command: mockCommand},
    live: {
      session: {activeFilterID: 'filter-a'},
      filters: [{id: 'filter-a', name: 'Core', count: '12', frozen: false}],
      limits: {maxFilterRules: 500_000, allowsCustomBlocklists: false},
      plus: {enabled: false},
      blocklistNames: {'source-a': 'Block List Basic'},
      blocklistMetadata: {},
      filterStatus: {title: 'Filter up to date', icon: 'checkmark.circle.fill'},
    },
    session: {...initialSession, filterID: 'filter-a', activeFilterID: 'filter-a', editing: true, blocklists: ['source-a'], savedBlocklists: ['source-a']},
    draft: initialPreviewDraft(),
    savedDraft: initialPreviewDraft(),
    setDraft: jest.fn(), setSession: jest.fn(),
  } as unknown as ReviewState;
  mockQueryResult = {
    refreshing: false,
    value: {
      sections: [{title: 'Security & Threat Intel', isCustom: false, sources: [
        {id: 'source-a', name: 'Block List Basic', licenseName: 'Unlicense', sourceURL: 'https://catalog.example/basic'},
        {id: 'source-b', name: 'Block List Malware', licenseName: 'Unlicense', sourceURL: 'https://catalog.example/malware'},
      ]}],
      count: 0, budget: 500_000, pending: 0, exceeded: false, summary: '1 of 2 lists selected',
      fraction: 0, indeterminate: true, atOrOverBudget: false,
    },
  };
});

test('the Android editing surface exposes the owner-backed bundled-list picker', () => {
  render(<FilterScreen />);
  fireEvent.press(screen.getByRole('button', {name: 'Add a blocklist'}));
  expect(mockNavigate).toHaveBeenCalledWith('AddBlocklist', {id: 'filter-a'});
});

test('selection is queried and staged locally, and closing the picker leaves the owner unchanged', () => {
  const rendered = render(<AddBlocklistScreen />);
  expect(mockQuery).toHaveBeenCalledWith({type: 'catalog.query', ids: ['source-a']}, JSON.stringify(['catalog', 'filter-a']));
  fireEvent.press(screen.getByRole('button', {name: 'Block List Malware, Unlicense'}));
  expect(mockQuery).toHaveBeenLastCalledWith({type: 'catalog.query', ids: ['source-a', 'source-b']}, JSON.stringify(['catalog', 'filter-a']));
  rendered.unmount();
  expect(mockCommand).not.toHaveBeenCalledWith(expect.objectContaining({type: 'filter.lists'}));
  expect(mockReview.session.blocklists).toEqual(['source-a']);
});

test('Save Selection submits the selected catalog IDs to the real draft command before returning', async () => {
  render(<AddBlocklistScreen />);
  fireEvent.press(screen.getByRole('button', {name: 'Block List Malware, Unlicense'}));
  fireEvent.press(screen.getByRole('button', {name: 'Save selection'}));
  await waitFor(() => expect(mockCommand).toHaveBeenCalledWith({type: 'filter.lists', id: 'filter-a', ids: ['source-a', 'source-b']}));
  expect(mockGoBack).toHaveBeenCalledTimes(1);
});

test('accessibility deselection survives a delayed budget response and equivalent native snapshots', async () => {
  const rendered = render(<AddBlocklistScreen />);
  const row = screen.getByRole('button', {name: 'Block List Basic, Unlicense'});
  fireEvent(row, 'accessibilityAction', {nativeEvent: {actionName: 'activate'}});
  expect(screen.getByRole('button', {name: 'Block List Basic, Unlicense'}).props.accessibilityState.selected).toBe(false);
  expect(mockQuery).toHaveBeenLastCalledWith({type: 'catalog.query', ids: []}, JSON.stringify(['catalog', 'filter-a']));

  mockQueryResult = {...mockQueryResult, refreshing: true};
  mockReview = {...mockReview, session: {...mockReview.session, blocklists: ['source-a']}};
  rendered.rerender(<AddBlocklistScreen />);
  expect(screen.getByRole('button', {name: 'Block List Basic, Unlicense'}).props.accessibilityState.selected).toBe(false);
  fireEvent.press(screen.getByRole('button', {name: 'Save selection'}));
  expect(mockCommand).not.toHaveBeenCalled();

  mockQueryResult = {...mockQueryResult, refreshing: false, value: {...mockQueryResult.value as object, summary: '0 of 2 lists selected'}};
  rendered.rerender(<AddBlocklistScreen />);
  expect(screen.getByRole('button', {name: 'Block List Basic, Unlicense'}).props.accessibilityState.selected).toBe(false);
  fireEvent.press(screen.getByRole('button', {name: 'Save selection'}));
  await waitFor(() => expect(mockCommand).toHaveBeenCalledWith({type: 'filter.lists', id: 'filter-a', ids: []}));
});

test('a reopened picker receives activation in its new local selection instead of the discarded sheet', () => {
  const first = render(<AddBlocklistScreen />);
  fireEvent(screen.getByRole('button', {name: 'Block List Basic, Unlicense'}), 'accessibilityAction', {nativeEvent: {actionName: 'activate'}});
  expect(screen.getByRole('button', {name: 'Block List Basic, Unlicense'}).props.accessibilityState.selected).toBe(false);
  first.unmount();

  render(<AddBlocklistScreen />);
  expect(screen.getByRole('button', {name: 'Block List Basic, Unlicense'}).props.accessibilityState.selected).toBe(true);
  fireEvent(screen.getByRole('button', {name: 'Block List Basic, Unlicense'}), 'accessibilityAction', {nativeEvent: {actionName: 'activate'}});
  expect(screen.getByRole('button', {name: 'Block List Basic, Unlicense'}).props.accessibilityState.selected).toBe(false);
  expect(mockCommand).not.toHaveBeenCalled();
});

test('a free custom-list request reaches Upgrade without issuing an unsupported owner command', () => {
  render(<PlusIntentProvider><AddBlocklistScreen /></PlusIntentProvider>);
  const options = mockSetOptions.mock.calls.at(-1)![0];
  const header = render(options.headerRight({}));
  fireEvent.press(header.getByRole('button', {name: 'Add your own blocklist'}));
  expect(mockNavigate).toHaveBeenCalledWith('Upgrade', expect.objectContaining({reason: 'customBlocklist'}));
  expect(mockCommand).not.toHaveBeenCalledWith(expect.objectContaining({type: 'filter.customList'}));
});

test('a Plus custom-list request opens the editor through the owner command', async () => {
  (mockReview as unknown as {live: {limits: unknown}}).live.limits = {
    maxAllowedDomains: 1_000, maxBlockedDomains: 1_000, maxFilterRules: 2_000_000,
    maxFilters: 50, allowsCustomBlocklists: true, allowsCustomDNS: true,
  };
  mockCommand.mockResolvedValueOnce('token-1');
  render(<AddBlocklistScreen />);
  const options = mockSetOptions.mock.calls.at(-1)![0];
  const header = render(options.headerRight({}));
  fireEvent.press(header.getByRole('button', {name: 'Add your own blocklist'}));
  await waitFor(() => expect(mockCommand).toHaveBeenCalledWith({type: 'filter.customList', id: 'filter-a', ids: ['source-a']}));
  await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith('CustomEntry', {id: 'token-1', kind: 'blocklist'}));
});

test('missing catalog evidence cannot enable a fabricated selection save', () => {
  mockQueryResult = {refreshing: false};
  render(<AddBlocklistScreen />);
  expect(screen.getAllByText('Loading blocklists…').length).toBeGreaterThan(0);
  fireEvent.press(screen.getByRole('button', {name: 'Save selection'}));
  expect(mockCommand).not.toHaveBeenCalled();
  expect(mockGoBack).not.toHaveBeenCalled();
});


test('the actual blocklist picker retains unfiltered categories and the pinned native search through no matches and recovery',async()=>{
  const view=render(<AddBlocklistScreen/>);await act(async()=>{});
  const search=screen.getByLabelText('Search blocklists or categories'),category=screen.getByRole('button',{name:'Security & threat intel'}),controls=screen.getByTestId('catalog.controls');
  const categoryStyle=StyleSheet.flatten(category.props.style),searchStyle=StyleSheet.flatten(search.props.style);
  fireEvent(search,'change',{nativeEvent:{text:'lava-catalog-no-match'}});
  expect(screen.getByText('No blocklists found')).toBeOnTheScreen();
  expect(screen.queryByRole('button',{name:'Block List Basic, Unlicense'})).toBeNull();
  expect(screen.getByRole('button',{name:'Security & threat intel'})).toBe(category);
  expect(screen.getByLabelText('Search blocklists or categories')).toBe(search);expect(screen.getByTestId('catalog.controls')).toBe(controls);
  expect(StyleSheet.flatten(category.props.style)).toEqual(categoryStyle);expect(StyleSheet.flatten(search.props.style)).toEqual(searchStyle);
  const results=screen.getByTestId('sheet.results'),pinned=screen.getByTestId('sheet.pinned-header');
  expect(screen.UNSAFE_getAllByType(ScrollView).find(scroll=>!scroll.props.horizontal)?.props.testID).toBe('sheet.results');
  expect(results.props.stickyHeaderIndices).toBeUndefined();
  expect(StyleSheet.flatten(pinned.props.style)).toMatchObject({position:'absolute',top:0});
  for(const node of [controls,search,category]){
    const ancestors:Array<typeof node>=[];
    for(let ancestor=node.parent;ancestor;ancestor=ancestor.parent)ancestors.push(ancestor);
    expect(ancestors.includes(pinned)).toBe(true);expect(ancestors.includes(results)).toBe(false);
  }
  expect(mockCommand).not.toHaveBeenCalled();
  fireEvent(search,'change',{nativeEvent:{text:''}});
  expect(screen.queryByText('No blocklists found')).toBeNull();expect(screen.getByRole('button',{name:'Block List Basic, Unlicense'})).toBeOnTheScreen();
  expect(screen.getByRole('button',{name:'Security & threat intel'})).toBe(category);expect(screen.getByLabelText('Search blocklists or categories')).toBe(search);
  expect(mockReview.setSession).not.toHaveBeenCalled();view.unmount();
});
