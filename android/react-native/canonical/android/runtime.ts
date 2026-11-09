import {NativeModules,DeviceEventEmitter,Alert} from 'react-native';
import {parseProductSnapshot,type ProductSnapshot} from '../../src/productContract';
import type {AppSnapshot,AppCommand} from '../app/contract';
import {AppCommandDetachedError} from '../app/store';
import {protectedActionNames} from '../review/session';
import {projectGuards} from './guardCatalog';
import {IMPORT_FLOWS,ownerReasonCopy} from '../review/import-model';
import {localized,localizedFormat,localizedNumber} from '../app/presentation';
import {translations} from '../app/translations';
import {CANONICAL_LOCALE,resolveLavaLocale} from '../../src/i18n/locales';

type Wire = ProductSnapshot & {
  generation?:string|null;
  build?:string;
  plus?:AppSnapshot['plus']; limits?:AppSnapshot['limits'];
  preferences?:{appearance:string;look:string;matchTextSize:boolean;textSize:number;haptics:boolean};
  hasDomainHistory?:boolean|null; domainHistoryCount?:number|null;
};
const native=NativeModules.NativeLavaApp as {getSnapshot():Promise<string>;getSnapshotForHost?(hostID:string):Promise<string>;command(request:string):Promise<string>};
type WireCache={nativeHostID?:string;latest?:Wire};
const unboundCache:WireCache={};
function staleHostError(error:unknown):boolean {
  return !!error&&typeof error==='object'&&('code' in error&&error.code==='stale-host'||'message' in error&&error.message==='stale-host');
}
function acceptWire(payload:string,cache:WireCache,exact=false):Wire {
  const packet=parseProductSnapshot(payload) as Wire;
  if(cache.nativeHostID!==undefined&&packet.nativeHostID!==cache.nativeHostID)throw new AppCommandDetachedError('afterStart');
  if(!cache.latest||packet.revision>=cache.latest.revision)cache.latest=packet;
  return exact?packet:cache.latest;
}
async function readWire(cache:WireCache):Promise<Wire> {
  try {
    const payload=cache.nativeHostID!==undefined&&native.getSnapshotForHost
      ?await native.getSnapshotForHost(cache.nativeHostID):await native.getSnapshot();
    return acceptWire(payload,cache);
  } catch(error) {
    if(cache.nativeHostID!==undefined&&staleHostError(error))throw new AppCommandDetachedError('afterStart');
    throw error;
  }
}
const labels:Record<string,string>={inactive:'Protection is off',starting:'Starting protection',awaiting_network:'Waiting for a network',protected:'Protection is on',reconnecting:'Reconnecting',paused:'Protection is paused',stopping:'Stopping protection',attention:'Protection needs attention',unavailable:'Protection is unavailable'};
const actions:Record<string,string>={turnOn:'Turn on protection',turnOff:'Turn off protection',resume:'Resume protection',reconnect:'Reconnect',settings:'Open system settings'};
const connectivity:Record<string,string>={healthy:'DNS protection is working.',internet_unverified:'Local filtering is active. Internet access on this network has not been verified.',rechecking:'Lava is checking protection after waking.',encrypted_fallback:'Using the encrypted fallback resolver.',plain_dns_fallback:'Using the standard DNS fallback resolver.',device_dns_fallback:'Using device DNS as fallback.',recovering:'Recovering the connection.',dns_slow:'DNS is responding slowly.',network_unavailable:'No network connection.',needs_reconnect:'Reconnect to restore DNS protection.',unavailable:'No confirmed connectivity assessment yet.'};
/** The user-facing notification category labels the shared customization screen renders. */
const notificationLabels=['Filter changes',"Filter couldn't switch",'Protection resumed','Connection updates'] as const;
/**
 * Canonical protected-action titles to the Android `SecurityOwner` protected-surface wire ids.
 * The titles are the pinned `protectedActionNames`/`settings.set` suffixes; the ids are the exact
 * values the native `security.protectedSurfaces` family emits. A title with no id is never on.
 */
const securitySurfaceByTitle:Record<string,string>={'App Unlock':'appUnlock','Turn on/off Lava':'protectionControl','Pause Lava':'protectionPause','Update domains and lists':'filterEditing','View Activities':'activityViewing','Update App Settings':'appSettings'};
/**
 * Canonical per-category notification preferences. An owner that does not project the family
 * (or a partial set) yields only the confirmed categories, never a fabricated toggle.
 */
function notifications(p:Wire):Record<string,boolean>{
  const projected=p.notifications;
  if(!projected)return {};
  return Object.fromEntries(notificationLabels.filter(key=>typeof projected[key]==='boolean').map(key=>[key,projected[key]!]));
}

/**
 * Canonical `account` family from confirmed owner facts. When the native account owner is not
 * installed the family is absent, and the honest unavailable fallback (busy, so the provider
 * controls stay inert) is rendered instead. Link state is owner-confirmed only; the canonical
 * account shape carries no capability field, so provider availability stays on the wire
 * (`p.account.googleAvailable`/`appleAvailable`) and the owner's refusal is mapped to copy at the
 * command boundary.
 */
function account(p:Wire):AppSnapshot['account']{
  const a=p.account;
  if(!a)return {signedIn:false,status:'Account unavailable',detail:'Account controls are not connected to this interface yet.',appleTitle:'Sign in with Apple',googleTitle:'Sign in with Google',appleConnected:false,googleConnected:false,busy:true,message:''};
  return {signedIn:a.signedIn,status:a.status,detail:a.detail,appleTitle:a.appleTitle,googleTitle:a.googleTitle,appleConnected:a.appleConnected,googleConnected:a.googleConnected,appleBusy:a.appleBusy,googleBusy:a.googleBusy,busy:a.busy,message:a.message};
}

/**
 * The canonical `backup.remoteStatus` is the iOS `backupStatusSubtitle`: already-localized user
 * copy, never the stable code. The Android wire carries the code (`unavailable`/`noBackup`/
 * `checking`/`available`), so this adapter localizes it with the same pinned catalog strings the
 * shared screen renders. An unconfigured service (`unavailable`) therefore reads as honest
 * unavailable copy instead of leaking a raw wire token.
 */
function remoteBackupSubtitle(status:string):string{
  switch(status){
    case 'available':return localized('Backup available');
    case 'noBackup':return localized('No backup');
    case 'checking':return localized('Checking for a backup…');
    default:return localized('Backup status unavailable');
  }
}

/** The shared Today graph requires exact numeric counters and today's owner calendar range. */
function todayCounts(p:Wire):AppSnapshot['protection']['today'] {
  if(!p.available||!p.foreground||p.activity.locked||!p.logging)return;
  if(!p.logging.keepCounts)return {countsEnabled:false,allowed:0,blocked:0};
  const a=p.activity;
  if(a.status!=='ready'||!a.todayDate||a.startDate!==a.todayDate||a.endDate!==a.todayDate||a.allowed===null||a.blocked===null)return;
  const allowed=Number(a.allowed),blocked=Number(a.blocked);
  if(!Number.isSafeInteger(allowed)||!Number.isSafeInteger(blocked)||!Number.isSafeInteger(allowed+blocked))return;
  return {countsEnabled:true,allowed,blocked};
}

