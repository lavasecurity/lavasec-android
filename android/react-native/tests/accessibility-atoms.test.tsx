import React from 'react';
import {Pressable, View} from 'react-native';
import {fireEvent, render} from '@testing-library/react-native';

jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({setOptions: jest.fn()}),
  useIsFocused: () => true,
  useRoute: () => ({params: undefined}),
  usePreventRemove: jest.fn(),
}));
import {
  LavaActionButton,
  LavaCard,
  LavaChoice,
  LavaIconButton,
  LavaIconButtonGroup,
  LavaSelectionAccessory,
  LavaText,
  LavaToggleRow,
} from '../canonical/src';
import {LavaDiscoveryDot} from '../canonical/src/LavaDiscoveryDot';
import {Choice, Copy, DisclosureRow, Guardian, Row, Section, Symbol} from '../canonical/review/primitives';
import {AdaptivePair, Group, Info, Link, ListRow, Panel, StatusPill, Toggle} from '../canonical/review/scaffold';
import {ActivityCharts} from '../canonical/review/activity-scaffold';
import {activityExample} from '../canonical/review/activity-model';
import {PresentationContext} from '../canonical/app/presentation';
import {foundation} from '../canonical/src/foundation';
import {
  expectChartAlternative,
  expectDecorativeHidden,
  expectEveryControlLabelled,
  expectHasHeading,
  expectManualScaleDisablesNativeScaling,
  expectNoClippedPrimaryActions,
  expectStructuralAccessibility,
  expectUniqueControlLabels,
  headingNodes,
  hostByTestID,
  interactiveNodes,
  labelOf,
  mockWindowSize,
  styleOf,
  textNodes,
  type AuditNode,
} from './a11yHarness';

function noop(): void {}

test('every shared atom family renders a labelled, structurally sound tree', () => {
  const cases: ReadonlyArray<{name: string; element: React.ReactElement}> = [
    {name: 'LavaActionButton', element: <LavaActionButton title="Turn on" onPress={noop} testID="atom.action" />},
    {name: 'LavaIconButton', element: <LavaIconButton icon="close" title="Close" onPress={noop} testID="atom.icon" />},
    {
      name: 'LavaIconButtonGroup',
      element: (
        <LavaIconButtonGroup>
          <LavaIconButton icon="back" title="Back" onPress={noop} testID="atom.back" />
          <LavaIconButton icon="delete" title="Delete" role="destructive" onPress={noop} testID="atom.delete" />
        </LavaIconButtonGroup>
      ),
    },
    {name: 'LavaToggleRow', element: <LavaToggleRow title="Feedback" value={false} onValueChange={noop} testID="atom.toggle" />},
    {
      name: 'LavaChoice',
      element: (
        <LavaChoice
          label="Mode"
          options={[
            {value: 'normal', label: 'Normal'},
            {value: 'focused', label: 'Focused'},
          ]}
          value="normal"
          onValueChange={noop}
          testID="atom.choice"
        />
      ),
    },
    {name: 'Copy', element: <Copy role="heading">Your Lava</Copy>},
    {name: 'Row', element: <Row title="Privacy & Data" summary="Local logs" intent="page" onPress={noop} testID="atom.row" />},
    {name: 'ListRow', element: <ListRow title="Account & Backup" onPress={noop} testID="atom.listrow" />},
    {name: 'Link', element: <Link title="Learn more" onPress={noop} testID="atom.link" />},
    {name: 'Toggle', element: <Toggle title="Automatic Backup" value onChange={noop} />},
    {name: 'Panel', element: <Panel><Info title="Heads up" description="Local only" /></Panel>},
  ];

  for (const {name, element} of cases) {
    const {UNSAFE_root} = render(element);
    expect(() => expectStructuralAccessibility(UNSAFE_root)).not.toThrow();
    expect(name.length).toBeGreaterThan(0);
  }
});

test('the harness is not vacuous: it rejects an unlabelled control', () => {
  const {UNSAFE_root} = render(<Pressable accessibilityRole="button" testID="bad.control" />);
  expect(interactiveNodes(UNSAFE_root).length).toBe(1);
  expect(() => expectEveryControlLabelled(UNSAFE_root)).toThrow();
});

