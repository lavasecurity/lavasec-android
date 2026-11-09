import {useCallback, useEffect, useRef, useState} from 'react';
import {AppState} from 'react-native';
import {getProductSnapshot, sendProductCommand, subscribeProductSnapshot, type ProductCommand, type ProductSnapshot} from './productContract';

/** Subscribe before the first read; revisions prevent a late read from undoing a push. */
export function useProduct() {
  const [snapshot, setSnapshot] = useState<ProductSnapshot | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const latest = useRef(-1);
  const turn = useRef(0);
  const revoking = useRef(false);
  const active = useRef(AppState.currentState !== 'background' && AppState.currentState !== 'inactive');
  const mounted = useRef(true);
  const commandPending = useRef(false);
  const errorKind = useRef<'read' | 'command' | null>(null);
  const accept = useCallback((value: ProductSnapshot) => {
    if (!mounted.current || !active.current || revoking.current || value.revision < latest.current) return;
    latest.current = value.revision;
    setSnapshot(value.foreground ? value : null);
    if (errorKind.current === 'read') { errorKind.current = null; setError(null); }
  }, []);
  const failed = useCallback(() => {
    if (!mounted.current || !active.current) return;
    setSnapshot(null);
    errorKind.current = 'read';
    setError('Lava could not read its current state. Try again.');
  }, []);
  const refresh = useCallback(async () => {
    if (mounted.current && active.current) { errorKind.current = null; setError(null); }
    const requestedTurn = turn.current;
    try {
      if (revoking.current) {
        const result = await sendProductCommand({type: 'navigation.endTurn'}, '');
        if (result.status !== 'completed') throw new Error('Revocation unavailable');
        if (requestedTurn !== turn.current) return;
        revoking.current = false;
      }
      const value = await getProductSnapshot();
      if (requestedTurn === turn.current) accept(value);
    } catch { if (requestedTurn === turn.current) failed(); }
  }, [accept, failed]);
  useEffect(() => {
    mounted.current = true;
    const unsubscribe = subscribeProductSnapshot(accept, failed);
    const appState = AppState.addEventListener('change', state => {
      active.current = state === 'active';
      if (active.current) { void refresh(); } else { turn.current++; revoking.current = false; setSnapshot(null); setError(null); }
    });
    // Resume may occur between render and effect subscription. Read the current value
    // after subscribing so that a missed event cannot leave this surface backgrounded.
    active.current = AppState.currentState !== 'background' && AppState.currentState !== 'inactive';
    void refresh();
    return () => { mounted.current = false; unsubscribe(); appState.remove(); };
  }, [accept, failed, refresh]);
  // Revocation bypasses commandPending so a pending native prompt cannot hold a turn open.
  const endTurn = useCallback(async () => {
    const requestedTurn = ++turn.current;
    revoking.current = true;
    setSnapshot(null);
    try {
      const result = await sendProductCommand({type: 'navigation.endTurn'}, '');
      if (result.status !== 'completed') throw new Error('Revocation unavailable');
      const value = await getProductSnapshot();
      if (requestedTurn === turn.current) { revoking.current = false; accept(value); }
    } catch {
      if (requestedTurn === turn.current) failed();
    }
  }, [accept, failed]);
  const command = useCallback(async (request: ProductCommand) => {
    if (snapshot?.generation == null || !active.current || revoking.current || commandPending.current) return;
    commandPending.current = true;
    setBusy(true);
    errorKind.current = null;
    setError(null);
    try {
      const result = await sendProductCommand(request, snapshot.generation);
      if (mounted.current && result.status === 'rejected') {
        errorKind.current = 'command';
        setError(result.reason === 'stale-generation' || result.reason === 'stale-action'
          ? 'Lava changed while this screen was open. Try again.'
          : 'This action could not be completed. Try again.');
      }
      // Native completion/cancellation never implies a state change; read the owner again.
      const requestedTurn = turn.current;
      const updated = await getProductSnapshot();
      if (mounted.current && active.current && !revoking.current && requestedTurn === turn.current && updated.revision >= latest.current) {
        latest.current = updated.revision;
        setSnapshot(updated.foreground ? updated : null);
      }
    } catch { if (mounted.current) { errorKind.current = 'command'; setError('This action could not be completed. Try again.'); } }
    finally { commandPending.current = false; if (mounted.current) setBusy(false); }
  }, [snapshot]);
  return {snapshot, error, busy, refresh, command, endTurn};
}
