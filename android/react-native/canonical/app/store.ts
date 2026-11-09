import {AppState} from 'react-native';
import {PresentationHydration,type PresentationReadTicket} from './presentation-hydration';
import type {PlusDestination} from '../review/plus-intents';
import {clearAppReadCache, readPrivacyScope, domainReviewPrivacyScope} from './read-cache';
import type {Spec} from '../specs/NativeLavaApp';
import type {AppCommand, AppSnapshot, RuntimeRetryReceipt, VpnDraftRetirement} from './contract';

// Native effects can finish after their React presentation has disconnected. The
// result still belongs to that retired connection, even when a new screen uses
// the same store. Callers must reject it without presenting an obsolete failure.
export class AppCommandDetachedError extends Error {
  constructor(phase: 'beforeStart' | 'afterStart') {
    super(phase === 'beforeStart' ? 'The app screen closed before this action started.' : 'The app screen closed while this action completed.');
    this.name = 'AppCommandDetachedError';
  }
}

// The native snapshot carries two concealment facts the strict `AppSnapshot` type does
// not (yet) declare: whether this frame was projected for a backgrounded app, and the
// owner facts the shared policy consumes. Read them structurally so this slice stays a
// store-local lifecycle change.
type PrivacyFacts = AppSnapshot & {
  foreground?: boolean;
  available?: boolean;
  security: AppSnapshot['security'] & {recoveryRequired?: boolean; protectedSurfaces?: string[]};
};

/** Native display consent never follows synthetic access gates or credential failures. */
export function backgroundPrivacyCoverRequired(snapshot: PrivacyFacts): boolean {
  return snapshot.security?.appUnlockCoverChoice !== false;
}

function backgroundFrame(snapshot: PrivacyFacts): boolean {
  // The native `foreground` fact mirrors `application.started` (the Activity start/stop lifecycle),
  // so ordinary backgrounding sets it false. It is NOT by itself evidence of process teardown or
  // owner retirement; `available:false` is the native redaction of a non-started frame, not an
  // authority fact. Every non-started frame enters the background branch below, which separates a
  // ROUTINE background (the same owner authority: the display is retained for warm resume) from an
  // AUTHORITY BOUNDARY (a different/absent scope: the old display is invalidated).
  return snapshot.foreground === false;
}

/**
 * The owner authority a native background frame preserves through redaction: the credential-graph
 * generation plus the owner's own availability/recovery facts. Two frames with equal scopes are the
 * same authority, so ordinary backgrounding must not discard the accepted display; a changed scope
 * is a boundary (credential rotation/retirement via `generation`, secure-storage unavailability, or
 * recovery) and must not keep it.
 *
 * The security OPT-IN set (`hasAuthenticationMethod`, `protectedSurfaces`) is deliberately NOT part
 * of the authority scope. Committing a passcode or a protected surface mid-session does not change
 * the owner: the same generation still owns the display, and the shipping native `ReactAppLockView`
 * cover is what conceals the retained tree while the app is backgrounded. Treating a
 * protected-surface commit as a boundary discarded the accepted frame on an ordinary background and
 * unmounted the still-mounted navigation/editor beneath it (the concealment must cover, not destroy,
 * the workflow). A genuine credential loss still invalidates through `unavailable`/`recoveryRequired`.
 */
export function backgroundAuthorityScope(snapshot: PrivacyFacts): string {
  const security = snapshot.security;
  return JSON.stringify([
    snapshot.generation ?? null,
    security?.unavailable === true,
    security?.recoveryRequired === true,
  ]);
}