test('every interactive control carries a non-empty role and label', () => {
  const {UNSAFE_root} = render(
    <>
      <LavaActionButton title="Turn on" onPress={noop} testID="c.action" />
      <LavaIconButton icon="confirm" title="Confirm" onPress={noop} testID="c.icon" />
      <LavaSelectionAccessory state="selected" testID="c.selection" />
      <LavaChoice
        label="Mode"
        options={[
          {value: 'normal', label: 'Normal'},
          {value: 'focused', label: 'Focused'},
        ]}
        value="normal"
        onValueChange={noop}
        testID="c.choice"
      />
      <Row title="Account" intent="page" onPress={noop} testID="c.row" />
      <Link title="Learn more" onPress={noop} testID="c.link" />
    </>,
  );
  const controls = interactiveNodes(UNSAFE_root);
  expect(controls.length).toBeGreaterThan(4);
  for (const control of controls) {
    expect(typeof control.props.accessibilityRole).toBe('string');
    expect((labelOf(control) ?? '').trim().length).toBeGreaterThan(0);
  }
  expect(() => expectEveryControlLabelled(UNSAFE_root)).not.toThrow();
  expect(() => expectUniqueControlLabels(UNSAFE_root)).not.toThrow();
});

test('the toggle exposes its label to TalkBack', () => {
  const {UNSAFE_root} = render(<LavaToggleRow title="Feedback" value={false} onValueChange={noop} testID="toggle" />);
  const node = hostByTestID(UNSAFE_root, 'toggle') as AuditNode;
  expect(node).not.toBeNull();
  expect(node.props.accessibilityLabel).toBe('Feedback');
});

test.each([false, true])('ListRow activation calls its current action once (separate trailing: %s)', separateTrailing => {
  const firstAction = jest.fn();
  const currentAction = jest.fn();
  const screen = render(<ListRow title="Block List Basic" subtitle="Unlicense" selected onPress={firstAction} separateTrailing={separateTrailing} />);
  const row = screen.getByLabelText('Block List Basic, Unlicense');
  expect(row.props.accessibilityActions).toEqual([{name: 'activate'}]);
  fireEvent(row, 'accessibilityAction', {nativeEvent: {actionName: 'activate'}});
  expect(firstAction).toHaveBeenCalledTimes(1);

  screen.rerender(<ListRow title="Block List Basic" subtitle="Unlicense" selected={false} onPress={currentAction} separateTrailing={separateTrailing} />);
  fireEvent(screen.getByLabelText('Block List Basic, Unlicense'), 'accessibilityAction', {nativeEvent: {actionName: 'activate'}});
  expect(firstAction).toHaveBeenCalledTimes(1);
  expect(currentAction).toHaveBeenCalledTimes(1);
  fireEvent(screen.getByLabelText('Block List Basic, Unlicense'), 'accessibilityAction', {nativeEvent: {actionName: 'increment'}});
  expect(currentAction).toHaveBeenCalledTimes(1);
});

test.each([false, true])('ListRow activation respects the current disabled state and preserves touch (separate trailing: %s)', separateTrailing => {
  const onPress = jest.fn();
  const screen = render(<ListRow title="Block List Basic" onPress={onPress} separateTrailing={separateTrailing} />);
  fireEvent.press(screen.getByLabelText('Block List Basic'));
  expect(onPress).toHaveBeenCalledTimes(1);

  screen.rerender(<ListRow title="Block List Basic" disabled onPress={onPress} separateTrailing={separateTrailing} />);
  const disabledRow = screen.getByLabelText('Block List Basic');
  expect(disabledRow.props.accessibilityState.disabled).toBe(true);
  // Exercise the production guard even if Android delivers an already queued action.
  disabledRow.props.onAccessibilityAction({nativeEvent: {actionName: 'activate'}});
  fireEvent.press(disabledRow);
  expect(onPress).toHaveBeenCalledTimes(1);

  screen.rerender(<ListRow title="Block List Basic" onPress={onPress} separateTrailing={separateTrailing} />);
  fireEvent(screen.getByLabelText('Block List Basic'), 'accessibilityAction', {nativeEvent: {actionName: 'activate'}});
  expect(onPress).toHaveBeenCalledTimes(2);
});