/** Shape adapter over confirmed Android owner facts. No preview fixtures or optimistic mutations. */
// Resolve DNS app copy from the receipt's own locale, without borrowing a newer root's locale.
function dnsCopy(p:Wire,value:string):string {
  const locale=p.presentation?.locale??'en';
  return (translations[CANONICAL_LOCALE[resolveLavaLocale(locale)]]??{})[value]??value;
}
function dnsConnection(p:Wire):AppSnapshot['connection'] {
  const connection=p.connection;if(!connection)return undefined;
  const display=(part:NonNullable<Wire['connection']>['dns']['primary'])=>({name:part.nameIsDefault===true?dnsCopy(p,part.name):part.name,detail:part.detail,transport:part.transport});
  return {...connection,dns:{...connection.dns,primary:display(connection.dns.primary),
    ...(connection.dns.fallback===undefined?{}:{fallback:connection.dns.fallback===null?null:display(connection.dns.fallback)})}};
}
function dnsSummary(p:Wire,connection:AppSnapshot['connection']):string {
  if(p.settingsSummary?.dns===undefined)return 'Unavailable';
  if(!connection)return p.settingsSummary.dns;
  const name=connection.dns.primary.name;
  return connection.dns.fallback?dnsCopy(p,'%@ + Fallback').replace('%@',()=>name):name;
}
/** Same material/action semantics as GuardStatusPresentation on iOS, from native facts.
 * A running tunnel alone cannot affirm protection: health must also be confirmed.
 */
