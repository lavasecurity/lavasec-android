/**
 * Shared interruption matrix (plan 309 F23).
 *
 * One matrix drives every future interruption case, so a journey is not "tested" by a
 * screen-local mock that happens to forget a lifecycle edge. The harness wires the *real*
 * canonical `AppStore` to the *real* Android runtime adapter (`canonical/android/runtime.ts`)
 * over a fake native product port. The port records commands (side effects), keeps a durable
 * owner-state map, and can defer a command so a case can interrupt it mid-flight.
 *
 * The harness owns:
 *  - {@link FakeWireOwner}: the fake `NativeLavaApp` port and the confirmed wire snapshot it
 *    serves, including deferred replies and a durable owner-state map;
 *  - a tree that can be softly re-rendered (a snapshot/graph push) or hard remounted (rotation,
 *    route back / tab change, process death) with the owner state preserved;
 *  - an AppState emitter so background/foreground and privacy revocation are deterministic;
 *  - the ten canonical {@link INTERRUPTION_MATRIX} descriptors, each with an executable `apply`.
 *
 * Cases assert four things, never "did not crash": visible state (RNTL), durable owner state
 * ({@link FakeWireOwner.durability}), runtime state (the {@link AppSnapshot} the mounted tree
 * renders from), and the NUMBER of side effects per command type.
 */
import React, {useEffect, useLayoutEffect, useMemo, useState, useSyncExternalStore, type ReactElement, type ReactNode} from 'react';
import {AppState, DeviceEventEmitter, NativeModules, View} from 'react-native';
import {act, fireEvent, render, waitFor, type RenderResult} from '@testing-library/react-native';
import type {AppCommand, AppSnapshot} from '../canonical/app/contract';
import {AppStore} from '../canonical/app/store';
import {AppearanceStore} from '../canonical/review/appearance-store';
import {ReviewContext, type ReviewState} from '../canonical/review/ReviewContext';
import {initialPreviewDraft} from '../canonical/review/preview-model';
import {initialSession, type PreviewSession} from '../canonical/review/session';
import type {ProductSnapshot} from '../src/productContract';
import {productFixture} from './productFixture';

// ---------------------------------------------------------------------------
// The Android runtime port fake
// ---------------------------------------------------------------------------

/**
 * The Android runtime adapter binds `NativeModules.NativeLavaApp` when it is first imported.
 * Install the stable dispatch slot BEFORE the runtime is required so the adapter captures it.
 * The harness module body runs before its lazy `require`, and no static import in this file
 * pulls the runtime. The test must import this module before importing anything that re-exports
 * `canonical/specs/NativeLavaApp` (journey stations live here, so this is self-contained).
 */
type NativePort = {
  getSnapshot: () => Promise<string>;
  command: (request: string) => Promise<string>;
};
let activeOwner: FakeWireOwner | null = null;
const nativePort: NativePort = {
  getSnapshot: async () => {
    if (!activeOwner) throw new Error('The fake Android owner is not installed.');
    return activeOwner.getSnapshot();
  },
  command: async (request: string) => {
    if (!activeOwner) throw new Error('The fake Android owner is not installed.');
    return activeOwner.command(request);
  },
};
(NativeModules as {NativeLavaApp?: unknown}).NativeLavaApp = nativePort;
// eslint-disable-next-line @typescript-eslint/no-var-requires
const {androidRuntime, projectAndroidSnapshot} = require('../canonical/android/runtime') as typeof import('../canonical/android/runtime');

/** The extended wire the Android projection accepts beyond `ProductSnapshot`. */
export type Wire = ProductSnapshot & {
  build?: string;
  plus?: AppSnapshot['plus'];
  limits?: AppSnapshot['limits'];
  preferences?: {appearance: string; look: string; matchTextSize: boolean; textSize: number; haptics: boolean};
};

/** Confirmed all-off Security, the production default every harness wire carries when it omits one. */
const confirmedAllOffSecurity: NonNullable<ProductSnapshot['security']> = {
  unavailable: false, hasAuthenticationMethod: false, protectedSurfaces: [],
  showBiometrics: false, canEnableBiometrics: false, biometricTitle: 'Biometrics', status: '',
};

export type Reply =
  | {status: 'completed'; value?: unknown}
  | {status: 'rejected'; reason?: string}
  | {status: 'cancelled'; reason?: string};
export type CommandHandler = (command: AppCommand, owner: FakeWireOwner) => Reply | Promise<Reply>;

export interface Deferred<T> {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (error: unknown) => void;
}
/** A promise whose settlement a case controls, so it can interrupt a command in flight. */
export function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return {promise, resolve, reject};
}

export const completed = (value?: unknown): Reply => (value === undefined ? {status: 'completed'} : {status: 'completed', value});
export const rejected = (reason: string): Reply => ({status: 'rejected', reason});