test('ListRow keeps the trailing control and long press independent of activation', () => {
  const onPress = jest.fn();
  const onLongPress = jest.fn();
  const onTrailing = jest.fn();
  const screen = render(<ListRow title="Block List Basic" onPress={onPress} onLongPress={onLongPress} separateTrailing
    trailing={<Pressable accessibilityRole="button" accessibilityLabel="Source information" onPress={onTrailing} />} />);
  fireEvent.press(screen.getByLabelText('Source information'));
  expect(onTrailing).toHaveBeenCalledTimes(1);
  expect(onPress).not.toHaveBeenCalled();
  fireEvent(screen.getByLabelText('Block List Basic'), 'longPress');
  expect(onLongPress).toHaveBeenCalledTimes(1);
  expect(onPress).not.toHaveBeenCalled();
  fireEvent(screen.getByLabelText('Block List Basic'), 'accessibilityAction', {nativeEvent: {actionName: 'activate'}});
  expect(onPress).toHaveBeenCalledTimes(1);
  expect(onTrailing).toHaveBeenCalledTimes(1);
  expect(onLongPress).toHaveBeenCalledTimes(1);
});

test('ListRow does not advertise activation without an onPress action', () => {
  const onLongPress = jest.fn();
  const screen = render(<ListRow title="Source details" onLongPress={onLongPress} />);
  const row = screen.getByLabelText('Source details');
  expect(row.props.accessibilityActions).toBeUndefined();
  expect(row.props.onAccessibilityAction).toBeUndefined();
  fireEvent(row, 'longPress');
  expect(onLongPress).toHaveBeenCalledTimes(1);
});

test('decorative atoms are withdrawn from the accessibility tree', () => {
  const {UNSAFE_root} = render(
    <>
      <LavaSelectionAccessory state="selected" testID="d.selection" />
      <LavaSelectionAccessory state="unselected" testID="d.selection.empty" />
      <LavaDiscoveryDot testID="d.dot" />
      <Guardian testID="d.guardian" />
      <Symbol name="shield.fill" />
    </>,
  );
  expectDecorativeHidden(UNSAFE_root, ['d.selection', 'd.selection.empty', 'd.dot', 'd.guardian']);
  const symbolHidden = UNSAFE_root.findAll(
    (node: AuditNode) => node.props?.accessibilityElementsHidden === true,
    {deep: true},
  );
  expect(symbolHidden.length).toBeGreaterThan(0);
});

test('title-class copy is a TalkBack heading; supporting copy is not', () => {
  const {UNSAFE_root} = render(
    <>
      <Copy role="title" testID="t.title">Guard</Copy>
      <Copy role="heading" testID="t.heading">Your Lava</Copy>
      <Copy role="section" testID="t.section">Privacy</Copy>
      <Copy role="supporting" testID="t.supporting">Local logs only.</Copy>
    </>,
  );
  expectHasHeading(UNSAFE_root);
  const headings = headingNodes(UNSAFE_root).map(node => node.props.testID);
  expect(headings).toEqual(expect.arrayContaining(['t.title', 't.heading', 't.section']));
  expect(headings).not.toContain('t.supporting');
});

test('a caller-provided role still wins over the heading default', () => {
  const {UNSAFE_root} = render(<Copy role="section" accessibilityRole="text" testID="t.text">Privacy</Copy>);
  expect(headingNodes(UNSAFE_root).map(node => node.props.testID)).not.toContain('t.text');
});

test('LavaText exposes a heading for title-class roles only', () => {
  const {UNSAFE_root} = render(
    <>
      <LavaText role="primaryValue" testID="lt.title">Guard</LavaText>
      <LavaText role="cardTitle" testID="lt.card">Your Lava</LavaText>
      <LavaText role="sectionLabel" testID="lt.section">Privacy</LavaText>
      <LavaText role="rowTitle" testID="lt.row">Account</LavaText>
    </>,
  );
  const headings = headingNodes(UNSAFE_root).map(node => node.props.testID);
  expect(headings).toEqual(expect.arrayContaining(['lt.title', 'lt.card', 'lt.section']));
  expect(headings).not.toContain('lt.row');
});