function guardPresentation(p:Wire):Pick<AppSnapshot['protection'],'materialIntent'|'actionTone'|'mood'> {
  const state=(materialIntent:NonNullable<AppSnapshot['protection']['materialIntent']>,actionTone:NonNullable<AppSnapshot['protection']['actionTone']>):Pick<AppSnapshot['protection'],'materialIntent'|'actionTone'|'mood'>=>{
    const recovering=['reconnecting','awaiting_network'].includes(p.protection.lifecycle)||['recovering','network_unavailable','rechecking'].includes(p.protection.connectivity);
    const mood=materialIntent==='rest'||materialIntent==='stopping'?'sleeping':materialIntent==='affirmed'?'awake':
      materialIntent==='paused'?'paused':materialIntent==='unresolved'?'waking':materialIntent==='recovery'?(recovering?'retrying':'concerned'):
      p.protection.error||p.protection.connectivity==='internet_unverified'?'concerned':p.protection.lifecycle==='protected'?'waking':'retrying';
    return {materialIntent,actionTone,mood};
  };
  if(!p.foreground||p.protection.error||p.onboardingRecoveryRequired||p.onboardingReconstructionInFlight)return state('unknown','recovery');
  if(p.onboardingRequired&&p.onboardingAvailable)return state('rest','affirmative');
  if(!p.available)return state('unknown','recovery');
  switch(p.protection.lifecycle){
    case 'inactive':return state('rest','affirmative');
    case 'starting':return state('unresolved','quiet');
    case 'stopping':return state('stopping','quiet');
    case 'paused':return state('paused','affirmative');
    case 'reconnecting':case 'awaiting_network':case 'attention':return state('recovery','recovery');
    case 'protected':
      if(p.protection.chainedEstablishing)return state('unresolved','quiet');
      switch(p.protection.connectivity){
        case 'internet_unverified':return state('unknown','quiet');
        case 'rechecking':return state('recovery','recovery');
        case 'healthy':return state('affirmed',p.protection.action==='reconnect'?'recovery':'quiet');
        case 'recovering':case 'encrypted_fallback':case 'plain_dns_fallback':case 'device_dns_fallback':
        case 'dns_slow':case 'network_unavailable':case 'needs_reconnect':return state('recovery','recovery');
        default:return state('unknown','quiet');
      }
    default:return state('unknown','recovery');
  }
}
/** The shared detail status describes freshness for this filter, not just VPN activity. */
function filterFreshness(p:Wire,detail:Wire['filterSession']):AppSnapshot['filterStatus'] {
  const selected=p.filters.rows.find(row=>row.id===(detail?.id??p.filters.rows.find(row=>row.active)?.id))
    ??(p.newFilter&&p.newFilter.id===detail?.id?{...p.newFilter,active:false}:undefined);
  if(!p.available||!p.foreground||p.filters.locked||!selected)return {title:'Unavailable',icon:'exclamationmark.circle',label:'rules',warning:true};
  const active=selected.active,empty=detail?detail.lists.length===0&&detail.blocked.length===0:selected.empty;
  const fresh=p.catalog?.fresh!==false&&!p.catalog?.sourceRepairRequired&&
    !['offline','persistFailed','installFailed','keptInstalled','bundledFallback'].includes(p.catalog?.lastStatus??'');
  return {title:empty?(active?'Blocks nothing — not protected':'Blocks nothing'):!active?'Not in effect':fresh?'Filter up to date':'Update recommended',
    icon:empty?'exclamationmark.circle':!active?'pause.circle':fresh?'checkmark.circle.fill':'arrow.clockwise',
    label:active?'rules in effect':'rules',warning:!!empty||active&&!fresh};
}
export function projectAndroidSnapshot(p:Wire):AppSnapshot {
  const active=p.filters.rows.find(filter=>filter.active);
  const primary=p.dns?.tiers?.[0];
  const connection=dnsConnection(p);
  const detail=p.available&&!p.filters.locked&&p.foreground?p.filterSession:undefined;
  const paused=p.protection.lifecycle==='paused';
  const chainedEstablishing=p.available&&p.foreground&&p.protection.chainedEstablishing===true&&
    ['starting','reconnecting','protected'].includes(p.protection.lifecycle);
  const ready=p.available&&p.foreground;
  // Shared GuardStatusPresentation keeps the stop label during transitions.
  // Native still owns whether the action exists and whether input is disabled.
  const protectionAction=p.protection.action??(ready&&['starting','stopping'].includes(p.protection.lifecycle)?'turnOff':undefined);
  const reloadingSetup=p.onboardingReconstructionInFlight===true;
  const setupRecovery=p.onboardingRecoveryRequired===true||reloadingSetup;
  const setupRequired=p.onboardingRequired||setupRecovery;
  const wireSecurity=p.security;
  // A live SecurityOwner projects the family. A missing credential cannot protect a surface, so
  // the opt-in set is emptied unless the owner confirms a method; an absent family never invents
  // protection the owner cannot enforce.
  const hasAuthenticationMethod=wireSecurity?(wireSecurity.hasAuthenticationMethod===true&&!wireSecurity.unavailable):p.settings.securityEnabled;
  const protectedSurfaceIds=new Set<string>(wireSecurity?.protectedSurfaces??[]);
  const protectedActions=Object.fromEntries(protectedActionNames.map(name=>[name,hasAuthenticationMethod&&protectedSurfaceIds.has(securitySurfaceByTitle[name]??'')])) as AppSnapshot['session']['protectedActions'];
  return {
    schema:1,fullApp:true,revision:p.revision,generation:p.generation??undefined,nativeHostID:p.nativeHostID,version:p.version,build:p.build??'Unavailable',
    domainCheckAvailable:p.domainCheckAvailable===true,domainCheckRevision:p.domainCheckRevision,
    presentation:p.presentation??{locale:'en',textScales:null},
    settingsSummary:{dns:dnsSummary(p,connection),privacy:p.settingsSummary?.privacy??'Unavailable',security:p.settingsSummary?.security??'Unavailable'},
    foreground:p.foreground,
    available:p.available||!!p.onboardingSetup||p.onboardingAvailable===true,
    presentationBlocked:p.presentationBlocked,
    presentationError:p.presentationError?localized(p.presentationError):p.presentationError,
    appPrivacyCoverRequired:p.appPrivacyCoverRequired,
    backgroundPrivacyCoverRequired:p.backgroundPrivacyCoverRequired,
    foregroundFlow:p.foregroundFlow?.kind==='accountBackup'&&(!ready||p.presentationBlocked||
      wireSecurity?.unavailable||['appUnlock','appSettings',...(p.foregroundFlow.accountBackup?.kind==='backupRestore'?['filterEditing']:[])].some(surface=>
        (wireSecurity?.protectedSurfaces as string[]|undefined)?.includes(surface)&&
        !(wireSecurity?.authenticatedSurfaces as string[]|undefined)?.includes(surface)))?undefined:
      p.foregroundFlow?.accountBackup?{...p.foregroundFlow,accountBackup:{...p.foregroundFlow.accountBackup,
        error:localized(backupReasons[p.foregroundFlow.accountBackup.error]??p.foregroundFlow.accountBackup.error)}}:p.foregroundFlow,
    foregroundClosing:p.foregroundClosing,
    customEntry:p.customEntry,onboardingSetup:p.onboardingSetup,
    navigation:p.navigation,
    filterSession:p.filterSession?{id:p.filterSession.id,lease:p.filterSession.lease}:undefined,
    security:wireSecurity?{appUnlockCoverChoice:wireSecurity.appUnlockCoverChoice,readRevision:wireSecurity.readRevision,ownerRevision:wireSecurity.ownerRevision,sourceRevision:wireSecurity.sourceRevision,displayClearRevision:wireSecurity.displayClearRevision,unavailable:wireSecurity.unavailable,recoveryRequired:wireSecurity.recoveryRequired===true,protectedSurfaces:wireSecurity.protectedSurfaces??[],authenticatedSurfaces:wireSecurity.authenticatedSurfaces??[],hasAuthenticationMethod,updatingSurface:wireSecurity.updatingSurface===true,showBiometrics:wireSecurity.showBiometrics===true,canEnableBiometrics:wireSecurity.canEnableBiometrics===true,biometricTitle:wireSecurity.biometricTitle??'Biometrics',status:wireSecurity.status??''}:{unavailable:true,showBiometrics:false,canEnableBiometrics:false,biometricTitle:'Biometrics',status:'Security settings are not connected to this interface yet.'},
    protection:{...guardPresentation(p),today:todayCounts(p),title:setupRecovery?'Reload your setup':setupRequired?'Set up your protection':(p.protection.lifecycle==='protected'&&p.protection.connectivity==='internet_unverified'?'Internet unverified':p.protection.lifecycle==='protected'&&p.protection.connectivity==='rechecking'?'Checking protection':labels[p.protection.lifecycle]??labels.unavailable!),
      subtitle:setupRecovery?(reloadingSetup?'Lava is reloading the saved setup on this device. Please wait.':'Lava needs to reload the saved setup on this device before you continue. Unsaved choices will not be applied.'):setupRequired?'Choose your protection and allow Lava to create a local VPN connection.':p.protection.error??(chainedEstablishing?'Setting up VPN chaining.':connectivity[p.protection.connectivity]??'Checking the connection.'),
      action:setupRecovery?(reloadingSetup?'Reloading setup':'Reload setup'):setupRequired?'Continue setup':actions[protectionAction??'']??'Unavailable',
      disabled:!p.foreground||reloadingSetup||(setupRequired?!(p.available||p.onboardingAvailable===true||p.onboardingSetup):!ready||p.protection.disabled),configuring:reloadingSetup||(ready&&!setupRequired&&p.protection.commandInFlight===true),
      status:p.protection.lifecycle==='protected'?1:0,paused,canPause:ready&&p.protection.canPause,
      rules:active?.ruleCount??0,activity:p.activity.locked?'Activity is locked':'Activity summary unavailable',
    },
    edition:p.edition,
    portableBackup:p.portableBackup,
    account:account(p),
    backup:{...p.backup,remoteStatus:remoteBackupSubtitle(p.backup.remoteStatus),detail:p.backup.detail??undefined},
    plus:p.plus??{enabled:false,busy:true,checking:false,showsYearlyPaidMonthly:false,expiration:'',message:'Google Play is unavailable.',offers:[]},
    session:{filterID:detail?.id??active?.id??'',activeFilterID:active?.id??'',filter:detail?.name??active?.name??'Unavailable',activeFilter:active?.name??'Unavailable',editing:detail?.editing??false,blocklists:detail?.lists??[],savedBlocklists:detail?.savedLists??[],logs:ready&&p.logging?{'Filtering Counts':p.logging.keepCounts,'Domain logs':p.logging.keepHistory,'Network activity':p.logging.keepNetworkActivity,'Lava Guard Progress':p.logging.keepLavaGuardProgress}:{},notifications:notifications(p),
      deviceDNS:primary?.transport==='IP',fallback:(p.dns?.tiers?.length??0)>1,provider:primary?.name??'Unavailable',transport:primary?.transport??'Unavailable',
      matchTextSize:p.preferences?.matchTextSize??true,textSize:p.preferences?.textSize??3,haptics:p.preferences?.haptics??false,liveActivities:false,matchIcon:false,passcode:hasAuthenticationMethod,biometrics:wireSecurity?.biometrics===true,
      protectedActions,
    },
    look:p.preferences?.look??'original',draft:{blocked:detail?.blocked??[],allowed:detail?.allowed??[]},savedDraft:{blocked:detail?.savedBlocked??[],allowed:detail?.savedAllowed??[]},
    filterEditing:detail&&p.filterEditing?{...p.filterEditing,validation:p.filterEditing.validation??undefined}:undefined,
    libraryEditing:!p.filters.locked&&p.foreground?p.libraryEditing:undefined,
    guards:projectGuards({currentLook:p.preferences?.look,hasPlus:ready&&p.plus?.enabled===true,unlockedIDs:ready?p.guardProgress?.unlockedIDs:undefined,qualifiedUsageDays:ready?p.guardProgress?.qualifiedUsageDays:0,progressEnabled:ready&&p.logging?.keepLavaGuardProgress===true}),
    dns:p.dns??{editable:false,providers:[],transports:[],customSelected:false},
    vpn:p.vpn,
    connection,
    liveActivityPauseMinutes:5,liveActivityPause:{available:false,label:'Unavailable on Android',minutes:[]},qaTools:p.qaTools===true,
    confirmations:{
      'Clear filtering counts':{title:'Clear filtering counts?',message:'This removes the filtering totals saved on this device. Domain history and network activity are unchanged.',action:'Clear Counts'},
      'Clear domain history':{title:'Clear domain history?',message:'This removes saved domain rows from this device. Filtering counts and network activity are unchanged.',action:'Clear History'},
      'Clear network activity':{title:'Clear network activity?',message:'This removes the network activity saved on this device. Filtering counts and domain history are unchanged.',action:'Clear Network Activity'},
      'Clear all logs':{title:'Clear all logs?',message:'This removes filtering counts, domain history, network activity and unearned Lava Guard progress from this device. Earned Lava Guards stay unlocked.',action:'Clear All Logs'},
    },
    limits:p.limits??{maxAllowedDomains:0,maxBlockedDomains:0,maxFilterRules:0,maxFilters:0,allowsCustomBlocklists:false,allowsCustomDNS:false},
    filterPreparationPresented:p.filterPreparationPresented===true,
    preparation:p.foreground&&!p.filters.locked&&!p.presentationBlocked&&p.preparation?{
      ...p.preparation,title:localized(p.preparation.title),
      message:customBlocklistReason({type:'filter.apply',id:p.preparation.filterId,review:''},
        p.preparation.failureReason??undefined,p.preparation.failureSource??undefined)??localized(p.preparation.message),
    }:undefined,
    logExportBusy:false,logExportError:'',hasDomainHistory:p.hasDomainHistory===true,
    ...(typeof p.domainHistoryCount==='number'?{domainHistoryCount:p.domainHistoryCount}:{}),
    filterStatus:filterFreshness(p,detail),
    blocklistNames:detail?.names??{},
    blocklistMetadata:Object.fromEntries([...new Set([...p.filters.rows.flatMap(filter=>filter.lists??[]),...(detail?.lists??[])])].map(sourceID=>{
      const count=detail?.ruleCounts?.[sourceID],custom=detail?.customLists?.includes(sourceID);
      const text=count===undefined?(custom?'Pending refresh · Custom List':p.catalog?.installed==='bundled'||!p.catalog?'Not updated yet':'Unavailable'):
        localizedFormat(custom?'%@ rules · Custom List':'%@ rules',localizedNumber(count));
      return [sourceID,localized(text)];
    })),
    filters:p.filters.rows.map(filter=>({id:filter.id,name:filter.name,emoji:filter.emoji,empty:filter.empty,blockedDomainCount:filter.blockedDomainCount,allowedExceptionCount:filter.allowedExceptionCount,frozen:filter.frozen,count:filter.ruleCount==null?'Unavailable':filter.ruleCount.toLocaleString(),lists:filter.lists??[],shareable:filter.empty!==true,shareSummary:''})),
    newFilter:ready&&!p.filters.locked&&p.newFilter?{...p.newFilter,frozen:false,count:'Unavailable',shareable:false,shareSummary:''}:undefined,
    sudoku:ready&&p.logging?.keepLavaGuardProgress?p.sudoku:undefined,
  };
}
// The unbound exports remain useful to unit hosts. Production React roots use an
// isolated facade below; no host-scoped read can consult this shared cache.
export function acceptAndroidWire(payload:string) {
  return projectAndroidSnapshot(acceptWire(payload,unboundCache));
}
export async function readAndroidWire():Promise<Wire> {
  return readWire(unboundCache);
}
/** Ordinary native presentation preferences still belong to their originating root. */
export async function readAndroidWireForHost(nativeHostID:string):Promise<Wire> {
  if(!nativeHostID)throw new AppCommandDetachedError('beforeStart');
  return readWire({nativeHostID});
}
const unsupported=()=>new Error('This feature is not connected on Android yet.');
/**
 * Canonical DNS refusal/outcome reasons mapped to user copy. The native owner refuses custom DNS
 * with `custom-resolver-runtime-absent` because the Android engine has no custom-resolver route
 * yet; that is surfaced verbatim as an honest unavailable state — never a faked save. Codes are
 * never rendered directly, and a missing owner selection is an explicit failure.
 */