export class AppStore {
  private state: {snapshot: AppSnapshot | null; error: string | null; privacyCoverRequired?:boolean; appPrivacyCoverRequired?:boolean; displaySnapshot?:AppSnapshot|null} = {snapshot: null, error: null};
  private listeners = new Set<() => void>();
  private generation = 0;
  private connected = false;
  private invalidation = 0;
  private readEpoch = 0;
  private domainReviewEpoch = 0;
  private tail: Promise<unknown> = Promise.resolve();
  private hasReadAuthority=false;
  private retryFrame?:{receipt?:RuntimeRetryReceipt;connection:number;epoch:number};
  private projectionRecovery?:{connection:number;epoch:number;generation?:string;host?:string};
  private lastSnapshotRevision=-1;
  private refreshing?:{connection:number;epoch:number;generation?:string;host?:string;promise:Promise<void>};
  private refreshingLateMarker=false;
  private lateMarkerRefreshRequested=false;
  private hydration=new PresentationHydration(()=>this.notify(),ready=>requestAnimationFrame(()=>ready()));
  constructor(private readonly native: Spec, initialSnapshot?: AppSnapshot) {
    if (initialSnapshot) this.accept(initialSnapshot);
  }
  getSnapshot = () => this.state;
  getInvalidation = () => this.invalidation;
  getReadEpoch = () => this.readEpoch;
  getConnectionEpoch = () => this.generation;
  getPresentationAuthority = () => this.hasReadAuthority;
  getPresentationHydration = () => this.hydration.getSnapshot();
  registerPresentationRead = () => this.hydration.registerRead();
  settlePresentationRead = (ticket?:PresentationReadTicket) => this.hydration.settleRead(ticket);
  completePresentationLayout = (epoch:number) => this.hydration.completeLayout(epoch);
  private revokeReads(preserveDomainReview = false) {
    clearAppReadCache(this); ++this.readEpoch; ++this.invalidation;
    if (!preserveDomainReview) ++this.domainReviewEpoch;
  }
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => {this.listeners.delete(listener);}; };
  private notify() { for (const listener of this.listeners) listener(); }
  private accept(snapshot: AppSnapshot, navigationReply=false) {
    if (!snapshot || snapshot.schema !== 1 || snapshot.fullApp !== true || !Number.isSafeInteger(snapshot.revision) || snapshot.revision < 0) throw new Error('The installed native app does not provide the full Lava runtime.');
    if (snapshot.revision < this.lastSnapshotRevision) return;
    // Native emits its exact publication before returning a navigation receipt.
    // Reuse that accepted frame only while its original host/read authority is live;
    // blocked/background/policy markers must still reach the boundary handling below.
    const accepted=this.state.snapshot;
    if(navigationReply&&accepted&&!this.state.error&&this.hasReadAuthority&&AppState.currentState==='active'&&
      snapshot.foreground!==false&&snapshot.available!==false&&!snapshot.presentationBlocked&&
      snapshot.revision===accepted.revision&&snapshot.nativeHostID===accepted.nativeHostID&&
      snapshot.generation===accepted.generation&&readPrivacyScope(snapshot)===readPrivacyScope(accepted)&&
      snapshot.backgroundPrivacyCoverRequired===accepted.backgroundPrivacyCoverRequired&&
      (snapshot.security?.protectedSurfaces??[]).join('|')===(accepted.security?.protectedSurfaces??[]).join('|'))return;
    // An all-off inactive projection can arrive after the active reply for the same
    // revision. Preserve restored authority only for that exact owner/policy marker.
    if(snapshot.foreground===false&&snapshot.backgroundPrivacyCoverRequired===false&&
      this.state.snapshot?.backgroundPrivacyCoverRequired===false&&this.hasReadAuthority&&
      AppState.currentState==='active'&&snapshot.revision===this.lastSnapshotRevision&&
      backgroundAuthorityScope(snapshot)===backgroundAuthorityScope(this.state.snapshot))return;
    this.retryFrame=undefined;this.projectionRecovery=undefined;
    this.lastSnapshotRevision=snapshot.revision;
    const privacy = snapshot as PrivacyFacts;
    this.state={...this.state,privacyCoverRequired:snapshot.backgroundPrivacyCoverRequired,appPrivacyCoverRequired:snapshot.backgroundPrivacyCoverRequired!==false&&snapshot.appPrivacyCoverRequired===true};
    // A background frame is concealment metadata. It can never reopen reads or restore a
    // frame that was already discarded.
    if (backgroundFrame(privacy)||AppState.currentState!=='active') {
      const hadAuthority=this.hasReadAuthority;
      this.hasReadAuthority=false;
      if(hadAuthority&&!this.hydration.getSnapshot().required)this.hydration.beginBoundary();
      const previous = this.state.snapshot as PrivacyFacts | null;
      // Backgrounding is always an authority change for READS: retire any staged review and
      // undelivered read, regardless of whether the display survives.
      if (previous) this.revokeReads();
      // A routine background is the same owner authority, so it must not discard the accepted
      // display; an authority boundary (rotation, sign-out/retirement, privacy change) must.
      const routine = previous !== null && backgroundAuthorityScope(previous) === backgroundAuthorityScope(privacy);
      if (routine) {
        this.notify();
        // Retain the accepted frame (content, drafts, navigation, scroll). Protected content is
        // concealed by the native ReactAppLockView cover over the mounted tree; nulling the JS
        // snapshot would unmount the navigation tree and destroy in-progress workflows (an open
        // editor, an in-flight WireGuard import), which the native cover does not.
        return;
      }
      // Authority boundary: do not keep the old display; adopt the authoritative (redacted) frame.
      this.state = {snapshot, error: snapshot.presentationError??null, privacyCoverRequired:snapshot.backgroundPrivacyCoverRequired,appPrivacyCoverRequired:snapshot.backgroundPrivacyCoverRequired!==false&&snapshot.appPrivacyCoverRequired===true, displaySnapshot:snapshot}; this.notify();
      return;
    }
    const wasAuthoritative=this.hasReadAuthority;
    this.hasReadAuthority=snapshot.available!==false&&snapshot.foreground!==false&&!snapshot.presentationBlocked;
    const previous = this.state.snapshot;
    if(this.hasReadAuthority&&!wasAuthoritative) {
      if(previous)this.hydration.beginBoundary();else this.hydration.beginInitialPresentation();
    }
    if (previous) {
      const protectedChanged = (previous as PrivacyFacts).security?.protectedSurfaces?.join('|')
        !== privacy.security?.protectedSurfaces?.join('|');
      if (readPrivacyScope(previous) !== readPrivacyScope(snapshot) || protectedChanged) {
        // A staging-only source advance belongs to the in-flight review and must not retire it;
        // every authority-relevant revision still revokes. Absent revisions keep the strict fence.
        this.revokeReads(!protectedChanged && domainReviewPrivacyScope(previous) === domainReviewPrivacyScope(snapshot));
      }
    }
    this.state = {snapshot, error: snapshot.presentationError??null, privacyCoverRequired:snapshot.backgroundPrivacyCoverRequired,appPrivacyCoverRequired:snapshot.backgroundPrivacyCoverRequired!==false&&snapshot.appPrivacyCoverRequired===true, displaySnapshot:snapshot}; this.notify();
  }
  private commandProjectionUnavailable(deferRecovery=false) {
    this.retryFrame=undefined;this.hasReadAuthority=false;this.revokeReads();
    if(!this.hydration.getSnapshot().required)this.hydration.beginBoundary();
    this.state={...this.state,error:'Lava could not refresh its state. Try refreshing again.'};this.notify();
    const recovery={connection:this.generation,epoch:this.readEpoch,generation:this.state.snapshot?.generation,host:this.state.snapshot?.nativeHostID};
    this.projectionRecovery=recovery;
    const current=()=>this.projectionRecovery===recovery&&this.connected&&this.generation===recovery.connection&&this.readEpoch===recovery.epoch&&
      this.state.snapshot?.generation===recovery.generation&&this.state.snapshot?.nativeHostID===recovery.host;
    // Deliver the real operation outcome first. This one subsequent read repairs
    // a retained warm frame; it is never part of the command receipt or a replay.
    const schedule=()=>setTimeout(()=>{
      if(!current())return;
      void (async()=>{
        try{
          const snapshot=JSON.parse(await this.native.getSnapshot()) as AppSnapshot;
          if(current()&&snapshot.generation===recovery.generation&&snapshot.nativeHostID===recovery.host)this.accept(snapshot);
        }catch(error){if(current())this.fail(error);}
        finally{if(this.projectionRecovery===recovery)this.projectionRecovery=undefined;}
      })();
    },0);
    if(!deferRecovery)schedule();
    return schedule;
  }
  private handleBackground(state:string) {
    if(state==='background')this.state={...this.state,appPrivacyCoverRequired:this.state.privacyCoverRequired===true};
    this.retryFrame=undefined;this.projectionRecovery=undefined;
    // AppState went inactive before any native background frame arrived. Revoke reads but keep the
    // accepted frame mounted: protected content is concealed by the native app-lock cover, and
    // nulling the JS snapshot here would unmount the navigation tree and destroy in-progress work.
    this.hasReadAuthority=false;
    if(!this.hydration.getSnapshot().required)this.hydration.beginBoundary();
    this.revokeReads();this.notify();
  }
  connect() {
    const generation = ++this.generation;
    this.connected = true;
    const subscription = this.native.onSnapshot(value => {
      if (generation === this.generation) { try {
        const snapshot=JSON.parse(value) as AppSnapshot;this.accept(snapshot);
        // A late display-only marker must not strand a resumed owner. Coalesce
        // native events; the fresh projection still passes revision/epoch fences.
        if(AppState.currentState==='active'&&!this.hasReadAuthority&&this.state.snapshot&&
          (snapshot.foreground===false||snapshot.presentationBlocked))this.refreshAfterLateMarker();
      } catch (error) {this.fail(error);} }
    });
    const lifecycle = AppState.addEventListener('change', state => {
      if (state !== 'active') {this.handleBackground(state);}
      else {void this.flushPendingDomainReviewCancels().then(()=>this.flushPendingPlusTeardowns()).then(() => this.refresh());}
    });
    void this.refresh();
    return () => {if (generation === this.generation) {++this.generation; this.connected = false;this.retryFrame=undefined;this.projectionRecovery=undefined;clearAppReadCache(this);} subscription.remove(); lifecycle.remove();};
  }
  async retryPresentation():Promise<void> {
    if(!this.connected||AppState.currentState!=='active')return;
    const connection=this.generation;
    this.hasReadAuthority=false;this.revokeReads();this.notify();
    try {
      await this.native.command(JSON.stringify({schema:1,type:'presentation.retry'}));
      if(this.connected&&connection===this.generation&&AppState.currentState==='active')await this.refresh();
    } catch(error) {if(connection===this.generation)this.fail(error);}
  }
  private refreshAfterLateMarker() {
    this.lateMarkerRefreshRequested=true;
    if(this.refreshingLateMarker)return;
    this.refreshingLateMarker=true;
    void (async()=>{
      try {
        do {
          this.lateMarkerRefreshRequested=false;
          await this.refresh();
          // A newer marker may revoke the epoch of an in-flight read. Drain its
          // request with a new native read instead of weakening the read fence.
        } while(this.connected&&this.lateMarkerRefreshRequested);
      } finally {this.refreshingLateMarker=false;}
    })();
  }
  private fail(error: unknown) { if(error instanceof AppCommandDetachedError)return; this.retryFrame=undefined; this.state = {...this.state, error: error instanceof Error ? error.message : String(error)}; this.notify(); }
  /**
   * A `domains.stage` token that can no longer be delivered — the store retired, authority changed,
   * or the caller rejected the response — must be cancelled at the native edge so the staged draft
   * does not linger. The native `domains.cancel` compares the owned token, draft and credential
   * generation, so a newer review survives. Best-effort: a failed cancel never masks the caller's
   * original outcome.
   */
  private pendingDomainReviewCancels: string[] = [];
  /**
   * Retire a staged domain review at the native edge. Native `domains.cancel` compares the owned
   * token, draft and credential generation, so a newer review survives. The bridge refuses a
   * command while the Activity is not foreground, so a cancel that fails is QUEUED and replayed on
   * the next foreground transition; a token-owned teardown is exempt from the App-Unlock gate, so
   * the replay succeeds even while the app is locked and is never stranded.
   */
  async retireDomainReview(token: string) {
    try { await this.native.command(JSON.stringify({type: 'domains.cancel', token})); }
    catch { if (!this.pendingDomainReviewCancels.includes(token)) this.pendingDomainReviewCancels.push(token); }
  }
  /** Retire only the original route capability. Native marks abandonment without
   * replaying Cancel or removing storage; a fresh explicit Begin owns any retry. */
  async retireVpnDraft(retirement:VpnDraftRetirement,after:Promise<unknown>) {
    const payload=JSON.stringify({type:'vpn.retire',id:retirement.id,
      retirementGeneration:retirement.retirementGeneration,nativeHostID:retirement.nativeHostID,retirementLease:retirement.retirementLease});
    const settled=after.then(()=>{},()=>{});
    const retire=async()=>{
      // The original Cancel settles first, including a pre-admission refusal.
      // Its truthful failure is not a reason to replay or hide that outcome.
      await settled;await this.native.command(payload);
    };
    // Install the marker barrier now so a fresh explicit Begin cannot overtake it.
    const result=this.tail.then(retire,retire);this.tail=result.catch(()=>{});
    await result;
  }
  private pendingPlusTeardowns: {command:AppCommand;generation?:string}[]=[];
  async retirePlusDestination(destination:PlusDestination) {
    if(destination.name==='Review') {if(destination.params.standaloneReview)await this.retireDomainReview(destination.params.standaloneReview);return;}
    const command:AppCommand=destination.name==='CustomEntry'?{type:'customEntry.dismiss',id:destination.params.id}:{type:'filter.cancel',id:destination.params.id};
    const generation=destination.name==='Filter'?destination.params.retirementGeneration:this.state.snapshot?.generation;
    const retirementLease=destination.name==='Filter'?destination.params.retirementLease:undefined;
    if(destination.name==='Filter'&&!retirementLease)return;
    const retirementCommand={...command,retirementGeneration:generation,...(retirementLease?{retirementLease}:{})};
    try {await this.native.command(JSON.stringify(retirementCommand));}
    catch {this.pendingPlusTeardowns.push({command:retirementCommand,generation});}
  }
  private async flushPendingPlusTeardowns() {
    const pending=this.pendingPlusTeardowns;this.pendingPlusTeardowns=[];
    for(const owned of pending) {
      if(owned.generation!==this.state.snapshot?.generation)continue;
      try {await this.native.command(JSON.stringify({...owned.command,retirementGeneration:owned.generation}));}
      catch {this.pendingPlusTeardowns.push(owned);}
    }
  }
  private async flushPendingDomainReviewCancels() {
    if (!this.pendingDomainReviewCancels.length) return;
    const pending = this.pendingDomainReviewCancels;
    this.pendingDomainReviewCancels = [];
    for (const token of pending) await this.retireDomainReview(token);
  }
  private async discardUndeliveredDomainReview(result: unknown) {
    const token = result && typeof result === 'object' && 'standaloneReview' in result
      ? (result as {standaloneReview?: unknown}).standaloneReview : undefined;
    if (typeof token !== 'string') return;
    await this.retireDomainReview(token);
  }
  /** Only a failed first read may prepare a public frame. This never accepts a snapshot. */
  async prepareRuntimeRetry(serial:number):Promise<RuntimeRetryReceipt|undefined> {
    if(!this.connected||this.state.snapshot||!this.state.error||AppState.currentState!=='active'||!Number.isSafeInteger(serial)||serial<1)return;
    const visit={connection:this.generation,epoch:this.readEpoch,receipt:undefined as RuntimeRetryReceipt|undefined};
    this.retryFrame=visit;
    const current=()=>this.retryFrame===visit&&this.connected&&this.generation===visit.connection&&this.readEpoch===visit.epoch
      &&AppState.currentState==='active'&&!this.state.snapshot&&!!this.state.error;
    const response=JSON.parse(await this.native.command(JSON.stringify({type:'navigation.retryPresentation',serial}))) as {result?:RuntimeRetryReceipt};
    const receipt=response.result;
    if(!current()||!receipt||typeof receipt.generation!=='string'||!receipt.generation||receipt.serial!==serial)return;
    visit.receipt=receipt;return receipt;
  }
  async acknowledgeRuntimeRetry(receipt:RuntimeRetryReceipt):Promise<boolean> {
    const visit=this.retryFrame;
    const current=()=>!!visit&&this.retryFrame===visit&&visit.receipt===receipt&&this.connected&&this.generation===visit.connection
      &&this.readEpoch===visit.epoch&&AppState.currentState==='active'&&!this.state.snapshot&&!!this.state.error;
    if(!current())return false;
    const response=JSON.parse(await this.native.command(JSON.stringify({type:'navigation.retryPresentationReady',...receipt}))) as {result?:unknown};
    return current()&&response.result===true;
  }
  refresh():Promise<void> {
    if (!this.connected) return Promise.resolve();
    const connection=this.generation,epoch=this.readEpoch,generation=this.state.snapshot?.generation,host=this.state.snapshot?.nativeHostID;
    const pending=this.refreshing;
    if(pending&&pending.connection===connection&&pending.epoch===epoch&&pending.generation===generation&&pending.host===host)return pending.promise;
    this.retryFrame=undefined;this.projectionRecovery=undefined;
    // A shared read still belongs to its exact connection, lifecycle/read epoch,
    // graph and native host. A resumed/successor owner starts independent work.
    const current=()=>this.connected&&connection===this.generation&&epoch===this.readEpoch&&
      generation===this.state.snapshot?.generation&&host===this.state.snapshot?.nativeHostID;
    const request={connection,epoch,generation,host,promise:Promise.resolve()};this.refreshing=request;
    request.promise=(async()=>{
      try {const value=await this.native.getSnapshot();if(current())this.accept(JSON.parse(value));}
      catch(error){if(current())this.fail(error);}
      finally{if(this.refreshing===request)this.refreshing=undefined;}
    })();
    return request.promise;
  }
  /** Opaque native cancellation carries no read grant and survives renderer disconnection. */
  async retireActivityShare(token:string):Promise<void> {
    await this.native.command(JSON.stringify({type:'activity.share.retire',token}));
  }
  async retirePreparation(id:string):Promise<void> {
    await this.native.command(JSON.stringify({type:'preparation.cancel',id}));
  }
  async retireAccountBackup(id:string):Promise<void> {
    await this.native.command(JSON.stringify({type:'accountBackup.command',id,action:'close'}));
  }
  command<T = unknown>(command: AppCommand, isCurrent?: () => boolean): Promise<T> {
    if(command.type==='vpn.retire')return Promise.reject(new Error('VPN route retirement requires its captured cancellation outcome.'));
    if(command.type==='navigation.retryPresentation'||command.type==='navigation.retryPresentationReady')
      return Promise.reject(new Error('Public retry paint requires its owned initial-error receipt.'));
    const generation = this.generation;
    const execute = async () => {
      if (!this.connected || generation !== this.generation) throw new AppCommandDetachedError('beforeStart');
      if (isCurrent && !isCurrent()) throw new AppCommandDetachedError('beforeStart');
      const revokesReads = command.type === 'logs.clear' || command.type.startsWith('account.') ||
        command.type==='accountBackup.command'&&command.action.startsWith('account.');
      if (revokesReads) {this.revokeReads(); this.notify();}
      const readEpoch = this.readEpoch;
      const sourceGeneration = this.state.snapshot?.generation;
      const sourceHost = this.state.snapshot?.nativeHostID;
      // A failed projection belongs to the invocation's read owner. An old
      // native outcome cannot revoke the fresh same-graph grant accepted on resume.
      const ownsFailedProjection = () => readEpoch === this.readEpoch &&
        sourceGeneration === this.state.snapshot?.generation && sourceHost === this.state.snapshot?.nativeHostID;
      const domainReviewEpoch = this.domainReviewEpoch;
      let payload: string;
      try {payload = await this.native.command(JSON.stringify(command));}
      catch (error) {
        if (!this.connected || generation !== this.generation) throw new AppCommandDetachedError('afterStart');
        if(error&&typeof error==='object'&&'projectionFailure' in error&&error.projectionFailure==='unavailable'){
          if(!ownsFailedProjection())throw new AppCommandDetachedError('afterStart');
          this.commandProjectionUnavailable();
        }
        throw error;
      }
      const response = JSON.parse(payload) as {snapshot?: AppSnapshot; result: T;projectionFailure?:'unavailable'|'stale-host'};
      if (!this.connected || generation !== this.generation) {
        // Teardown carries no read authority and must not accept the response snapshot; a staged
        // domain token is discarded at the native edge so no newer draft is stranded.
        if (['domains.stage','domainCheck.stage'].includes(command.type)) await this.discardUndeliveredDomainReview(response.result);
        throw new AppCommandDetachedError('afterStart');
      }
      if(response.projectionFailure!==undefined&&(!['unavailable','stale-host'].includes(response.projectionFailure)||response.snapshot!==undefined))throw new Error('Invalid command publication receipt.');
      if(response.projectionFailure==='stale-host')throw new AppCommandDetachedError('afterStart');
      if(response.projectionFailure==='unavailable'&&!ownsFailedProjection()){
        // The old review result still owns its exact token; teardown carries no
        // private reads and must not revoke or re-read the successor frame.
        if(['domains.stage','domainCheck.stage'].includes(command.type))await this.discardUndeliveredDomainReview(response.result);
        throw new AppCommandDetachedError('afterStart');
      }
      // A haptic changes no app state. Native acknowledges only the effect;
      // independent snapshot events still carry actual runtime changes.
      if (command.type === 'haptic' || command.type === 'onboarding.geometry' || command.type === 'domainCheck.cancel') return response.result;
      // A staging-only source advance is expected for the in-flight review; an authority change
      // (owner/read/clear revision, credential generation, foreground) retires its token.
      if (['domains.stage','domainCheck.stage'].includes(command.type) && domainReviewEpoch !== this.domainReviewEpoch) {
        await this.discardUndeliveredDomainReview(response.result);
        throw new Error('Read access changed.');
      }
      if (command.type.endsWith('.query') && readEpoch !== this.readEpoch) throw new Error('Read access changed.');
      if(response.projectionFailure==='unavailable'){
        const recover=this.commandProjectionUnavailable(['domains.stage','domainCheck.stage'].includes(command.type));
        // Scoped query values and review handles cannot escape a revoked read.
        if(['domains.stage','domainCheck.stage'].includes(command.type)){
          try{await this.discardUndeliveredDomainReview(response.result);}finally{recover();}
          throw new Error('Read access changed.');
        }
        if(command.type.endsWith('.query'))throw new Error('Read access changed.');
        return response.result;
      }
      if (!response.snapshot) throw new Error('The native app did not return its updated state.');
      if (!command.type.endsWith('.query') && !command.type.startsWith('navigation.') && !['refresh','haptic','onboarding.geometry','guard.gesture','activity.visibility'].includes(command.type)) ++this.invalidation;
      const mayPublish = readEpoch === this.readEpoch || ['domains.stage','domainCheck.stage'].includes(command.type) && domainReviewEpoch === this.domainReviewEpoch;
      if (revokesReads) this.revokeReads();
      if (mayPublish) this.accept(response.snapshot,command.type.startsWith('navigation.'));
      return response.result;
    };
    // A filter compile may run for seconds. Protection Stop and authenticated
    // navigation must reach the native orchestrator immediately while it runs.
    // Apply starts only after the queued review returns a token. Native rechecks
    // that token against the current draft/baseline and rejects overtaken edits.
    if (command.type==='domainCheck.cancel' || command.type==='accountBackup.command' || command.type.startsWith('preparation.') || command.type.startsWith('protection.') || command.type.endsWith('.query') || ['refresh','haptic','onboarding.geometry','guard.gesture','activity.visibility','navigation.presentation','navigation.presentationReady','filter.open','filter.close','filter.refresh','filter.edit'].includes(command.type) || command.type.startsWith('navigation.') || command.type.startsWith('foreground.') || command.type.startsWith('feedback.') || command.type.startsWith('vpnEditor.') || command.type.startsWith('onboarding.') || command.type === 'filter.apply') return execute();
    const result = this.tail.then(execute, execute);
    this.tail = result.catch(() => {});
    return result;
  }
}