/** Process-wide monotonic wire revision, mirroring the native owner's AtomicLong. */
let wireClock = 1_000;
function nextRevision(): number {
  return ++wireClock;
}

export class FakeWireOwner {
  wire: Wire;
  generation: string | null;
  readonly commands: AppCommand[] = [];
  /** Durable owner-side state a screen must never fake locally. */
  readonly durability = new Map<string, unknown>();
  private handler: CommandHandler | undefined;
  private nativeAvailable = true;

  constructor(wire: Wire = productFixture as Wire) {
    // Production always projects a `security` family and the native preserves it through redaction,
    // so the harness must too: without it the projection reports an uncertain owner and every
    // background frame would be concealed, which is neither the Security-off shape nor this
    // matrix's subject. The confirmed all-off default keeps ordinary backgrounding a routine frame.
    this.wire = {...wire, security: wire.security ?? confirmedAllOffSecurity, revision: nextRevision()};
    this.generation = this.wire.generation;
  }

  setHandler(handler: CommandHandler | undefined): void {
    this.handler = handler;
  }

  get available(): boolean {
    return this.nativeAvailable;
  }

  getSnapshot = async (): Promise<string> => {
    if (!this.nativeAvailable) throw new Error('The native product transport is torn down.');
    return JSON.stringify(this.wire);
  };

  command = async (request: string): Promise<string> => {
    if (!this.nativeAvailable) throw new Error('The native product transport is torn down.');
    const command = JSON.parse(request) as AppCommand;
    this.commands.push(command);
    const reply = this.handler ? await this.handler(command, this) : {status: 'completed' as const};
    return JSON.stringify(reply);
  };

  /** Records a committed durable value, as the real owner would after a verified completion. */
  commit(key: string, value: unknown): void {
    this.durability.set(key, value);
  }

  /** Simulates the native module being torn down (transport no longer admits a command). A real
   *  retirement takes the credential graph with it, so the generation is cleared: the resulting
   *  frame is an authority boundary, not a routine background. */
  teardownNative(): void {
    this.nativeAvailable = false;
    this.generation = null;
    this.wire = {...this.wire, generation: null, available: false, revision: nextRevision()};
  }

  /** Applies an owner change and publishes it as a native snapshot event. */
  publish(patch: Partial<Wire> = {}): void {
    this.wire = {...this.wire, ...patch, revision: nextRevision()};
    this.generation = this.wire.generation;
    DeviceEventEmitter.emit('lavaAppSnapshot', JSON.stringify(this.wire));
  }

  /** Replaces the snapshot and advances the credential-graph generation (a rotation). */
  rotateGeneration(patch: Partial<Wire> = {}): void {
    const generation = String((Number(this.generation ?? '0') || 0) + 1);
    this.publish({...(this.wire as object), ...patch, generation} as Partial<Wire>);
  }

  count(type: string): number {
    return this.commands.filter(command => command.type === type).length;
  }

  last(type?: string): AppCommand | undefined {
    return type === undefined ? this.commands[this.commands.length - 1] : [...this.commands].reverse().find(command => command.type === type);
  }
}

// ---------------------------------------------------------------------------
// The matrix
// ---------------------------------------------------------------------------

export type InterruptionKind =
  | 'blur-back'
  | 'tab-change'
  | 'background-foreground'
  | 'rotation'
  | 'process-death'
  | 'native-module-teardown'
  | 'graph-retirement'
  | 'account-switch'
  | 'tier-change'
  | 'privacy-revocation';

export interface InterruptionDescriptor {
  kind: InterruptionKind;
  title: string;
  /** Journal class this interruption exercises. */
  journal: 'lifecycle' | 'owner';
  apply(harness: JourneyHarness): void;
}

/** The stage of a journey a case is exercising. */
export type JourneyStage = 'fetch' | 'prompt' | 'review' | 'commit' | 'result';
/** The highest-risk journeys named by the plan. */
export type JourneyName = 'filters' | 'dns' | 'account' | 'billing' | 'backup' | 'feedback';

// ---------------------------------------------------------------------------
// AppState + native subscription simulation
// ---------------------------------------------------------------------------

export interface LifecycleMock {
  emit(state: 'active' | 'background' | 'inactive'): void;
  /** Live AppState listeners; must not grow across remounts. */
  appStateListenerCount(): number;
  /** Live native snapshot subscribers; must not grow across remounts. */
  nativeListenerCount(): number;
}

/**
 * Installs a deterministic AppState mock. Call once in `beforeEach`. Only an explicit
 * background/inactive is "not active", mirroring `useProduct`'s foreground convention.
 */