const dnsReasons:Record<string,string>={
  'chained-edit-locked':'VPN chaining manages DNS for this tunnel. Turn chaining off or use a split tunnel to edit.',
  'stale-editor':'The DNS settings changed elsewhere. Reopen the editor and try again.',
  'invalid-tier-count':'A DNS ladder needs a primary and at most one secondary.',
  'unknown-provider':'That DNS provider is not available.',
  'unknown-transport':'That DNS transport is not available.',
  'custom-requires-plus':'Custom DNS requires Lava Plus.',
  'custom-invalid':'That resolver address is not valid.',
  'custom-transport-mismatch':'The address and the selected transport do not match.',
  'custom-secondary-unsupported':'A secondary address is not supported on Android yet. Enter one address.',
  'transport-not-offered':'That provider does not offer this transport.',
  'transport-unavailable':'That DNS option is not available in this build yet.',
  'custom-resolver-runtime-absent':'Custom DNS is not available on this device yet.',
  'dns-unserved':'Lava cannot serve custom DNS on this device yet.',
  'dns-commit-failed':'Lava could not save the DNS changes. Try again.',
  'unsupported-custom-dns':'Custom DNS is not connected on Android yet.',
  'fallback-not-eligible':'That provider cannot be used as a fallback.',
  'duplicate-tier':'That provider is already in the ladder.',
  'invalid-tier-index':'That DNS tier cannot be toggled on this device.',
  'unauthorized':'Unlock Lava to change DNS settings.',
  'unavailable':'Lava could not change DNS settings right now.',
};
function dnsReason(type:string,reason?:string):string|undefined{
  if(!reason||!type.startsWith('dns.'))return undefined;
  // A custom save can only fail as runtime-absent on Android; name the feature rather than the
  // generic transport, so the copy matches the action the user took.
  if((type==='dns.custom'||type==='dns.customDraft'||type==='dns.customStage')&&(reason==='transport-unavailable'||reason==='custom-resolver-runtime-absent'))return dnsReasons['custom-resolver-runtime-absent'];
  return dnsReasons[reason];
}
/**
 * Canonical account refusal reasons mapped to user copy. The Android owner refuses Apple sign-in
 * (`account-apple-unavailable`, no Apple credential adapter) and an unconfigured Google client
 * (`account-google-unavailable`) truthfully; a superseded operation is a stale-account fence. No
 * credential is ever involved, and a code is never rendered directly.
 */
const accountReasons:Record<string,string>={
  'account-apple-unavailable':'Apple sign-in is not available on Android yet.',
  'account-google-unavailable':'Google sign-in is not available on this device.',
  'account-not-signed-in':'Sign in to your account first.',
  'account-service-unavailable':'Account is not available on this device yet.',
  'account-busy':'Another sign-in is already in progress.',
  'account-superseded':'The account changed while this was in progress. Try again.',
  'account-operation-failed':'That account action could not be completed. Try again.',
};
function accountReason(type:string,reason?:string):string|undefined{
  if(!reason||!type.startsWith('account.'))return undefined;
  return accountReasons[reason];
}
/**
 * Canonical encrypted-backup refusals mapped to honest copy. Setup and restore are real native
 * flows on Android: the setup dialog seals the live payload with a recovery phrase, and the restore
 * owner downloads + decrypts the account envelope for a reviewed apply. Each refusal names its
 * exact cause (sign in required, no confirmed filter, payload too large, seal/local-write failure,
 * wrong secret, corrupt payload, unsupported schema, changed resolver, stale review) rather than a
 * blanket unavailable. `backup.automatic` is a real backup-owner preference, so a refused write
 * names the setting. No recovery material or restore review handle is ever part of a backup command
 * or its result.
 */
