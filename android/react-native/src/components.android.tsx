import React from 'react';
import {Pressable, StyleSheet, Switch, Text, View} from 'react-native';
import type {
  LavaActionButtonProps,
  LavaCardProps,
  LavaTextProps,
  LavaTextRole,
  LavaTextTone,
  LavaToggleRowProps,
} from './contracts';

// Minimal Android role mapping built on RN core components. It mirrors the
// iOS role-based contract so screens stay platform-neutral; only this file
// owns colors and metrics.

const COLORS = {
  background: '#0b0b0c',
  card: '#18181b',
  border: '#27272a',
  primary: '#f5f5f7',
  secondary: '#a1a1aa',
  warning: '#fbbf24',
  danger: '#f87171',
  accent: '#f97316',
  accentInk: '#0b0b0c',
} as const;

const TEXT: Record<LavaTextRole, {fontSize: number; fontWeight: '400' | '500' | '600' | '700'}> = {
  title: {fontSize: 30, fontWeight: '700'},
  headline: {fontSize: 22, fontWeight: '600'},
  subheadline: {fontSize: 18, fontWeight: '600'},
  body: {fontSize: 16, fontWeight: '400'},
  caption: {fontSize: 13, fontWeight: '400'},
  metricNumeral: {fontSize: 16, fontWeight: '500'},
};

function toneColor(tone: LavaTextTone | undefined): string {
  switch (tone) {
    case 'secondary':
      return COLORS.secondary;
    case 'warning':
      return COLORS.warning;
    case 'danger':
      return COLORS.danger;
    default:
      return COLORS.primary;
  }
}

export function LavaText({children, role = 'body', tone, testID}: LavaTextProps): React.JSX.Element {
  const metric = role === 'metricNumeral';
  return (
    <Text
      testID={testID}
      allowFontScaling={!metric}
      style={[TEXT[role], {color: toneColor(tone)}, metric ? styles.metric : null]}>
      {children}
    </Text>
  );
}

export function LavaCard({children, role = 'card', testID}: LavaCardProps): React.JSX.Element {
  return (
    <View testID={testID} style={[styles.card, role === 'panel' ? styles.panel : null]}>
      {children}
    </View>
  );
}

export function LavaToggleRow({
  title,
  value,
  onValueChange,
  disabled = false,
  accessibilityHint,
  testID,
}: LavaToggleRowProps): React.JSX.Element {
  return (
    <View style={styles.toggleRow}>
      <Text
        testID={testID ? `${testID}.label` : undefined}
        style={[styles.toggleLabel, disabled ? styles.disabledText : null]}
        accessible={false}
        suppressHighlighting
        onPress={disabled ? undefined : () => onValueChange(!value)}>
        {title}
      </Text>
      <Switch
        testID={testID}
        value={value}
        onValueChange={onValueChange}
        disabled={disabled}
        accessibilityRole="switch"
        accessibilityLabel={title}
        accessibilityHint={accessibilityHint}
        accessibilityState={{disabled, checked: value}}
      />
    </View>
  );
}

function buttonStyle(role: LavaActionButtonProps['role']): object {
  switch (role) {
    case 'secondary':
      return styles.buttonSecondary;
    case 'panel':
      return styles.buttonPanel;
    default:
      return styles.buttonPrimary;
  }
}

export function LavaActionButton({
  title,
  role = 'primary',
  onPress,
  disabled = false,
  accessibilityHint,
  testID,
}: LavaActionButtonProps): React.JSX.Element {
  const secondary = role === 'secondary';
  return (
    <Pressable
      testID={testID}
      onPress={disabled ? undefined : onPress}
      disabled={disabled}
      accessibilityRole="button"
      accessibilityLabel={title}
      accessibilityHint={accessibilityHint}
      accessibilityState={{disabled}}
      style={({pressed}) => [
        styles.button,
        buttonStyle(role),
        pressed && !disabled ? styles.buttonPressed : null,
        disabled ? styles.buttonDisabled : null,
      ]}>
      <Text style={[styles.buttonLabel, {color: secondary ? COLORS.primary : COLORS.accentInk}, disabled ? styles.disabledText : null]}>
        {title}
      </Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  card: {
    backgroundColor: COLORS.card,
    borderColor: COLORS.border,
    borderRadius: 18,
    borderWidth: StyleSheet.hairlineWidth,
    gap: 16,
    padding: 20,
    width: '100%',
  },
  panel: {
    backgroundColor: COLORS.background,
  },
  metric: {
    fontVariant: ['tabular-nums'],
  },
  toggleRow: {
    alignItems: 'center',
    flexDirection: 'row',
    gap: 16,
    justifyContent: 'space-between',
  },
  toggleLabel: {
    color: COLORS.primary,
    flexShrink: 1,
    fontSize: 16,
  },
  button: {
    alignItems: 'center',
    borderRadius: 999,
    justifyContent: 'center',
    paddingHorizontal: 20,
    paddingVertical: 12,
  },
  buttonPrimary: {
    backgroundColor: COLORS.accent,
  },
  buttonPanel: {
    backgroundColor: COLORS.card,
  },
  buttonSecondary: {
    backgroundColor: 'transparent',
    borderColor: COLORS.border,
    borderWidth: StyleSheet.hairlineWidth,
  },
  buttonPressed: {
    opacity: 0.85,
  },
  buttonDisabled: {
    opacity: 0.45,
  },
  buttonLabel: {
    fontSize: 16,
    fontWeight: '600',
  },
  disabledText: {
    opacity: 0.45,
  },
});