export function installLifecycleMock(): LifecycleMock {
  const handlers = new Set<(state: string) => void>();
  let current = 'active';
  jest.spyOn(AppState, 'addEventListener').mockImplementation(((_type: string, handler: (state: string) => void) => {
    handlers.add(handler);
    return {remove: () => handlers.delete(handler)} as never;
  }) as never);
  AppState.currentState = 'active';
  return {
    emit(state) {
      current = state;
      AppState.currentState = state;
      act(() => {
        handlers.forEach(handler => handler(state));
      });
    },
    appStateListenerCount: () => handlers.size,
    nativeListenerCount: () => DeviceEventEmitter.listenerCount('lavaAppSnapshot'),
  };
}

// ---------------------------------------------------------------------------
// Harness
// ---------------------------------------------------------------------------

const appearancePort = {
  getSnapshot: async () => ({preference: 'system', revision: 0}),
  setPreference: async () => ({preference: 'system', revision: 0}),
  onSnapshot: () => ({remove() {}}),
};

function JourneyProviders({harness, children}: {harness: JourneyHarness; children: ReactNode}) {
  const app = harness.store;
  const state = useSyncExternalStore(app.subscribe, app.getSnapshot);
  const live = state.snapshot ?? undefined;
  const hydration=useSyncExternalStore(app.subscribe,app.getPresentationHydration);
  const authority=useSyncExternalStore(app.subscribe,app.getPresentationAuthority);
  const [measured,setMeasured]=useState(false);
  useLayoutEffect(()=>{
    if(!measured||!authority||!hydration.required)return;
    const frame=requestAnimationFrame(()=>app.completePresentationLayout(hydration.epoch));
    return()=>cancelAnimationFrame(frame);
  },[app,measured,authority,hydration.epoch,hydration.required]);
  const [look, setLook] = useState('original');
  const [draft, setDraft] = useState(initialPreviewDraft);
  const [savedDraft, setSavedDraft] = useState(initialPreviewDraft);
  const [session, setSession] = useState<PreviewSession>(() => initialSession());
  const value = useMemo<ReviewState>(
    () => ({app, live, appearance: harness.appearance, look, setLook, draft, setDraft, savedDraft, setSavedDraft, session, setSession}),
    [app, live, harness.appearance, look, draft, savedDraft, session],
  );
  return <ReviewContext.Provider value={value}><View testID="journey.native-viewport"
    onLayout={event=>setMeasured(event.nativeEvent.layout.width>0&&event.nativeEvent.layout.height>0)}>{children}</View></ReviewContext.Provider>;
}

export interface MountJourneyOptions {
  wire?: Wire;
  owner?: FakeWireOwner;
  lifecycle?: LifecycleMock;
  /** Renders the journey's station; the harness wraps it in the canonical review provider. */
  render: () => ReactElement;
}

export interface JourneyHarness {
  readonly owner: FakeWireOwner;
  readonly store: AppStore;
  readonly appearance: AppearanceStore;
  /** Soft update: reconciles a new snapshot without dropping the mounted tree. */
  rerender(): void;
  /** Jest has no Yoga viewport. Fire real rendered layout callbacks, then wait for
   * the AppStore's current scoped readers and reveal frame, as the full root does. */
  ready(): Promise<void>;
  /** Hard reset: unmounts and mounts a fresh tree, as rotation/tab change would. The app-scoped
   *  store stays connected, exactly as it outlives a screen in production. */
  remount(): void;
  /** Process death: the JS runtime (and its store) is recreated; the native owner survives. */
  coldStart(): void;
  unmount(): void;
  setSnapshot(patch: Partial<Wire>): void;
  /** Replaces the snapshot and advances the credential-graph generation (a rotation). */
  rotateGeneration(patch?: Partial<Wire>): void;
  emitAppState(state: 'active' | 'background' | 'inactive'): void;
  setAppActive(active: boolean): void;
  /** The native transport is torn down; a snapshot marks the runtime unavailable. */
  teardown(): void;
  /** Current runtime state passed to the mounted tree. */
  runtime(): AppSnapshot | null;
  /** Side-effect count for a command type. */
  count(type: string): number;
  /** Asserts a command type ran exactly `expected` times, never replayed by a recovery. */
  expectCount(type: string, expected: number): void;
}