const backupReasons:Record<string,string>={
  'backup-pending-missing':'No encrypted import is saved for later.',
  'backup-pending-unreadable':'A saved encrypted import could not be read. Discard it and import the original file again.',
  'backup-pending-save-failed':'Lava could not save the encrypted import. Keep the original file and try again.',
  'backup-pending-save-unverified':'Lava could not verify whether the encrypted import was saved. Keep the original file and check the pending import before retrying.',
  'backup-pending-discard-failed':'Lava could not discard the saved import. Try again.',
  'backup-export-unavailable':'Unlock Lava and finish setup before exporting a backup.',
  'backup-export-failed':'Lava could not save the encrypted file. Choose a writable location and try again.',
  'backup-export-too-large':'This configuration is too large for a portable backup. No file was saved.',
  'edition-unavailable':'This service is not available in the FOSS edition. All local features remain available.',
  'backup-setup-unavailable':'Backup setup is not available on this device yet. Your settings are unchanged.',
  'backup-setup-sign-in-required':'Sign in to your Lava account to set up backup.',
  'backup-setup-payload-unavailable':'Lava needs a confirmed filter before it can back up your settings. Finish setup and try again.',
  'backup-setup-payload-too-large':'Your settings are too large to back up. Turn off some filters and try again.',
  'backup-setup-consent-required':'Confirm both acknowledgments to continue.',
  'backup-setup-deletion-pending':'Backup deletion is pending. Sign in to the original account and try again.',
  'backup-setup-seal-failed':'Lava could not encrypt your backup. Try again.',
  'backup-setup-local-write-failed':'Lava could not save your backup on this device. Try again.',
  'backup-setup-local-rollback-failed':'Backup setup could not finish cleanly. Reopen Lava and check your backup settings before trying again.',
  'backup-setup-account-changed':'The account changed while backup was starting. Sign in and try again.',
  'backup-restore-unavailable':'Lava needs a confirmed filter before it can restore a backup. Nothing on this device was changed.',
  'backup-restore-review-unavailable':'This restore needs the native unlock screen, which is not available on this device yet. Nothing on this device was changed.',
  'backup-restore-sign-in-required':'Sign in to your Lava account to restore a backup.',
  'backup-restore-wrong-secret':'That unlock secret did not match your backup. Nothing on this device was changed.',
  'backup-restore-corrupt-payload':'This backup is damaged or incomplete. Nothing on this device was changed.',
  'backup-restore-unsupported-schema':'This backup was made by a newer version of Lava. Update the app and try again.',
  'backup-restore-not-restorable':'This backup carries no settings Lava can restore here. Nothing on this device was changed.',
  'backup-restore-passkey-unavailable':'Restoring with a passkey is not available on this device yet.',
  'backup-restore-resolver-confirmation-required':'This backup changes your DNS resolver. Review and confirm the change to restore.',
  'backup-restore-stale-review':'Lava changed while you were reviewing this restore. Start the restore again.',
  'backup-restore-rollback-failed':'The restore could not be applied cleanly and your settings may have changed. Reopen Lava and check your filters.',
  'backup-restore-no-review':'This restore review is no longer available. Start the restore again.',
  'backup-restore-publish-interrupted':'The restore was interrupted before it finished. Nothing was applied; try again.',
  'setting-refused':'Lava could not save the automatic backup setting. Your previous setting is still in effect.',
};
function backupReason(command:AppCommand,reason?:string):string|undefined{
  if(!reason)return undefined;
  const isBackupFlow=command.type==='backup.export'||command.type==='backup.import'||command.type==='backup.pending.resume'||command.type==='backup.pending.discard'||
    command.type==='native.flow'&&(command.flow==='backupSetup'||command.flow==='backupRestore');
  const isAutomatic=command.type==='settings.set'&&command.key==='backup.automatic';
  if(!isBackupFlow&&!isAutomatic)return undefined;
  return backupReasons[reason];
}
// Share/import reasons carry precise user copy; every other command keeps its generic handling.
// `unknown-filter` and `stale-review` are shared with ordinary filter commands, so the mapper is
// only consulted when the command can actually originate the share/import vocabulary.
const carriesShareOrImportReason=(command:AppCommand)=>
  command.type.startsWith('share.')||(command.type==='native.flow'&&IMPORT_FLOWS.has(command.flow));
const activityShareReasons:Record<string,string>={
  'activity-chart-unavailable':'Time-series Activity charts are not available to share on Android yet.',
  'activity-counts-unavailable':'Activity totals are unavailable while filtering counts are off.',
  'share-card-unavailable':'Lava could not share this Activity chart. Try again.',
  'share-card-not-ready':'The Activity chart is still being prepared. Try sharing again.',
  'stale-share':'Activity export cancelled because its selection or access changed.',
};
/**
 * The canonical security settings and credential flow reject with stable codes. Android's owner
 * has no biometric authenticator, so `biometrics` is a genuine `unavailable-per-platform` refusal;
 * a missing credential or an unreadable Keystore is named honestly and never rendered as success.
 */
const securityReasons:Record<string,string>={
  'unavailable-per-platform':'Biometrics are not available on this device yet.',
  'unsupported-setting':'This security setting is not available on Android yet.',
  'unknown-setting':'This security setting is no longer recognized.',
  'invalid-setting-value':'That value is not valid for this security setting.',
  'setting-refused':'Lava could not change that security setting. The previous setting is still in effect.',
  'unauthorized':'Unlock Lava to change security settings.',
  'unsupported-command':'This security flow is not connected on Android yet.',
};
/** Native VPN refusal identifiers map to human copy; secrets never cross this contract. */
const vpnReasons:Record<string,string>={
  'vpn-multi-row-unsupported':'Android supports one WireGuard configuration at a time.',
  'vpn-invalid-row':'That VPN edit is no longer open. Reopen the editor and try again.',
  'vpn-invalid-composition':'That WireGuard configuration is not valid.',
  'vpn-editor-busy':'Lava could not save the WireGuard configuration. Try again.',
  'vpn-stale-generation':'The VPN configuration changed elsewhere. Reopen the editor and try again.',
  'vpn-unknown-draft':'That VPN edit is no longer open. Reopen the editor and try again.',
  'vpn-not-configured':'Add a WireGuard configuration first.',
  'vpn-unsupported-toggle':'That VPN chaining option is not available on Android yet.',
  'vpn-row-toggle-unsupported':'Enabling or disabling a single configuration row is not available on Android yet.',
  'vpn-configuration-editor-unavailable':'Entering a WireGuard configuration is not available on Android yet.',
  'vpn-configuration-invalid':'That WireGuard configuration is not valid.',
  'vpn-import-cleanup-failed':'Lava could not undo the cancelled import. Reopen the editor and remove the WireGuard configuration.',
  'vpn-toggle-failed':'Lava could not change the VPN chaining setting. Your previous setting is still in effect.',
  'vpn-commit-refused':'Lava could not save the WireGuard configuration. Try again.',
  'vpn-commit-unavailable':'Lava could not read the saved WireGuard configuration. Try again.',
  'vpn-commit-failed':'Lava could not save the WireGuard configuration. Try again.',
};
function vpnReason(type:string,reason?:string):string|undefined{
  if(!reason||!type.startsWith('vpn.'))return undefined;
  return vpnReasons[reason];
}
function securityReason(command:AppCommand,reason?:string):string|undefined{
  if(!reason)return undefined;
  const isSecuritySetting=command.type==='settings.set'&&(command.key==='biometrics'||command.key.startsWith('protectedActions.'));
  const isPasscodeFlow=command.type==='native.flow'&&command.flow==='passcode';
  if(!isSecuritySetting&&!isPasscodeFlow)return undefined;
  return securityReasons[reason];
}
/**
 * Canonical `filter.refresh` refusals mapped to honest copy. The catalog owner never silently
 * degrades: an unreachable, invalid, stale-rollback, persist-failed or install-failed refresh
 * keeps the current filter and says so, and a build with no catalog owner is named as
 * unavailable. A caught `stale` would otherwise read as a client-side race, and every other
 * catalog code as the generic failure; codes are never rendered directly.
 */
