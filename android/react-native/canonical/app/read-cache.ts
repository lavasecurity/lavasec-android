import {AppState} from 'react-native';
import {isCancelledError, QueryClient, type QueryKey} from '@tanstack/react-query';
import type {AppCommand, AppSnapshot} from './contract';
import type {AppStore} from './store';

// Memory only, owned by one native app connection. TanStack owns query identity,
// deduplication and eviction; the hook retains the native credential/lifecycle
// protocol, since cancelling a JS promise cannot dismiss a native auth prompt.
class AppReadCache {
  private makeClient = () => new QueryClient({defaultOptions: {queries: {
    retry: false, staleTime: 0, gcTime: 60_000,
    refetchOnWindowFocus: false, refetchOnReconnect: false,
  }}});
  private client = this.makeClient();
  private pending = new Map<string, Promise<unknown>>();
  peek<T>(key: QueryKey): T | undefined { return this.client.getQueryData<T>(key); }
  async read<T>(key: QueryKey, read: () => Promise<T>): Promise<T> {
    const identity = JSON.stringify(key);
    try {
      return await this.client.fetchQuery({queryKey: key, queryFn: () => {
        const request = read();
        this.pending.set(identity, request);
        const finish = () => {if (this.pending.get(identity) === request) this.pending.delete(identity);};
        void request.then(finish, finish);
        return request;
      }});
    } catch (error) {
      const request = this.pending.get(identity);
      // Cache revocation rejects TanStack's promise immediately. Still wait for
      // the native operation before allowing a foreground retry; its real auth
      // cancellation must reach the hook and stop automatic prompts.
      if (isCancelledError(error) && request) return await request as T;
      throw error;
    }
  }
  clear() {
    const revoked = this.client;
    this.client = this.makeClient();
    // Switch lookup ownership synchronously, then dispose after cancellation
    // settles so a retiring query cannot leave its eviction timer behind.
    void revoked.cancelQueries().then(() => revoked.clear());
  }
}

const caches = new WeakMap<AppStore, AppReadCache>();
export function appReadCache(app: AppStore) {
  let cache = caches.get(app);
  if (!cache) {cache = new AppReadCache(); caches.set(app, cache);}
  return cache;
}
export function clearAppReadCache(app: AppStore) {caches.get(app)?.clear();}

export function mayCacheRead(command: AppCommand | null, snapshot?: AppSnapshot): boolean {
  if (!command) return false;
  // Presentation-cache reads (iOS #807) belong to the native encrypted cache; share codes and
  // QR payloads get no JS retention exception. Android keeps JS caching only for unprotected
  // activity/catalog reads, which the native boundary re-authorizes on resume.
  let surface: 'View Activities' | 'Update domains and lists';
  switch (command.type) {
    case 'activity.query':
    case 'domains.query':
    case 'network.query':
    case 'stats.query': surface = 'View Activities'; break;
    case 'catalog.query': surface = 'Update domains and lists'; break;
    // A new command must choose its authorization/retention policy explicitly.
    // Transaction reviews, credential flows and generation are not cached reads.
    default: return false;
  }
  if (!snapshot || snapshot.security?.unavailable !== false) return false;
  // Unknown/protected surfaces keep their existing fresh native authorization.
  return snapshot.session?.protectedActions?.[surface] === false;
}

export function readPrivacyScope(snapshot: AppSnapshot): string {
  const ordered = (values?: Record<string, boolean>) => Object.entries(values ?? {}).sort(([a], [b]) => a.localeCompare(b));
  return JSON.stringify([snapshot.generation, snapshot.account?.signedIn, snapshot.account?.status, snapshot.account?.detail,
    snapshot.session?.passcode, ordered(snapshot.session?.protectedActions), ordered(snapshot.session?.logs),
    snapshot.security?.unavailable, snapshot.security?.readRevision, snapshot.security?.sourceRevision,
    snapshot.security?.ownerRevision, snapshot.security?.displayClearRevision]);
}

/**
 * Staging advances the source revision itself; only authority changes retire its review handoff.
 * Mirrors the pinned iOS `domainReviewPrivacyScope`: a `domains.stage` whose own source-only
 * snapshot lands before its token must keep the staged review alive while ordinary private reads
 * still invalidate. A projection without a native owner identity keeps the strict read fence.
 */
export function domainReviewPrivacyScope(snapshot: AppSnapshot): string {
  if (snapshot.security?.ownerRevision === undefined) return readPrivacyScope(snapshot);
  return readPrivacyScope({...snapshot, security: {...snapshot.security, sourceRevision: undefined}});
}

/** Only an explicit native opt-out permits continuity of an already-painted frame. */
export function mayRetainPresentationFrame(snapshot?:AppSnapshot|null):boolean {
  return snapshot?.backgroundPrivacyCoverRequired===false;
}

/** Runtime callbacks require current foreground fields and a completed resume layout. */
export function mayInteractWithPresentation(app?:AppStore):boolean {
  // Isolated review adapters have no native snapshot owner.
  if(!app?.getSnapshot)return true;
  return AppState.currentState==='active'&&(app.getPresentationAuthority?.()??!!app.getSnapshot().snapshot)&&!app.getPresentationHydration?.().required;
}

/** Painted opt-out frames ignore routine grant revocation, while data ownership and policy still bind them. */
export function readDisplayScope(snapshot:AppSnapshot):string {
  if(snapshot.security?.ownerRevision!==undefined&&snapshot.security.displayClearRevision!==undefined) {
    const ordered=(values?:Record<string,boolean>)=>Object.entries(values??{}).sort(([a],[b])=>a.localeCompare(b));
    return JSON.stringify([snapshot.security.ownerRevision,snapshot.security.displayClearRevision,
      mayRetainPresentationFrame(snapshot)?undefined:snapshot.session?.passcode,ordered(snapshot.session?.protectedActions),ordered(snapshot.session?.logs),snapshot.security.unavailable]);
  }
  return readPrivacyScope({...snapshot,security:{...snapshot.security,readRevision:undefined}});
}

/** Unsaved forms belong to an owner/privacy boundary, independent of ordinary native refresh or clear revisions. */
export function presentationOwnerScope(snapshot:AppSnapshot):string {
  const ordered=Object.entries(snapshot.session?.protectedActions??{}).sort(([a],[b])=>a.localeCompare(b));
  const owner=snapshot.security?.ownerRevision??[snapshot.account?.signedIn,snapshot.account?.status,snapshot.account?.detail,snapshot.security?.sourceRevision];
  return JSON.stringify([owner,mayRetainPresentationFrame(snapshot)?undefined:snapshot.session?.passcode,ordered,snapshot.security?.unavailable]);
}

/** Ordinary settings scaffolds survive policy edits; actual owner replacement still retires their viewport. */
export function presentationScaffoldOwnerScope(snapshot:AppSnapshot):string {
  const owner=snapshot.security?.ownerRevision??[snapshot.account?.signedIn,snapshot.account?.status,snapshot.account?.detail,snapshot.security?.sourceRevision];
  return JSON.stringify([owner,snapshot.security?.unavailable]);
}