export function mountJourney(options: MountJourneyOptions): JourneyHarness {
  const owner = options.owner ?? new FakeWireOwner(options.wire);
  activeOwner = owner;
  const appearance = new AppearanceStore(appearancePort);
  let store = new AppStore(androidRuntime, undefined);
  // The app-scoped runtime is connected once and outlives the mounted screen. A screen remount
  // must not disconnect/reconnect it, or a child query effect can race a parent reconnect.
  let disconnect = store.connect();
  const tree = (): ReactElement => (
    <JourneyProviders harness={harness}>
      {options.render()}
    </JourneyProviders>
  );
  const harness: JourneyHarness = {
    owner,
    get store(): AppStore {
      return store;
    },
    appearance,
    async ready():Promise<void> {
      acknowledgeLayout();
      await waitFor(()=>{expect(store.getPresentationAuthority()).toBe(true);expect(store.getPresentationHydration().required).toBe(false);});
    },
    rerender(): void {
      act(() => {
        result.rerender(tree());
        acknowledgeLayout();
      });
    },
    remount(): void {
      result.unmount();
      result = render(tree());
      acknowledgeLayout();
    },
    coldStart(): void {
      result.unmount();
      disconnect();
      store = new AppStore(androidRuntime, undefined);
      disconnect = store.connect();
      result = render(tree());
      acknowledgeLayout();
    },
    unmount(): void {
      result.unmount();
      disconnect();
    },
    setSnapshot(patch): void {
      owner.publish(patch);
    },
    rotateGeneration(patch): void {
      owner.rotateGeneration(patch);
    },
    emitAppState(state): void {
      options.lifecycle?.emit(state);
    },
    setAppActive(active): void {
      options.lifecycle?.emit(active ? 'active' : 'background');
    },
    teardown(): void {
      owner.teardownNative();
      // A torn-down owner publishes a cold, locked snapshot: the previously-confirmed session
      // evidence is gone with the graph.
      owner.publish({available: false, foreground: false, filterSession: undefined, filterEditing: undefined, libraryEditing: undefined});
    },
    runtime(): AppSnapshot | null {
      return store.getSnapshot().snapshot;
    },
    count(type): number {
      return owner.count(type);
    },
    expectCount(type, expected): void {
      expect(owner.count(type)).toBe(expected);
    },
  };
  let result: RenderResult = render(tree());
  function acknowledgeLayout() {
    // Actual host onLayout handlers register both the route viewport and the
    // focused scaffold. Scoped read tickets still settle only from their real
    // query commits; this cannot fabricate presentation authority or data.
    for(const host of result.UNSAFE_root.findAll((node:{type:unknown;props:{onLayout?:unknown}})=>typeof node.type==='string'&&typeof node.props.onLayout==='function')) {
      fireEvent(host,'layout',{nativeEvent:{layout:{x:0,y:0,width:360,height:720}}});
    }
  }
  acknowledgeLayout();
  return harness;
}

// ---------------------------------------------------------------------------
// Matrix descriptors (executable)
// ---------------------------------------------------------------------------

export const INTERRUPTION_MATRIX: readonly InterruptionDescriptor[] = [
  {
    kind: 'blur-back',
    title: 'Back / blur: the route is popped and re-entered',
    journal: 'lifecycle',
    apply: harness => harness.remount(),
  },
  {
    kind: 'tab-change',
    title: 'Tab change: the tab unmounts and remounts',
    journal: 'lifecycle',
    apply: harness => harness.remount(),
  },
  {
    kind: 'background-foreground',
    title: 'Background then foreground',
    journal: 'lifecycle',
    apply: harness => {
      harness.setAppActive(false);
      harness.setAppActive(true);
    },
  },
  {
    kind: 'rotation',
    title: 'Configuration change recreates the activity',
    journal: 'lifecycle',
    apply: harness => harness.remount(),
  },
  {
    kind: 'process-death',
    title: 'Process death: JS restarts, the native owner survives',
    journal: 'lifecycle',
    apply: harness => harness.coldStart(),
  },
  {
    kind: 'native-module-teardown',
    title: 'The native transport is torn down',
    journal: 'owner',
    apply: harness => harness.teardown(),
  },
  {
    kind: 'graph-retirement',
    title: 'Credential-graph generation retires',
    journal: 'owner',
    apply: harness => harness.rotateGeneration(),
  },
  {
    kind: 'account-switch',
    title: 'The active account switches',
    journal: 'owner',
    apply: harness =>
      harness.rotateGeneration({
        backup: {...productFixture.backup, configured: false, remoteStatus: 'noBackup', remoteAvailable: false},
      } as Partial<Wire>),
  },
  {
    kind: 'tier-change',
    title: 'The entitlement tier changes',
    journal: 'owner',
    apply: harness => {
      const plus = harness.runtime()?.plus;
      harness.setSnapshot({plus: {...(plus ?? {}), enabled: !(plus?.enabled ?? false)}} as Partial<Wire>);
    },
  },
  {
    kind: 'privacy-revocation',
    title: 'Privacy scope is revoked (background)',
    journal: 'owner',
    apply: harness => {
      harness.setAppActive(false);
      harness.setAppActive(true);
    },
  },
];

export function interruption(kind: InterruptionKind): InterruptionDescriptor {
  const descriptor = INTERRUPTION_MATRIX.find(candidate => candidate.kind === kind);
  if (descriptor === undefined) throw new Error(`Unknown interruption: ${kind}`);
  return descriptor;
}

export {projectAndroidSnapshot};