const catalogReasons:Record<string,string>={
  'catalog-offline':"Lava couldn't reach the update service. Your current filter is unchanged.",
  'catalog-stale':'That update is older than the catalog already on this device. Your current filter is unchanged.',
  'catalog-invalid':"That catalog update wasn't valid. Your current filter is unchanged.",
  'catalog-persist-failed':"Lava couldn't save the catalog update. Your current filter is unchanged.",
  'catalog-install-failed':"Lava couldn't apply the catalog update. Your current filter is unchanged.",
  'catalog-unavailable':'Catalog updates are not available on this device yet.',
};
function catalogReason(command:AppCommand,reason?:string):string|undefined{
  if(!reason||command.type!=='filter.refresh')return undefined;
  return catalogReasons[reason];
}
/**
 * Honest copy for a canonical command whose Android owner is not connected yet. The shared
 * screens for activity/stats/network/domain-history/account still render, and a read or write
 * there resolves to this explicit unavailable state instead of a fabricated result or a native
 * handoff. The screens' queries surface it as their own error copy and their mutations alert it.
 */
const androidUnavailable='This part of Lava is not available on Android yet. Your saved settings are unchanged.';
/**
 * Honest copy for the headless Activity/Top Domains/Domain History/Network/Nerd Stats reads. Each
 * reason is an owner-confirmed refusal (locked, unavailable, out-of-window, or a surface with no
 * shared Android reviewer yet) and is never rendered as a fabricated successful read.
 */
const queryReasons:Record<string,string>={
  'domain-check-stale':'The filter changed. Check the domain again before changing its rules.',
  'domain-check-guardrail':'Some dangerous domains cannot be allowed.',
  'filters-locked':'Unlock Lava to view this filter.',
  'activity-locked':'Unlock Lava to view activity.',
  'activity-unavailable':'Activity data is not available yet.',
  'activity-range-unavailable':'That date range is not available.',
  'domains-unavailable':'Top domains are not available yet.',
  'domain-history-unavailable':'Domain history is not available yet.',
  'network-unavailable':'Network activity is not available yet.',
  'diagnostics-unavailable':'Diagnostics are not available yet. Try again in a moment.',
  'clear-failed':'Some logs could not be cleared. Try again.',
  'export-failed':'The log export could not be prepared. Try again.',
  'unknown-log':'That log category is not available.',
  'domains-review-unavailable':'Lava could not open this domain review right now. Try again.',
};
const customBlocklistReasons:Record<string,string>={
  'custom-blocklist-invalid-url':'That address is not a valid blocklist URL.',
  'custom-blocklist-unsupported-scheme':'Blocklist addresses must start with https://.',
  'custom-blocklist-missing-host':'That address is missing a host.',
  'custom-blocklist-private-network':'That address points to a private network.',
  'custom-blocklist-credentials':'That address contains a username or password.',
  'custom-blocklist-requires-plus':'Custom blocklist URLs are included with Lava plus.',
  'custom-blocklist-duplicate-url':'That custom URL is already added.',
  'custom-blocklist-duplicate-name':'A custom list with that name already exists.',
  'custom-blocklist-limit':'You have reached the custom blocklist limit for this filter.',
  'custom-entry-expired':'This editor is no longer open. Close it and open it again.',
};
function customBlocklistReason(command:AppCommand,reason?:string,sourceName?:string):string|undefined{
  if(!reason)return undefined;
  // A `filter.save`/`filter.apply` rejection can be one custom source's fetch failure; the native
  // owner names the list in `value` and the failure class in `reason`. The URL is never carried.
  if(command.type==='filter.save'||command.type==='filter.apply'){
    const template=customBlocklistFetchReasons[reason];
    return template?localizedFormat(template,sourceName||localized('your custom list')):undefined;
  }
  if(command.type!=='filter.customList'&&command.type!=='filter.addCustomList'&&command.type!=='filter.deleteCustomList')return undefined;
  return customBlocklistReasons[reason];
}
/**
 * Stable per-class copy for a custom blocklist that could not be fetched. `%@` is the user's
 * own list name; the code itself never carries the URL or any other source detail.
 */
