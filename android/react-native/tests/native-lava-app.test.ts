import {DeviceEventEmitter} from 'react-native';
import {
  CHAINED_ACTIVE_EVENT,
  CHAINED_COMPOSITION_EVENT,
  CHAINED_PROTECTION_EVENT,
  CHAINED_REASON_KEY_UNAVAILABLE,
  CHAINED_REASON_NOT_CONFIGURED,
  CHAINED_REASON_PREFERENCE_FAILED,
  CHAINED_REASON_RECOVERING,
  CHAINED_REASON_UNAVAILABLE,
  chainedReasonCopy,
  getChainedStatus,
  parseChainedStatus,
  parseChainedToggleResult,
  setChainedEnabled,
  subscribeChainedActive,
  subscribeChainedComposition,
  subscribeChainedProtection,
} from '../src/NativeLavaApp';

describe('NativeLavaApp typed command surface', () => {
  test('reports unavailable rather than pretending success when the module is absent', async () => {
    await expect(getChainedStatus()).resolves.toEqual({
      ready: false,
      reason: CHAINED_REASON_UNAVAILABLE,
      enabled: false,
      active: false,
      protection: 'off',
      installedMode: 'none',
      canEnable: false,
      configured: false,
      eligible: false,
    });
    await expect(setChainedEnabled(true)).resolves.toEqual({
      accepted: false,
      reason: CHAINED_REASON_UNAVAILABLE,
    });
  });

  test('rejects a non-boolean enable argument before touching native', async () => {
    await expect(
      setChainedEnabled('true' as unknown as boolean),
    ).rejects.toThrow(TypeError);
  });

  test('rejects a malformed native status instead of trusting it', () => {
    expect(() => parseChainedStatus(null)).toThrow(TypeError);
    expect(() => parseChainedStatus({ready: 'yes'})).toThrow(TypeError);
    expect(() =>
      parseChainedStatus({ready: true, reason: 4, enabled: false, eligible: true}),
    ).toThrow(TypeError);
    expect(() =>
      parseChainedStatus({ready: true, reason: null, enabled: false}),
    ).toThrow(TypeError);
    expect(() =>
      parseChainedStatus({
        ready: true,
        reason: null,
        enabled: false,
        configured: 'yes',
        eligible: true,
      }),
    ).toThrow(TypeError);
    expect(() =>
      parseChainedStatus({
        ready: true,
        reason: null,
        enabled: false,
        canEnable: 'maybe',
        configured: true,
        eligible: true,
      }),
    ).toThrow(TypeError);
    expect(() =>
      parseChainedStatus({
        ready: true,
        reason: null,
        enabled: false,
        active: 'yes',
        canEnable: true,
        configured: true,
        eligible: true,
      }),
    ).toThrow(TypeError);
    expect(() =>
      parseChainedStatus({
        ready: true,
        reason: null,
        enabled: false,
        active: false,
        protection: 'sometimes',
        canEnable: true,
        configured: true,
        eligible: true,
      }),
    ).toThrow(TypeError);
    expect(() =>
      parseChainedStatus({
        ready: true,
        reason: null,
        enabled: false,
        active: false,
        protection: 'running',
        installedMode: 'both',
        canEnable: true,
        configured: true,
        eligible: true,
      }),
    ).toThrow(TypeError);
  });

  test('rejects a malformed toggle result', () => {
    expect(() => parseChainedToggleResult(null)).toThrow(TypeError);
    expect(() => parseChainedToggleResult({accepted: false, reason: 7})).toThrow(
      TypeError,
    );
    expect(() => parseChainedToggleResult({reason: null})).toThrow(TypeError);
  });

  test('accepts a well-formed status and toggle result', () => {
    expect(
      parseChainedStatus({
        ready: true,
        reason: null,
        enabled: true,
        active: true,
        protection: 'running',
        installedMode: 'chained',
        canEnable: true,
        configured: true,
        eligible: true,
      }),
    ).toEqual({
      ready: true,
      reason: null,
      enabled: true,
      active: true,
      protection: 'running',
      installedMode: 'chained',
      canEnable: true,
      configured: true,
      eligible: true,
    });
    expect(parseChainedToggleResult({accepted: true, reason: null})).toEqual({
      accepted: true,
      reason: null,
    });
  });

  test('subscribeChainedActive forwards activity changes and unsubscribes', () => {
    const listener = jest.fn();
    const unsubscribe = subscribeChainedActive(listener);

    DeviceEventEmitter.emit(CHAINED_ACTIVE_EVENT, true);
    expect(listener).toHaveBeenCalledWith(true);

    unsubscribe();
    DeviceEventEmitter.emit(CHAINED_ACTIVE_EVENT, false);
    expect(listener).toHaveBeenCalledTimes(1);
  });

  test('subscribeChainedProtection forwards protection-mode changes and unsubscribes', () => {
    const listener = jest.fn();
    const unsubscribe = subscribeChainedProtection(listener);

    DeviceEventEmitter.emit(CHAINED_PROTECTION_EVENT, 'running');
    expect(listener).toHaveBeenCalledWith('running');

    unsubscribe();
    DeviceEventEmitter.emit(CHAINED_PROTECTION_EVENT, 'paused');
    expect(listener).toHaveBeenCalledTimes(1);
  });

  test('subscribeChainedComposition forwards composition changes and unsubscribes', () => {
    const listener = jest.fn();
    const unsubscribe = subscribeChainedComposition(listener);

    DeviceEventEmitter.emit(CHAINED_COMPOSITION_EVENT, true);
    expect(listener).toHaveBeenCalledWith(true);

    unsubscribe();
    DeviceEventEmitter.emit(CHAINED_COMPOSITION_EVENT, false);
    expect(listener).toHaveBeenCalledTimes(1);
  });

  test('chainedReasonCopy maps codes to copy, never echoing a code or log identifier', () => {
    const codes = [
      CHAINED_REASON_NOT_CONFIGURED,
      CHAINED_REASON_UNAVAILABLE,
      CHAINED_REASON_RECOVERING,
      CHAINED_REASON_KEY_UNAVAILABLE,
      CHAINED_REASON_PREFERENCE_FAILED,
      'not-entitled',
      'device-ineligible',
      'disabled-by-user',
      'tunnel-dns-unavailable',
      'tunnel-dns-malformed',
      'no-ipv4-route',
      'tunnel-dns-unrouted',
      'routes-collide',
      'client-dns-unavailable',
    ];
    codes.forEach((code) => {
      const copy = chainedReasonCopy(code);
      expect(copy.length).toBeGreaterThan(0);
      expect(copy).not.toBe(code);
      expect(copy).not.toMatch(/chained-not-ready/);
      // Every emitted code has its own case, so none falls through to the generic default.
      expect(copy).not.toBe('Chained upstream is not available yet.');
    });
    expect(chainedReasonCopy(null)).not.toMatch(/chained-not-ready/);
    expect(chainedReasonCopy('unknown-code')).not.toMatch(/chained-not-ready/);
  });

  test('chainedReasonCopy tells the user to re-enter an unreadable key', () => {
    const copy = chainedReasonCopy(CHAINED_REASON_KEY_UNAVAILABLE);
    expect(copy).toMatch(/key/i);
    expect(copy).toMatch(/re-enter/i);
  });
});
