// Shared accessibility / responsive audit harness (plan 309 F22).
//
// The harness reads the RENDERED element tree structurally — roles, labels,
// hidden flags, content-container widths and text-scale opt-ins — never a
// screenshot or a measured pixel rectangle. Jest has no Yoga layout engine, so
// "without clipping" is asserted through the properties that CAUSE clipping (a
// finite `numberOfLines` on primary-action copy, a pinned content width, manual
// scaling that also opts into native scaling) rather than a bounding box.
//
// Real TalkBack order/state evidence and pixel geometry remain F25 device work.
import {Dimensions, StyleSheet} from 'react-native';
import type {RenderResult} from '@testing-library/react-native';

// `@types/react-test-renderer` is not a dependency, so the tree node type is
// taken from RNTL's own `RenderResult` instead of importing ReactTestInstance.
export type AuditNode = RenderResult['UNSAFE_root'];

/** Roles that are an actionable/stateful stop for TalkBack (excludes decorative/text/header). */
export const INTERACTIVE_ROLES: ReadonlySet<string> = new Set([
  'button',
  'link',
  'search',
  'imagebutton',
  'switch',
  'checkbox',
  'radio',
  'radiogroup',
  'tab',
  'tablist',
  'togglebutton',
  'menuitem',
  'spinbutton',
  'adjustable',
  'progressbar',
  'combobox',
]);

/** Every host (rendered) node in the tree; composite wrappers are excluded so props read once. */
export function hostNodes(root: AuditNode): AuditNode[] {
  return root.findAll((node: AuditNode) => typeof node.type === 'string', {deep: true});
}

/** The first host node carrying a testID, or null. */
export function hostByTestID(root: AuditNode, testID: string): AuditNode | null {
  const matches = hostNodes(root).filter((node: AuditNode) => node.props?.testID === testID);
  return matches.length > 0 ? (matches[0] as AuditNode) : null;
}

export function nodesWithRole(root: AuditNode): AuditNode[] {
  return hostNodes(root).filter(node => typeof node.props?.accessibilityRole === 'string');
}

/** Every node that is an interactive stop (a role AND not explicitly withdrawn). */
export function interactiveNodes(root: AuditNode): AuditNode[] {
  return nodesWithRole(root).filter(
    node => INTERACTIVE_ROLES.has(node.props.accessibilityRole) && node.props.accessible !== false,
  );
}

export function headingNodes(root: AuditNode): AuditNode[] {
  return hostNodes(root).filter(
    node => node.props?.accessibilityRole === 'header' && node.props?.accessible !== false,
  );
}

export function textNodes(root: AuditNode): AuditNode[] {
  return root.findAll((node: AuditNode) => node.type === 'Text', {deep: true});
}

export function labelOf(node: AuditNode): string | undefined {
  const label = node.props?.accessibilityLabel;
  return typeof label === 'string' ? label : undefined;
}

function nodeTestID(node: AuditNode): string | undefined {
  return typeof node.props?.testID === 'string' ? node.props.testID : undefined;
}

/** True when a node (and its descendants) is withdrawn from the accessibility tree. */
export function isHiddenFromAccessibility(node: AuditNode): boolean {
  return (
    node.props?.accessibilityElementsHidden === true ||
    node.props?.importantForAccessibility === 'no-hide-descendants'
  );
}

function describeNode(node: AuditNode): string {
  const testID = nodeTestID(node) ?? '<no testID>';
  const role = typeof node.props?.accessibilityRole === 'string' ? node.props.accessibilityRole : '<no role>';
  return `${testID} (role=${role})`;
}

/** Assert every interactive node carries a non-empty, human-readable label. */
export function expectEveryControlLabelled(root: AuditNode): void {
  const unlabelled = interactiveNodes(root).filter(node => (labelOf(node)?.trim() ?? '').length === 0);
  expect(unlabelled.map(describeNode)).toEqual([]);
}

/**
 * Assert no two interactive nodes share a label within the audited scope.
 * Duplicate labels make a TalkBack swipe list ambiguous ("Back, Back, Delete").
 */
export function expectUniqueControlLabels(root: AuditNode): void {
  const seen = new Set<string>();
  const duplicates = new Set<string>();
  for (const node of interactiveNodes(root)) {
    const label = labelOf(node)?.trim();
    if (label === undefined || label.length === 0) continue;
    if (seen.has(label)) duplicates.add(label);
    seen.add(label);
  }
  expect([...duplicates]).toEqual([]);
}