const customBlocklistFetchReasons:Record<string,string>={
  'custom-blocklist-fetch-network':"Lava couldn't reach %@. Check your connection and try again.",
  'custom-blocklist-fetch-http':'%@ returned an error. Check the address and try again.',
  'custom-blocklist-fetch-empty':'%@ returned no content. Check the address and try again.',
  'custom-blocklist-fetch-too-large':'%@ is too large for Lava to use.',
  'custom-blocklist-fetch-timeout':'Downloading %@ timed out. Try again.',
  'custom-blocklist-fetch-insecure':'%@ must be an https:// address.',
  'custom-blocklist-fetch-redirect':'%@ redirected somewhere Lava will not follow. Use its final https:// address.',
  'custom-blocklist-fetch-storage':"Lava couldn't save %@ on this device. Try again.",
  'custom-blocklist-parse-failed':"Lava couldn't read %@. Check its format and try again.",
};
const allowed=new Set(['domainCheck.query','domainCheck.stage','domainCheck.cancel','activity.card.query','activity.share','accountBackup.command','guard.gesture','navigation.accept','navigation.presentation','navigation.presentationReady','foreground.dismiss','foreground.enter','foreground.dirty','foreground.identity','foreground.submit','feedback.change','feedback.topic','feedback.diagnostics','feedback.enter','feedback.edit','feedback.step','feedback.prepare','feedback.submit','feedback.copy','feedback.preview','system.open','customEntry.enter','customEntry.save','vpn.enter','vpnEditor.enter','vpnEditor.name','vpnEditor.file','vpnEditor.save','onboarding.enter','onboarding.back','onboarding.vpn','onboarding.notifications','onboarding.ready','onboarding.open','onboarding.release','onboarding.complete','onboarding.dismiss','onboarding.navigate','onboarding.choice','navigation.authorize','navigation.endTurn','protection.toggle','protection.pause','native.flow','purchase.refresh','purchase.buy','purchase.restore','purchase.manage','purchase.clearMessage','settings.set','dns.tiers','dns.toggle','customEntry.dismiss','sudoku.new','sudoku.save','haptic','backup.export','backup.import','backup.pending.resume','backup.pending.discard','backup.refresh','backup.now','backup.disable','backup.delete','filter.open','filter.close','filter.switch','filter.edit','filter.cancel','filter.save','filter.review','filter.apply','filter.domain','filter.removeList','filter.undoDomain','filter.undoList','filter.lists','filter.customList','filter.addCustomList','filter.deleteCustomList','filter.restoreDefaults','filter.refresh','filter.renameForm','library.edit','library.cancel','library.toggleDeletion','library.form','dns.customDraft','dns.customStage','dns.custom','vpn.begin','vpn.retire','vpn.cancel','vpn.reset','vpn.swap','vpn.commit','vpn.toggle','vpn.edit','vpn.remove','vpn.rowToggle','share.query','share.copy','share.card','share.present','activity.query','activity.visibility','catalog.query','domains.query','domains.enableHistory','domains.copy','domains.stage','domains.cancel','network.query','stats.query','activity.refresh','reports.sample','logs.clear','logs.export','account.apple','account.google','account.signOut','account.delete']);
function runtimeForCache(cache:WireCache) {return {
  getSnapshot:async()=>JSON.stringify(projectAndroidSnapshot(await readWire(cache))),
  onSnapshot:(listener:(value:string)=>void)=>DeviceEventEmitter.addListener('lavaAppSnapshot',(payload:string)=>{
    // One ReactContext can outlive an Activity or serve a QA root as well. A
    // foreign packet must not replace even the adapter's cached command source.
    if(cache.nativeHostID!==undefined&&(JSON.parse(payload) as {nativeHostID?:unknown}).nativeHostID!==cache.nativeHostID)return;
    listener(JSON.stringify(projectAndroidSnapshot(acceptWire(payload,cache))));
  }),
  command:async(payload:string)=>{
    const command=JSON.parse(payload) as AppCommand;
    if(command.type==='domainCheck.cancel'){
      if(typeof command.visit!=='string'||! /^[0-9a-f-]{36}$/i.test(command.visit))throw new Error('Invalid domain check visit.');
      const reply=JSON.parse(await native.command(JSON.stringify({schema:1,type:command.type,visit:command.visit,nativeHostID:cache.nativeHostID})));
      if(reply.status!=='completed')throw new Error('The domain check could not be closed.');
      return JSON.stringify({result:null});
    }
    if(command.type==='accountBackup.command'&&(command.action==='close'||command.action==='back')){
      if(typeof command.id!=='string'||!command.id)throw new Error('Invalid account/backup retirement.');
      const reply=JSON.parse(await native.command(JSON.stringify({schema:1,type:command.type,id:command.id,action:command.action})));
      if(reply.status!=='completed')throw new Error('The account/backup flow could not be closed.');
      return JSON.stringify({result:null});
    }
    if(command.type==='activity.share.retire'){
      if(typeof command.token!=='string'||!command.token||command.token.length>256)throw new Error('Invalid share retirement.');
      const reply=JSON.parse(await native.command(JSON.stringify({schema:1,type:command.type,token:command.token})));
      if(reply.status!=='completed')throw new Error('The share could not be closed.');
      return JSON.stringify({result:null});
    }
    if(command.type==='preparation.cancel'){
      if(typeof command.id!=='string'||!command.id)throw new Error('Invalid preparation retirement.');
      const reply=JSON.parse(await native.command(JSON.stringify({schema:1,type:command.type,id:command.id,
        ...(cache.nativeHostID?{nativeHostID:cache.nativeHostID}:{})})));
      if(reply.status!=='completed')throw new Error('The preparation could not be closed.');
      return JSON.stringify({result:null});
    }
    if(command.type==='presentation.retry') {
      if(!cache.nativeHostID)throw new Error('The app screen closed before this action started.');
      const reply=JSON.parse(await native.command(JSON.stringify({schema:1,type:command.type,nativeHostID:cache.nativeHostID})));
      if(reply.reason==='stale-host')throw new AppCommandDetachedError('afterStart');
      if(reply.status!=='completed')throw new Error('Lava could not access its security settings. Try again.');
      return JSON.stringify({result:null});
    }
    // A cold error frame cannot read/project the failed private graph to acknowledge its paint.
    if(command.type==='navigation.retryPresentation'||command.type==='navigation.retryPresentationReady') {
      if(cache.nativeHostID===undefined||!Number.isSafeInteger(command.serial)||command.serial<1)throw new Error('Invalid public retry frame.');
      const ready=command.type==='navigation.retryPresentationReady';
      if(ready&&(typeof command.generation!=='string'||!command.generation))throw new Error('Invalid public retry frame.');
      let reply:{status:string;reason?:string;value?:unknown};
      try {reply=JSON.parse(await native.command(JSON.stringify({schema:1,type:command.type,serial:command.serial,
        ...(ready?{generation:command.generation}:{}),nativeHostID:cache.nativeHostID})));}
      catch(error){if(staleHostError(error))throw new AppCommandDetachedError('afterStart');throw error;}
      if(reply.reason==='stale-host')throw new AppCommandDetachedError('afterStart');
      if(reply.status!=='completed')throw new Error('The public retry frame is no longer current.');
      if(ready){if(reply.value!==true)throw new Error('Invalid public retry receipt.');}
      else {const value=reply.value as {generation?:unknown;serial?:unknown}|undefined;
        if(!value||typeof value.generation!=='string'||!value.generation||value.serial!==command.serial)throw new Error('Invalid public retry receipt.');}
      return JSON.stringify({result:reply.value});
    }
    // A retired route relinquishes its exact native capability without reading a
    // newer graph, admitting a mutation, or publishing a private successor frame.
    if(command.type==='vpn.retire') {
      const keys=['type','id','retirementGeneration','nativeHostID','retirementLease'];
      if(cache.nativeHostID===undefined||command.nativeHostID!==cache.nativeHostID||
        typeof command.id!=='string'||!command.id||typeof command.retirementGeneration!=='string'||
        !/^(0|[1-9][0-9]*)$/.test(command.retirementGeneration)||
        command.retirementGeneration.length>19||command.retirementGeneration.length===19&&command.retirementGeneration>'9223372036854775807'||
        typeof command.retirementLease!=='string'||! /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(command.retirementLease)||
        Object.keys(command).some(key=>!keys.includes(key)))throw new Error('Invalid VPN retirement receipt.');
      let reply:{status?:unknown;reason?:unknown;value?:unknown;snapshot?:unknown;projectionFailure?:unknown};
      try {reply=JSON.parse(await native.command(JSON.stringify({schema:1,type:'vpn.retire',id:command.id,
        retirementGeneration:command.retirementGeneration,nativeHostID:command.nativeHostID,retirementLease:command.retirementLease})));}
      catch(error){if(staleHostError(error))throw new AppCommandDetachedError('afterStart');throw error;}
      if(!reply||typeof reply!=='object'||reply.snapshot!==undefined||reply.projectionFailure!==undefined||
        reply.value!==undefined&&reply.value!==null)throw new Error('Invalid VPN retirement receipt.');
      if(reply.status==='rejected'&&reply.reason==='stale-host')throw new AppCommandDetachedError('afterStart');
      if(reply.status!=='completed')throw new Error('The VPN route retirement is no longer current.');
      if(reply.reason!==undefined&&reply.reason!==null)throw new Error('Invalid VPN retirement receipt.');
      return JSON.stringify({result:null});
    }
    if(command.type==='refresh')return JSON.stringify({snapshot:projectAndroidSnapshot(await readWire(cache)),result:null});
    if(!allowed.has(command.type)&&!['preparation.enter','preparation.rendered','preparation.presented',
      'preparation.announce','preparation.retry','preparation.editReturn','preparation.keepCurrent'].includes(command.type))throw unsupported();
    const source=cache.latest??await readWire(cache);
    // Publication is separate from the native operation's durable outcome.
    let projectionUnavailable=false;
    // Every native reply goes through the same cancellation/rejection mapping.
    const send=async(request:Record<string,unknown>)=>{
      let reply:{status:string;reason?:string;value?:unknown;snapshot?:unknown;projectionFailure?:unknown};
      try {reply=JSON.parse(await native.command(JSON.stringify({...request,...(cache.nativeHostID!==undefined?{nativeHostID:cache.nativeHostID}:{})})));}
      catch(error){if(cache.nativeHostID!==undefined&&staleHostError(error))throw new AppCommandDetachedError('afterStart');throw error;}
      if(reply.projectionFailure!==undefined){
        if(!['unavailable','stale-host'].includes(reply.projectionFailure as string)||reply.snapshot!==undefined)throw new Error('Invalid command publication receipt.');
        if(reply.projectionFailure==='stale-host')throw new AppCommandDetachedError('afterStart');
        projectionUnavailable=true;
      }
      if(cache.nativeHostID!==undefined&&reply.reason==='stale-host')throw new AppCommandDetachedError('afterStart');
      if(reply.status==='cancelled') {
        if(command.type==='library.form'||command.type==='filter.renameForm'||command.type==='native.flow'&&['feedback','onboarding','onboardingPreview'].includes(command.flow))return {...reply,value:false};
        throw new Error('Authentication cancelled.');
      }
      if(reply.status!=='completed'){
        const dns=dnsReason(command.type,reply.reason);
        if(dns)throw new Error(dns);
        const security=securityReason(command,reply.reason);
        if(security)throw new Error(security);
        const vpnCopy=vpnReason(command.type,reply.reason);
        if(vpnCopy)throw new Error(vpnCopy);
        const accountCopy=accountReason(command.type,reply.reason);
        if(accountCopy)throw new Error(accountCopy);
        const backupCopy=backupReason(command,reply.reason);
        if(backupCopy)throw new Error(backupCopy);
        if(command.type==='activity.share'||command.type==='activity.card.query'){
          const copy=activityShareReasons[reply.reason??''];
          if(copy)throw Object.assign(new Error(copy),{code:reply.reason});
        }
        const ownerCopy=carriesShareOrImportReason(command)?ownerReasonCopy(reply.reason??''):null;
        if(ownerCopy)throw Object.assign(new Error(ownerCopy),{code:reply.reason});
        const queryCopy=queryReasons[reply.reason??''];
        if(queryCopy)throw new Error(queryCopy);
        const catalogCopy=catalogReason(command,reply.reason);
        if(catalogCopy)throw new Error(catalogCopy);
        const customCopy=customBlocklistReason(command,reply.reason,typeof reply.value==='string'?reply.value:undefined);
        if(customCopy)throw new Error(customCopy);
        // A combined identity edit can fail name, emoji, frozen-target or durable admission.
        // Its Boolean owner result does not identify which guard refused the write.
        if(command.type==='filter.renameForm'&&reply.reason==='rename-refused')throw new Error('Couldn’t save. Please try again.');
        if(reply.reason==='library-invalid-emoji')throw new Error('Choose one emoji.');
        // The canonical command is recognized but Android has no owner for it yet. Unsupported
        // outcomes never read as success and never as an unrelated filter failure.
        if(reply.reason==='unknown-command'||reply.reason==='unsupported-command'||reply.reason==='invalid-flow')throw new Error(androidUnavailable);
        throw new Error(reply.reason?.includes('stale')?'Lava changed while this screen was open. Try again.':reply.reason?.includes('unsupported')?unsupported().message:reply.reason==='library-invalid-name'?'Choose an unused filter name.':'This action could not be completed. Try again.');
      }
      return reply;
    };
    const generation={schema:1,generation:source.generation??''};
    // `vpn.begin`/`vpn.rowToggle` carry their own canonical `generation`, which is the committed
    // ROTATION generation the native owner fences against — it must NOT be overwritten by the app
    // graph generation. The graph generation still rides as `productGeneration` for the bridge's
    // credential fence; every other command keeps the graph generation under `generation`.
    let request:Record<string,unknown>=command.type==='vpn.begin'||command.type==='vpn.rowToggle'
      ?{schema:1,...command,productGeneration:generation.generation}
      :{...command,...generation};
    if(command.type==='navigation.presentation'||command.type==='navigation.presentationReady') {
      // This is the invoking frame's witness, never a later cached/native read.
      if(command.readRevision!==undefined) {
        if(!Number.isSafeInteger(command.readRevision)||command.readRevision<0)throw new Error('Invalid presentation authority revision.');
        request.readRevision=String(command.readRevision);
      }
    }
    if(command.type==='protection.toggle')request=source.onboardingRequired||source.onboardingRecoveryRequired===true?{...request,type:'native.flow',flow:'onboarding'}:{...request,action:source.protection.action};
    const reply=await send(request).catch(error=>{
      // Preserve the original rejection/cancellation; the store separately
      // revokes obsolete read authority instead of calling this a failed write.
      if(projectionUnavailable&&error instanceof Error)Object.assign(error,{projectionFailure:'unavailable'});
      throw error;
    });
    if(projectionUnavailable){
      if(command.type.endsWith('.query'))throw Object.assign(new Error('Read access changed.'),{projectionFailure:'unavailable'});
      return JSON.stringify({result:reply.value??null,projectionFailure:'unavailable'});
    }
    // Native published this exact host-fenced projection before resolving the command.
    // Keep cache ordering, but return the actual receipt rather than borrowing a newer event.
    const published=reply.snapshot!==undefined?acceptWire(JSON.stringify(reply.snapshot),cache,true):await readWire(cache);
    return JSON.stringify({snapshot:projectAndroidSnapshot(published),result:reply.value??null});
  },
};}

export const androidRuntime=runtimeForCache(unboundCache);
/** Bind one immutable native Activity nonce to one isolated React root. */
export function androidRuntimeForHost(nativeHostID:string) {
  if(!nativeHostID)throw new AppCommandDetachedError('beforeStart');
  return runtimeForCache({nativeHostID});
}