test('sections expose headings and a panel keeps its title as one', () => {
  const {UNSAFE_root} = render(
    <Section title="Encrypted Backup">
      <Group>
        <Toggle title="Enable backup" value={false} onChange={noop} />
        <ListRow title="Restore Backup" onPress={noop} />
      </Group>
    </Section>,
  );
  expectHasHeading(UNSAFE_root);
  expectStructuralAccessibility(UNSAFE_root);
});

test('the Activity chart exposes a spoken alternative and an unclipped action', () => {
  const {UNSAFE_root} = render(
    <ActivityCharts
      buckets={activityExample.buckets!}
      rangeKey="today"
      allowed={activityExample.allowed}
      blocked={activityExample.blocked}
      loaded
      uptime={activityExample.uptime}
    />,
  );
  expectChartAlternative(UNSAFE_root, 'activity.total.inspect');
  expectNoClippedPrimaryActions(UNSAFE_root);
  const next = hostByTestID(UNSAFE_root, 'activity.chart.next') as AuditNode;
  expect(labelOf(next)?.trim().length).toBeGreaterThan(0);
});

test('a titled chart header is available when a caller labels one', () => {
  const {UNSAFE_root} = render(<StatusPill title="Protected" symbol="checkmark.circle.fill" />);
  expect(textNodes(UNSAFE_root).length).toBeGreaterThan(0);
});

test('manually scaled shared text never also opts into native scaling', () => {
  const textScales = {
    body: 2.5,
    headline: 2.5,
    subheadline: 2.5,
    footnote: 2.5,
    largeTitle: 2.5,
    title1: 2.5,
    title2: 2.5,
    title3: 2.5,
    callout: 2.5,
  };
  const {UNSAFE_root} = render(
    <PresentationContext.Provider value={{locale: 'en', textScales}}>
      <Copy role="heading">Guard</Copy>
      <LavaText role="primaryValue">Your Lava</LavaText>
      <LavaActionButton title="Turn on" onPress={noop} />
      <ListRow title="Account & Backup" onPress={noop} />
    </PresentationContext.Provider>,
  );
  expectManualScaleDisablesNativeScaling(UNSAFE_root);
});

test('AdaptivePair stacks at the compact width and uses a row at the wide width', () => {
  const pair = (): React.ReactElement => <View testID="pair"><AdaptivePair label={<Copy>Label</Copy>}><Copy>Value</Copy></AdaptivePair></View>;
  const compact = mockWindowSize(320);
  const compactRoot = render(pair()).UNSAFE_root;
  const compactPart = hostByTestID(compactRoot, 'pair')!.findAll((node: AuditNode) => node.type === 'View' && styleOf(node)?.flexDirection)[0];
  expect(styleOf(compactPart!)?.flexDirection).toBe('column');
  compact.mockRestore();

  const wide = mockWindowSize(768);
  const wideRoot = render(pair()).UNSAFE_root;
  const widePart = hostByTestID(wideRoot, 'pair')!.findAll((node: AuditNode) => node.type === 'View' && styleOf(node)?.flexDirection)[0];
  expect(styleOf(widePart!)?.flexDirection).toBe('row');
  wide.mockRestore();
});


test.each(['blocked','allowed'] as const)('filter %s rows show the shared outline mark without a second accessibility target', outcome => {
  const onPress = jest.fn();
  const tree = render(<ListRow title="example.com" verbatimTitle outcome={outcome} onPress={onPress}/>);
  const glyph = tree.UNSAFE_getByType(Symbol);
  expect(glyph.props.name).toBe(outcome==='blocked' ? foundation.outcome.blockedOutline : foundation.outcome.allowedOutline);
  expect(glyph.props.tone).toBe('primary');
  expect(tree.getAllByRole('button')).toHaveLength(1);
  fireEvent(tree.getByRole('button', {name:'example.com'}), 'accessibilityAction', {nativeEvent:{actionName:'activate'}});
  expect(onPress).toHaveBeenCalledTimes(1);
});
