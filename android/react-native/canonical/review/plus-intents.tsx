import {createContext,useContext,useEffect,useMemo,type ReactNode} from 'react';
import {mayInteractWithPresentation} from '../app/read-cache';
import type {AppSnapshot} from '../app/contract';
import type {AppStore} from '../app/store';

export const plusMessages = {
  customBlocklist:'Upgrade to Lava Plus to add your blocklists',
  customDNS:'Upgrade to Lava Plus to add custom DNS',
  blockedDomains:'Upgrade to Lava Plus to block more domains',
  allowedDomains:'Upgrade to Lava Plus to add more exceptions',
  rules:'Upgrade to Lava Plus to use more blocklist rules',
  filters:'Upgrade to Lava Plus to add more filters',
  frozenFilter:'Upgrade to Lava Plus to manage this filter',
  guards:'Upgrade to Lava Plus to unlock all Lava Guards',
  vpn:'Upgrade to Lava Plus to use VPN chaining',
  fullImport:'Use Lava Plus for full import',
} as const;
export type PlusReason = keyof typeof plusMessages;
export type PlusDestination = {name:'CustomEntry';params:{id:string;kind:'dns'|'blocklist'}}|{name:'Filter';params:{id:string;retirementLease?:string;retirementGeneration?:string}}|{name:'Review';params:{id:string;standaloneReview:string}};
export type PlusResume = (isCurrent?:()=>boolean) => Promise<PlusDestination|void>;
/** Capture at the owned destination boundary; delayed retirement never samples a newer visit. */
export function capturePlusRetirement(destination:PlusDestination|void,snapshot?:AppSnapshot,sourceGeneration?:string):PlusDestination|void {
  if(destination?.name!=='Filter'||destination.params.retirementLease)return destination;
  if(sourceGeneration!==undefined&&snapshot?.generation!==sourceGeneration)return destination;
  const session=(snapshot as (AppSnapshot&{filterSession?:{id:string;lease?:string}})|undefined)?.filterSession;
  if(session?.id!==destination.params.id||!session.lease)return destination;
  return {...destination,params:{...destination.params,retirementLease:session.lease,retirementGeneration:snapshot?.generation}};
}
// Callbacks stay with the mounted source screen, never in persisted navigation
// params. Privacy-owner retirement and source removal discard pending work.
let plusIntentSerial=0;
export class PlusIntents {
  private pending=new Map<string,PlusResume>();
  add(resume:PlusResume){const id=String(++plusIntentSerial);this.pending.set(id,resume);return id;}
  remove(id:string){this.pending.delete(id);}
  take(id:string){const resume=this.pending.get(id);this.pending.delete(id);return resume;}
  /** Reads a pending resume WITHOUT consuming it, so a rejected resume can be retried. */
  peek(id:string){return this.pending.get(id);}
  clear(){this.pending.clear();}
}
const context=createContext<PlusIntents|null>(null);
/**
 * Unsaved paid-feature work belongs to one account/privacy boundary. Android has no RN
 * presentation-hydration model (cover/hydration is native), so the boundary is the confirmed
 * account identity plus the local privacy policy. A change to any of these retires the registry
 * and discards every pending intent; a transient refresh that leaves them equal never replays.
 */
export function plusPresentationScope(snapshot:AppSnapshot):string {
  const ordered=Object.entries(snapshot.session?.protectedActions??{}).sort(([a],[b])=>a.localeCompare(b));
  // `generation` is the native credential-graph generation: it rotates on account/credential
  // change, so a same-provider account swap (invisible in status/detail) still retires pending
  // intents, matching iOS `presentationOwnerScope`'s owner revision.
  return JSON.stringify([snapshot.generation,snapshot.account?.signedIn,snapshot.account?.status,snapshot.account?.detail,
    snapshot.session?.passcode,ordered,snapshot.security?.unavailable]);
}
/** Runtime callbacks require a live foreground snapshot before they may resume native work. */
export function mayResumePlusIntent(app?:AppStore):boolean {
  return mayInteractWithPresentation(app);
}
/** A transient Plus-resume refusal that must stay registered until its prerequisite refreshes. */
export class PlusResumePending extends Error {
  constructor(){super('plus-resume-pending');this.name='PlusResumePending';}
}
/**
 * Whether the current live snapshot permits a Plus resume to reach the native owner. The whole-app
 * lock (`appUnlock`) refuses every native command (`rejected reason=app-locked`) until the current
 * navigation turn is authenticated, so a resume must wait for the grant rather than fire and strand.
 *
 * FAIL-CLOSED on an absent/unavailable/recovery security family: native `isLocked(APP_UNLOCK)` is
 * `security?.isAuthorized(surface) != true` and treats a missing owner as locked, so a resume in
 * that state must not fire and hammer the bridge. This mirrors the native predicate exactly:
 * `available && !recoveryRequired && (!enabled || surface !in protectedSurfaces || surface in
 * authenticatedSurfaces)`. `hasAuthenticationMethod` is native `enabled`; a credential-only or
 * unprotected configuration is authorized, and an explicit `appUnlock` opt-in requires the
 * current-turn grant.
 */
export function appUnlockAuthorized(live?:AppSnapshot):boolean {
  const security=live?.security;
  if(!security)return false;
  if(security.unavailable===true||security.recoveryRequired===true)return false;
  if(!security.hasAuthenticationMethod)return true;
  if(!(security.protectedSurfaces??[]).includes('appUnlock'))return true;
  return (security.authenticatedSurfaces??[]).includes('appUnlock');
}
/**
 * A Plus resume refusal worth retrying once App Unlock authorization lands. Native answers every
 * command with `reason=app-locked` while `isLocked(APP_UNLOCK)`, so the bridge names that refusal;
 * a snapshot that still reports the lock (authorization not yet granted) is the same condition. A
 * user-cancelled authentication is never retried, and an unrelated permanent failure is surfaced.
 */
export function retryablePlusResume(error:unknown,live?:AppSnapshot):boolean {
  const message=(error as Error)?.message??'';
  if(message==='Authentication cancelled.')return false;
  return /app[-\s]?lock/i.test(message)||/unlock/i.test(message)||!appUnlockAuthorized(live);
}
export function PlusIntentProvider({retirementKey,children}:{retirementKey?:string;children:ReactNode}){
  const intents=useMemo(()=>new PlusIntents(),[retirementKey]);
  useEffect(()=>()=>intents.clear(),[intents]);
  return <context.Provider value={intents}>{children}</context.Provider>;
}
export function usePlusIntents(){return useContext(context)!;}
