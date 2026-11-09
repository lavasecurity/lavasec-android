import React, {useCallback, useEffect, useRef, useState} from 'react';
import {ActivityIndicator, StyleSheet, View} from 'react-native';
import {LavaActionButton, LavaCard, LavaText, LavaToggleRow} from './components';
import {
  chainedReasonCopy,
  getChainedStatus,
  setChainedEnabled,
  subscribeChainedActive,
  subscribeChainedComposition,
  subscribeChainedProtection,
  type LavaChainedStatus,
} from './NativeLavaApp';
import {presentChainedUpstream} from './chainedUpstreamViewModel';

export interface ChainedUpstreamScreenProps {
  /** Navigation entry back to the screen that opened this one. */
  onNavigateBack?: () => void;
}

function messageOf(error: unknown): string {
  if (error instanceof Error && error.message.length > 0) {
    return error.message;
  }
  return 'Chained upstream could not be reached.';
}

/**
 * Android chained-upstream surface.
 *
 * Engine-backed: it renders the authoritative status returned by `NativeLavaApp` and never
 * assumes a toggle succeeded. `setChainedEnabled` reports `accepted` (the preference commit)
 * rather than post-command readiness, so a successful disable is presented as success; after
 * every command the screen re-reads the status, and it also refreshes when the native runtime
 * reports the live chained-activity flag changed, so a settling session leaves Recovering.
 */
export function ChainedUpstreamScreen({
  onNavigateBack,
}: ChainedUpstreamScreenProps): React.JSX.Element {
  const [status, setStatus] = useState<LavaChainedStatus | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const mounted = useRef(true);
  // Monotonic request id: a refresh issued before a toggle/reload can resolve after the one that
  // followed it, so only the LATEST request may update state. Without this an older read capturing
  // the pre-toggle preference could overwrite the newer one and strand the screen on the wrong
  // toggle/runtime state if no further activity event arrives.
  const refreshSequence = useRef(0);

  const refresh = useCallback(async (): Promise<void> => {
    const request = refreshSequence.current + 1;
    refreshSequence.current = request;
    try {
      const next = await getChainedStatus();
      if (!mounted.current || refreshSequence.current !== request) {
        return;
      }
      setStatus(next);
      setLoadError(null);
    } catch (error) {
      if (!mounted.current || refreshSequence.current !== request) {
        return;
      }
      setLoadError(messageOf(error));
    }
  }, []);

  useEffect(() => {
    mounted.current = true;
    void refresh();
    return () => {
      mounted.current = false;
    };
  }, [refresh]);

  useEffect(() => {
    // The engine establishes asynchronously after the preference/reload, so the initial read can
    // report Recovering. Refresh whenever the native runtime reports the live chained-activity flag
    // changed (letting a settling screen leave Recovering or show a fallback), whenever the
    // protection mode changes (off→awaiting/starting, or an intentional pause), and whenever the
    // per-generation composition is installed/rotates (the unlocked graph is built asynchronously,
    // and installing it with protection off emits neither of the other two).
    const unsubscribeActivity = subscribeChainedActive(() => {
      void refresh();
    });
    const unsubscribeProtection = subscribeChainedProtection(() => {
      void refresh();
    });
    const unsubscribeComposition = subscribeChainedComposition(() => {
      void refresh();
    });
    return () => {
      unsubscribeActivity();
      unsubscribeProtection();
      unsubscribeComposition();
    };
  }, [refresh]);

  const handleToggle = useCallback(
    async (next: boolean): Promise<void> => {
      if (busy) {
        return;
      }
      setBusy(true);
      setNotice(null);
      try {
        const result = await setChainedEnabled(next);
        if (!result.accepted) {
          // The toggle result carries a stable reason code, never UI copy; map it so a code is
          // never rendered directly.
          setNotice(
            result.reason != null
              ? chainedReasonCopy(result.reason)
              : 'Chained upstream could not be changed.',
          );
        }
        await refresh();
      } catch (error) {
        setNotice(messageOf(error));
        await refresh();
      } finally {
        if (mounted.current) {
          setBusy(false);
        }
      }
    },
    [busy, refresh],
  );

  const view = presentChainedUpstream(status, {loadError, notice, busy});

  return (
    <View style={styles.screen} testID="chainedUpstream.screen">
      <View style={styles.header}>
        <LavaText role="headline">Chained upstream</LavaText>
        {onNavigateBack ? (
          <LavaActionButton
            title="Back"
            role="secondary"
            onPress={onNavigateBack}
            testID="chainedUpstream.back"
            accessibilityHint="Returns to the previous screen"
          />
        ) : null}
      </View>

      <LavaCard testID="chainedUpstream.card">
        <LavaText role="subheadline" testID="chainedUpstream.title">
          {view.title}
        </LavaText>
        <LavaText
          role="body"
          tone={view.presentation === 'error' ? 'danger' : 'secondary'}
          testID="chainedUpstream.detail">
          {view.detail}
        </LavaText>

        {view.presentation === 'loading' ? (
          <ActivityIndicator testID="chainedUpstream.spinner" />
        ) : null}

        <LavaToggleRow
          title={view.toggleTitle}
          value={view.toggleValue}
          onValueChange={handleToggle}
          disabled={view.toggleDisabled}
          testID="chainedUpstream.toggle"
          accessibilityHint="Enables or disables the chained upstream"
        />

        {view.notice != null ? (
          <LavaText
            role="caption"
            tone="warning"
            testID="chainedUpstream.notice">
            {view.notice}
          </LavaText>
        ) : null}
      </LavaCard>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    gap: 20,
    padding: 20,
  },
  header: {
    alignItems: 'center',
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
});

export default ChainedUpstreamScreen;