/** Assert the tree exposes at least one section heading for heading navigation. */
export function expectHasHeading(root: AuditNode): void {
  expect(headingNodes(root).length).toBeGreaterThan(0);
}

/**
 * Assert a primary action is not clipped: no text descendant of an interactive
 * control pins a finite `numberOfLines`. Localized labels may be longer than the
 * source, and a truncated button label is a navigation dead end for TalkBack.
 */
export function expectNoClippedPrimaryActions(root: AuditNode): void {
  const clipped: string[] = [];
  for (const control of interactiveNodes(root)) {
    const descendants = control.findAll((node: AuditNode) => node.type === 'Text', {deep: true});
    if (descendants.some((node: AuditNode) => typeof node.props?.numberOfLines === 'number')) {
      clipped.push(describeNode(control));
    }
  }
  expect(clipped).toEqual([]);
}

/** Assert no text node clips itself to a finite line count anywhere in scope. */
export function expectNoClippedText(root: AuditNode): void {
  const clipped = textNodes(root).filter(node => {
    const lines = node.props?.numberOfLines;
    return typeof lines === 'number' && lines > 0;
  });
  expect(clipped.map(describeNode)).toEqual([]);
}

/**
 * Assert manually-scaled text never also opts into native font scaling, which
 * would multiply the two scale paths. The canonical `Text` applies an override
 * scale and disables native scaling; under an active override every shared text
 * node must therefore report `allowFontScaling === false`.
 */
export function expectManualScaleDisablesNativeScaling(root: AuditNode): void {
  const doubled = textNodes(root).filter(node => node.props?.allowFontScaling !== false);
  expect(doubled.map(describeNode)).toEqual([]);
}

/**
 * Assert a chart / position control exposes a spoken alternative: a labelled,
 * non-decorative node with a non-empty `accessibilityValue.text` and an
 * adjustable/progress role.
 */
export function expectChartAlternative(root: AuditNode, testID: string): void {
  const node = hostByTestID(root, testID);
  expect(node).not.toBeNull();
  const audit = node as AuditNode;
  expect(isHiddenFromAccessibility(audit)).toBe(false);
  expect(['adjustable', 'progressbar']).toContain(audit.props.accessibilityRole);
  expect((labelOf(audit) ?? '').trim().length).toBeGreaterThan(0);
  expect(String(audit.props.accessibilityValue?.text ?? '').trim().length).toBeGreaterThan(0);
}

/**
 * Assert each named node is present and withdrawn from the accessibility tree.
 * A decorative leaf is withdrawn either by an explicit hidden flag or by
 * `accessible={false}`, and must not expose an interactive descendant.
 */
export function expectDecorativeHidden(root: AuditNode, testIDs: readonly string[]): void {
  for (const testID of testIDs) {
    const node = hostByTestID(root, testID);
    expect(node).not.toBeNull();
    const audit = node as AuditNode;
    const withdrawn = isHiddenFromAccessibility(audit) || audit.props?.accessible === false;
    expect(withdrawn).toBe(true);
    expect(interactiveNodes(audit)).toEqual([]);
  }
}

/**
 * Render-wide structural audit shared by the atom suite and the representative
 * screen suite: labelled controls, unique labels and unclipped primary actions.
 */
export function expectStructuralAccessibility(root: AuditNode): void {
  expectEveryControlLabelled(root);
  expectUniqueControlLabels(root);
  expectNoClippedPrimaryActions(root);
}

/**
 * Pin the window size for the next render(s). `useWindowDimensions` reads
 * `Dimensions.get('window')` on mount, so spying there drives compact/wide and
 * enlarged-text renders without a real device.
 */
export function mockWindowSize(width: number, fontScale = 1, height = 800): jest.SpyInstance {
  return jest.spyOn(Dimensions, 'get').mockReturnValue({width, height, scale: 1, fontScale});
}

/** The flattened style of a host node, for structural width/text assertions. */
export function styleOf(node: AuditNode): Record<string, unknown> | undefined {
  return StyleSheet.flatten(node.props?.style) as Record<string, unknown> | undefined;
}
