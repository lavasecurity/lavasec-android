import {NativeModules,Alert} from 'react-native';
import {configurePresentation,localized} from '../canonical/app/presentation';
import {translations} from '../canonical/app/translations';
import {productFixture} from './productFixture';
import type {ProductSnapshot} from '../src/productContract';
import {parseProductSnapshot} from '../src/productContract';
import {androidReviewRouteAllowed} from '../src/systemCapabilities';
const port={getSnapshot:jest.fn(),command:jest.fn()};
NativeModules.NativeLavaApp=port;
const {androidRuntime,projectAndroidSnapshot,acceptAndroidWire}=require('../canonical/android/runtime') as typeof import('../canonical/android/runtime');
let revision=100;
afterEach(()=>jest.restoreAllMocks());
beforeEach(()=>{
  ++revision;
  port.getSnapshot.mockResolvedValue(JSON.stringify({...productFixture,revision}));
  port.command.mockReset().mockResolvedValue(JSON.stringify({status:'completed'}));
});
const preparation:NonNullable<ProductSnapshot['preparation']>={
  id:'native-attempt',filterId:'filter1',origin:'filters',state:'preparing',progress:.25,
  title:'Building the filter',message:'',busy:false,recovery:{retry:false,editReturn:false,keepCurrent:false,upgrade:null},
};
const accountBackupFlow:NonNullable<ProductSnapshot['foregroundFlow']>={
  id:'backup-visit',kind:'accountBackup',name:'',emoji:'',dismissAttempt:0,canCreate:false,templates:[],
  accountBackup:{kind:'backupSetup',title:'Set up backup',busy:false,canBack:false,canClose:true,success:false,
    connections:[],deleting:false,step:'upload',error:'',copied:false,canContinue:true,supportsPasskey:false,
    selectedPasskey:false,saved:true,understands:true,uploading:false,uploadError:'Upload failed',
    mode:'recoveryCode',modes:[],status:null,review:null,resolverConfirmationRequired:false,resolverConfirmed:false},
};
test('account backup wire admits native upload truth and rejects credentials or covered fields',()=>{
  const wire={...productFixture,foregroundFlow:accountBackupFlow};
  expect(parseProductSnapshot(JSON.stringify(wire)).foregroundFlow?.accountBackup?.success).toBe(false);
  for(const patch of [{foreground:false},{presentationBlocked:true},{available:false}]){
    expect(projectAndroidSnapshot({...wire,...patch}).foregroundFlow).toBeUndefined();
    expect(()=>parseProductSnapshot(JSON.stringify({...wire,...patch}))).toThrow(TypeError);
  }
  for(const key of ['recoveryPhrase','credential','email','secret']){
    expect(()=>parseProductSnapshot(JSON.stringify({...wire,foregroundFlow:{...accountBackupFlow,
      accountBackup:{...accountBackupFlow.accountBackup,[key]:'must remain native'}}}))).toThrow(TypeError);
  }
});
test('backup close has no snapshot read and upload retry reaches native while the launch awaits',async()=>{
  port.getSnapshot.mockClear();
  await androidRuntime.command(JSON.stringify({type:'accountBackup.command',id:'old-visit',action:'close'}));
  expect(port.getSnapshot).not.toHaveBeenCalled();
  expect(JSON.parse(port.command.mock.calls.at(-1)![0])).toEqual({schema:1,type:'accountBackup.command',id:'old-visit',action:'close'});
  await androidRuntime.getSnapshot();
  let finish!:(value:string)=>void;
  const pending=new Promise<string>(resolve=>{finish=resolve;});
  port.command.mockImplementation(raw=>JSON.parse(raw).type==='native.flow'?pending:Promise.resolve(JSON.stringify({status:'completed'})));
  const launch=androidRuntime.command(JSON.stringify({type:'native.flow',flow:'backupSetup'}));
  await androidRuntime.command(JSON.stringify({type:'accountBackup.command',id:'backup-visit',action:'setup.retry'}));
  expect(JSON.parse(port.command.mock.calls.at(-1)![0])).toMatchObject({schema:1,type:'accountBackup.command',id:'backup-visit',action:'setup.retry'});
  finish(JSON.stringify({status:'completed'}));await launch;
});

test('restore review cannot disclose filters through an app-settings-only grant',()=>{
  const wire:ProductSnapshot={...productFixture,foregroundFlow:{...accountBackupFlow,
    accountBackup:{...accountBackupFlow.accountBackup!,kind:'backupRestore' as const}},
    security:{...productFixture.security!,protectedSurfaces:['filterEditing'],authenticatedSurfaces:[]}};
  expect(projectAndroidSnapshot(wire).foregroundFlow).toBeUndefined();
  expect(()=>parseProductSnapshot(JSON.stringify(wire))).toThrow('Locked account/backup projection');
});
test('preparation projection preserves native quarters and redacts private fields under both privacy gates',()=>{
  const wire={...productFixture,filterPreparationPresented:true,preparation};
  expect(projectAndroidSnapshot(wire).preparation).toMatchObject({id:'native-attempt',progress:.25});
  for(const covered of [{...wire,foreground:false},{...wire,presentationBlocked:true},
    {...wire,filters:{...wire.filters,locked:true}}]){
    expect(projectAndroidSnapshot(covered).preparation).toBeUndefined();
    expect(projectAndroidSnapshot(covered).filterPreparationPresented).toBe(true);
    expect(()=>parseProductSnapshot(JSON.stringify(covered))).toThrow(TypeError);
  }
});
test('preparation cancel does not read a snapshot and controls reach native while apply is pending',async()=>{
  port.getSnapshot.mockClear();
  await androidRuntime.command(JSON.stringify({type:'preparation.cancel',id:'retired-attempt'}));
  expect(port.getSnapshot).not.toHaveBeenCalled();
  expect(JSON.parse(port.command.mock.calls.at(-1)![0])).toMatchObject({type:'preparation.cancel',id:'retired-attempt'});
  await androidRuntime.getSnapshot();
  let finish!:(value:string)=>void;
  const pending=new Promise<string>(resolve=>{finish=resolve;});
  port.command.mockImplementation(raw=>JSON.parse(raw).type==='filter.apply'?pending:Promise.resolve(JSON.stringify({status:'completed'})));
  const applying=androidRuntime.command(JSON.stringify({type:'filter.apply',id:'filter1',review:'native-review'}));
  for(const type of ['preparation.rendered','preparation.presented','preparation.announce','preparation.retry','preparation.editReturn','preparation.keepCurrent']){
    await androidRuntime.command(JSON.stringify({type,id:'native-attempt'}));
    expect(JSON.parse(port.command.mock.calls.at(-1)![0])).toMatchObject({type,id:'native-attempt'});
  }
  finish(JSON.stringify({status:'completed'}));await applying;
});
test('preparation wire rejects invented success and arbitrary timer progress',()=>{
  for(const patch of [{state:'success'},{progress:1},{progress:.33},{origin:'unknown'}]){
    expect(()=>parseProductSnapshot(JSON.stringify({...productFixture,filterPreparationPresented:true,
      preparation:{...preparation,...patch}}))).toThrow(TypeError);
  }
  expect(parseProductSnapshot(JSON.stringify({...productFixture,filterPreparationPresented:true,
    preparation:{...preparation,state:'success',progress:1}})).preparation?.state).toBe('success');
});
test('projects confirmed filter identity without inventing daily counts or entitlement',()=>{
  const snapshot=projectAndroidSnapshot(productFixture);
  expect(snapshot.filters[0]).toMatchObject({id:'filter1',name:'My filter',count:'84'});
  expect(snapshot.session.activeFilterID).toBe('filter1');
  expect(snapshot.protection.today).toBeUndefined();
  expect(snapshot.plus.enabled).toBe(false);
  expect(snapshot.plus.offers).toEqual([]);
  expect(snapshot.security.unavailable).toBe(true);
});
test('Guard shows chained establishment only for an admitted native in-progress runtime',()=>{
  const packet=(lifecycle='protected',extra:Partial<ProductSnapshot>={})=>({...productFixture,...extra,
    protection:{...productFixture.protection,lifecycle,chainedEstablishing:true}} as ProductSnapshot);
  for(const phase of ['starting','reconnecting','protected'])expect(projectAndroidSnapshot(packet(phase)).protection.subtitle).toBe('Setting up VPN chaining.');
  for(const phase of ['inactive','paused','awaiting_network','stopping','attention'])expect(projectAndroidSnapshot(packet(phase)).protection.subtitle).not.toBe('Setting up VPN chaining.');
  for(const extra of [{foreground:false},{available:false}])expect(projectAndroidSnapshot(packet('protected',extra)).protection.subtitle).not.toBe('Setting up VPN chaining.');
  const error=packet();error.protection.error='Current native error';expect(projectAndroidSnapshot(error).protection.subtitle).toBe('Current native error');
  expect(projectAndroidSnapshot({...packet(),protection:{...packet().protection,chainedEstablishing:undefined}}).protection.subtitle).not.toBe('Setting up VPN chaining.');
});
test.each([
  ['inactive','unavailable','rest','affirmative'],
  ['starting','unavailable','unresolved','quiet'],
  ['stopping','healthy','stopping','quiet'],
  ['paused','healthy','paused','affirmative'],
  ['reconnecting','healthy','recovery','recovery'],
  ['awaiting_network','network_unavailable','recovery','recovery'],
  ['attention','healthy','recovery','recovery'],
  ['unavailable','healthy','unknown','recovery'],
  ['future_state','healthy','unknown','recovery'],
  ['protected','healthy','affirmed','quiet'],
  ['protected','encrypted_fallback','recovery','recovery'],
  ['protected','plain_dns_fallback','recovery','recovery'],
  ['protected','device_dns_fallback','recovery','recovery'],
  ['protected','recovering','recovery','recovery'],
  ['protected','dns_slow','recovery','recovery'],
  ['protected','network_unavailable','recovery','recovery'],
  ['protected','needs_reconnect','recovery','recovery'],
  ['protected','unavailable','unknown','quiet'],
  ['protected','future_health','unknown','quiet'],
])('Guard material follows confirmed %s / %s health', (lifecycle,connectivity,materialIntent,actionTone)=>{
  const wire={...productFixture,protection:{...productFixture.protection,lifecycle,connectivity,error:null,action:'turnOff' as const}};
  expect(projectAndroidSnapshot(wire).protection).toMatchObject({materialIntent,actionTone});
});
test('Guard never affirms stale, errored, establishing, or setup-recovery state',()=>{
  const healthy={...productFixture,protection:{...productFixture.protection,lifecycle:'protected',connectivity:'healthy',error:null,action:'turnOff' as const}};
  for(const patch of [{available:false},{foreground:false},{onboardingRecoveryRequired:true},{onboardingReconstructionInFlight:true}])
    expect(projectAndroidSnapshot({...healthy,...patch}).protection).toMatchObject({materialIntent:'unknown',actionTone:'recovery'});
  for(const lifecycle of ['inactive','starting','protected','paused','stopping'])
    expect(projectAndroidSnapshot({...healthy,protection:{...healthy.protection,lifecycle,error:'Native failure'}}).protection).toMatchObject({materialIntent:'unknown',actionTone:'recovery'});
  expect(projectAndroidSnapshot({...healthy,protection:{...healthy.protection,chainedEstablishing:true}}).protection).toMatchObject({materialIntent:'unresolved',actionTone:'quiet'});
  const setup={...healthy,available:false,onboardingAvailable:true,onboardingRequired:true};
  expect(projectAndroidSnapshot(setup).protection).toMatchObject({materialIntent:'rest',actionTone:'affirmative'});
  for(const patch of [{foreground:false},{onboardingAvailable:false},{onboardingRecoveryRequired:true},{onboardingReconstructionInFlight:true}])
    expect(projectAndroidSnapshot({...setup,...patch}).protection).toMatchObject({materialIntent:'unknown',actionTone:'recovery'});
  expect(projectAndroidSnapshot({...healthy,protection:{...healthy.protection,action:'reconnect'}}).protection).toMatchObject({materialIntent:'affirmed',actionTone:'recovery'});
});
test('forwards the current native action and generation for protection',async()=>{
  await androidRuntime.getSnapshot();
  await androidRuntime.command(JSON.stringify({type:'protection.toggle'}));
  expect(JSON.parse(port.command.mock.calls[0][0])).toMatchObject({schema:1,type:'protection.toggle',generation:'7',action:'turnOn'});
});
test('routes first-run protection through the existing native onboarding owner',async()=>{
  port.getSnapshot.mockResolvedValue(JSON.stringify({...productFixture,revision,onboardingRequired:true}));
  await androidRuntime.getSnapshot();
  await androidRuntime.command(JSON.stringify({type:'protection.toggle'}));
  expect(JSON.parse(port.command.mock.calls[0][0])).toMatchObject({type:'native.flow',flow:'onboarding'});
});
test('does not report unsupported writes as successful',async()=>{
  await expect(androidRuntime.command(JSON.stringify({type:'protection.resume'})))
    .rejects.toThrow('not connected');
  expect(port.command).not.toHaveBeenCalled();
});
test('forwards custom blocklist open, add and delete to the native owner',async()=>{
  await androidRuntime.getSnapshot();
  port.command.mockResolvedValueOnce(JSON.stringify({status:'completed',value:'token-1'}));
  const opened=JSON.parse(await androidRuntime.command(JSON.stringify({type:'filter.customList',id:'filter1',ids:['a']})));
  expect(opened.result).toBe('token-1');
  expect(JSON.parse(port.command.mock.calls.at(-1)![0])).toMatchObject({type:'filter.customList',id:'filter1',ids:['a'],generation:'7',schema:1});

  port.command.mockResolvedValueOnce(JSON.stringify({status:'completed',value:'custom-2'}));
  await androidRuntime.command(JSON.stringify({type:'filter.addCustomList',token:'token-1',name:'List',url:'https://example.com/list.txt',format:'auto'}));
  expect(JSON.parse(port.command.mock.calls.at(-1)![0])).toMatchObject({type:'filter.addCustomList',token:'token-1',name:'List',url:'https://example.com/list.txt',format:'auto'});

  await androidRuntime.command(JSON.stringify({type:'filter.deleteCustomList',id:'filter1',sourceID:'custom-1'}));
  expect(JSON.parse(port.command.mock.calls.at(-1)![0])).toMatchObject({type:'filter.deleteCustomList',id:'filter1',sourceID:'custom-1'});
});
test('maps a custom blocklist refusal to honest copy',async()=>{
  await androidRuntime.getSnapshot();
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'custom-blocklist-duplicate-url'}));
  await expect(androidRuntime.command(JSON.stringify({type:'filter.addCustomList',token:'token-1',name:'List',url:'https://example.com/list.txt'})))
    .rejects.toThrow('already added');
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'custom-blocklist-requires-plus'}));
  await expect(androidRuntime.command(JSON.stringify({type:'filter.addCustomList',token:'token-1',name:'List',url:'https://example.com/list.txt'})))
    .rejects.toThrow('Lava plus');
});
test('maps a per-source custom blocklist fetch failure to named copy',async()=>{
  await androidRuntime.getSnapshot();
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'custom-blocklist-fetch-too-large',value:'My big list'}));
  await expect(androidRuntime.command(JSON.stringify({type:'filter.apply',id:'filter1',review:'token'})))
    .rejects.toThrow('My big list is too large for Lava to use.');
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'custom-blocklist-fetch-timeout',value:'My list'}));
  await expect(androidRuntime.command(JSON.stringify({type:'filter.save',id:'filter1'})))
    .rejects.toThrow('Downloading My list timed out. Try again.');
});
test('forwards Guard gestures to the native haptic owner with the current generation',async()=>{
  await androidRuntime.getSnapshot();
  for(const gesture of ['start','end','tap','reveal'] as const){
    const reply=JSON.parse(await androidRuntime.command(JSON.stringify({type:'guard.gesture',gesture})));
    expect(reply.result).toBeNull();
    expect(JSON.parse(port.command.mock.calls.at(-1)![0])).toEqual({type:'guard.gesture',gesture,schema:1,generation:'7'});
  }
});
test('forwards the review-moment dwell token to its Android owner',async()=>{
  await androidRuntime.getSnapshot();
  port.command.mockResolvedValueOnce(JSON.stringify({status:'completed'}));
  const reply=JSON.parse(await androidRuntime.command(JSON.stringify({type:'activity.visibility',token:'activity-1',visible:true,start:100,end:200})));
  expect(reply.result).toBeNull();
  expect(JSON.parse(port.command.mock.calls.at(-1)![0])).toMatchObject({type:'activity.visibility',token:'activity-1',visible:true,start:100,end:200,generation:'7',schema:1});
});
test('forwards the canonical activity refresh to the Android activity owner',async()=>{
  await androidRuntime.getSnapshot();
  port.command.mockResolvedValueOnce(JSON.stringify({status:'completed'}));
  const reply=JSON.parse(await androidRuntime.command(JSON.stringify({type:'activity.refresh'})));
  expect(reply.result).toBeNull();
  // The native bridge implements `activity.refresh`; the canonical adapter must admit it rather
  // than throwing the generic unsupported-command copy for a command the contract declares.
  expect(JSON.parse(port.command.mock.calls.at(-1)![0])).toMatchObject({type:'activity.refresh',generation:'7',schema:1});
});
test('forwards catalog.query to the blocklist picker owner and returns its real sections',async()=>{
  await androidRuntime.getSnapshot();
  const response={sections:[{title:'Security & Threat Intel',sources:[{id:'blocklistproject-basic',name:'Block List Basic',licenseName:'Unlicense',sourceURL:'https://example.test/basic.txt',metadata:'120 rules'}]}],count:0,budget:500000,pending:0,exceeded:false,summary:'1 of 1 lists selected',fraction:0,indeterminate:true,atOrOverBudget:false};
  port.command.mockResolvedValueOnce(JSON.stringify({status:'completed',value:response}));
  const reply=JSON.parse(await androidRuntime.command(JSON.stringify({type:'catalog.query',ids:['blocklistproject-basic']})));
  expect(reply.result).toEqual(response);
  expect(JSON.parse(port.command.mock.calls.at(-1)![0])).toMatchObject({type:'catalog.query',ids:['blocklistproject-basic'],generation:'7',schema:1});
});
test('projects the canonical connection family and omits it without a resolver selection',()=>{
  const connection={filter:{id:'filter1',name:'My filter',count:'84'},dns:{usesWireGuard:false,editable:true,primary:{name:'Device DNS',detail:'',transport:''},fallback:{name:'Mullvad',detail:'https://dns.mullvad.net/dns-query',transport:'DoH'}},vpn:{eligible:false,enabled:false,fallbackEnabled:false,configured:false,statusLabel:'Not configured'}};
  expect(projectAndroidSnapshot({...productFixture,connection}).connection).toEqual(connection);
  // No owner-projected family is omitted, never fabricated from the filters/session fallback.
  expect(projectAndroidSnapshot(productFixture).connection).toBeUndefined();
});
test('surfaces an explicit Android-unavailable state for canonical owners that are not connected',async()=>{
  await androidRuntime.getSnapshot();
  for(const command of [
    {type:'stats.query'},{type:'network.query'},{type:'activity.query',start:0,end:1,hourly:true},
    {type:'domains.query',history:false,decision:'All',search:''},{type:'domains.enableHistory'},
    {type:'domains.stage',domain:'example.com',decision:'blocked'},
  ]){
    port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'unknown-command'}));
    await expect(androidRuntime.command(JSON.stringify(command))).rejects.toThrow('not available on Android yet');
  }
  // A native flow whose Android destination does not exist is the same explicit unavailable.
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'invalid-flow'}));
  await expect(androidRuntime.command(JSON.stringify({type:'native.flow',flow:'automation'}))).rejects.toThrow('not available on Android yet');
});
test('routes backup setup and restore to the native bridge without leaking recovery material',async()=>{
  await androidRuntime.getSnapshot();
  port.command.mockResolvedValue(JSON.stringify({status:'completed'}));
  for(const flow of ['backupSetup','backupRestore'] as const){
    await androidRuntime.command(JSON.stringify({type:'native.flow',flow}));
    const request=JSON.parse(port.command.mock.calls.at(-1)![0]);
    expect(request).toMatchObject({type:'native.flow',flow,generation:'7',schema:1});
    // No recovery material or restore-review handle is ever sent for a backup flow.
    for(const key of ['secret','phrase','recoveryPhrase','password','passkey','deviceSecret','envelope','review','reviewID']){
      expect(request).not.toHaveProperty(key);
    }
  }
});
test('a completed setup reports its remote/local state truthfully',async()=>{
  await androidRuntime.getSnapshot();
  port.command.mockResolvedValueOnce(JSON.stringify({status:'completed',value:'uploaded'}));
  const uploaded=JSON.parse(await androidRuntime.command(JSON.stringify({type:'native.flow',flow:'backupSetup'})));
  expect(uploaded.result).toBe('uploaded');
  // A locally-committed setup awaiting a network/account is not a failure and not a remote copy.
  port.command.mockResolvedValueOnce(JSON.stringify({status:'completed',value:'local'}));
  const local=JSON.parse(await androidRuntime.command(JSON.stringify({type:'native.flow',flow:'backupSetup'})));
  expect(local.result).toBe('local');
});
test('setup refusals name their exact cause instead of a blanket unavailable',async()=>{
  await androidRuntime.getSnapshot();
  const reasons:Record<string,string>={
    'backup-setup-sign-in-required':'Sign in to your Lava account to set up backup.',
    'backup-setup-payload-unavailable':'Lava needs a confirmed filter before it can back up your settings. Finish setup and try again.',
    'backup-setup-payload-too-large':'Your settings are too large to back up. Turn off some filters and try again.',
    'backup-setup-consent-required':'Confirm both acknowledgments to continue.',
    'backup-setup-seal-failed':'Lava could not encrypt your backup. Try again.',
    'backup-setup-local-write-failed':'Lava could not save your backup on this device. Try again.',
    'backup-setup-local-rollback-failed':'Backup setup could not finish cleanly. Reopen Lava and check your backup settings before trying again.',
    'backup-setup-account-changed':'The account changed while backup was starting. Sign in and try again.',
  };
  for(const [reason,message] of Object.entries(reasons)){
    port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason}));
    await expect(androidRuntime.command(JSON.stringify({type:'native.flow',flow:'backupSetup'}))).rejects.toThrow(message);
  }
});
test('projects the backup remote status as localized copy, never a raw wire code',()=>{
  expect(projectAndroidSnapshot(productFixture).backup.remoteStatus).toBe('No backup');
  expect(projectAndroidSnapshot({...productFixture,backup:{...productFixture.backup,remoteStatus:'unavailable'}}).backup.remoteStatus).toBe('Backup status unavailable');
  expect(projectAndroidSnapshot({...productFixture,backup:{...productFixture.backup,remoteStatus:'available'}}).backup.remoteStatus).toBe('Backup available');
  expect(projectAndroidSnapshot({...productFixture,backup:{...productFixture.backup,remoteStatus:'checking'}}).backup.remoteStatus).toBe('Checking for a backup…');
});
test('a newer generation invalidates a restore request instead of acting on a stale review',async()=>{
  await androidRuntime.getSnapshot();
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'stale-generation'}));
  await expect(androidRuntime.command(JSON.stringify({type:'native.flow',flow:'backupRestore'})))
    .rejects.toThrow('Lava changed while this screen was open');
  expect(JSON.parse(port.command.mock.calls.at(-1)![0])).toMatchObject({type:'native.flow',flow:'backupRestore',generation:'7'});
});
test('restore refusals keep their precise cause instead of a blanket unavailable',async()=>{
  await androidRuntime.getSnapshot();
  const reasons:Record<string,string>={
    'backup-restore-sign-in-required':'Sign in to your Lava account to restore a backup.',
    'backup-restore-wrong-secret':'That unlock secret did not match your backup. Nothing on this device was changed.',
    'backup-restore-corrupt-payload':'This backup is damaged or incomplete. Nothing on this device was changed.',
    'backup-restore-unsupported-schema':'This backup was made by a newer version of Lava. Update the app and try again.',
    'backup-restore-resolver-confirmation-required':'This backup changes your DNS resolver. Review and confirm the change to restore.',
    'backup-restore-stale-review':'Lava changed while you were reviewing this restore. Start the restore again.',
    'backup-restore-review-unavailable':'This restore needs the native unlock screen, which is not available on this device yet. Nothing on this device was changed.',
  };
  for(const [reason,message] of Object.entries(reasons)){
    port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason}));
    await expect(androidRuntime.command(JSON.stringify({type:'native.flow',flow:'backupRestore'}))).rejects.toThrow(message);
  }
  // The remaining blanket setup code keeps its own copy, never the restore vocabulary.
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'backup-setup-unavailable'}));
  await expect(androidRuntime.command(JSON.stringify({type:'native.flow',flow:'backupSetup'})))
    .rejects.toThrow('Backup setup is not available on this device yet.');
});
test('routes the automatic backup preference to the owner and surfaces a refused write',async()=>{
  await androidRuntime.getSnapshot();
  await androidRuntime.command(JSON.stringify({type:'settings.set',key:'backup.automatic',value:true}));
  expect(JSON.parse(port.command.mock.calls.at(-1)![0])).toMatchObject({type:'settings.set',key:'backup.automatic',value:true,generation:'7',schema:1});
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'setting-refused'}));
  await expect(androidRuntime.command(JSON.stringify({type:'settings.set',key:'backup.automatic',value:false})))
    .rejects.toThrow('could not save the automatic backup setting');
  // The backup-specific copy is scoped to backup.automatic; a plain customization refusal stays generic.
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'setting-refused'}));
  await expect(androidRuntime.command(JSON.stringify({type:'settings.set',key:'appearance',value:'dark'})))
    .rejects.toThrow('This action could not be completed.');
});
test('projects the retained look and canonical notification toggles without inventing absent families',()=>{
  const projected=projectAndroidSnapshot({...productFixture,preferences:{appearance:'system',look:'original',matchTextSize:true,textSize:3,haptics:true},notifications:{'Filter changes':false,"Filter couldn't switch":true,'Protection resumed':true,'Connection updates':true}});
  expect(projected.look).toBe('original');
  expect(projected.session.notifications).toEqual({'Filter changes':false,"Filter couldn't switch":true,'Protection resumed':true,'Connection updates':true});
  // No owner-projected family means no fabricated toggles or account.
  expect(projectAndroidSnapshot(productFixture).session.notifications).toEqual({});
  expect(projectAndroidSnapshot(productFixture).account.signedIn).toBe(false);
});
test('projects all Guardian artwork with confirmed availability gates',()=>{
  const guards=projectAndroidSnapshot(productFixture).guards;
  expect(guards.map(guard=>guard.id)).toEqual(['original','emberObsidian','purpleObsidian','obsidian','strawberryObsidian','emerald','kiwiCreme','aquamarine']);
  expect(guards[0]).toMatchObject({id:'original',title:'Original',subtitle:'',selectable:true});
  for(const guard of guards.slice(1)){
    expect(guard.selectable).toBe(false);
    expect(guard.title).toContain('days');
    expect(guard.description.length).toBeGreaterThan(0);
    expect(guard.tip.length).toBeGreaterThan(0);
  }
});
test('reflects a persisted alternate look with its row selectable',()=>{
  const projected=projectAndroidSnapshot({...productFixture,preferences:{appearance:'system',look:'obsidian',matchTextSize:true,textSize:3,haptics:true}});
  expect(projected.look).toBe('obsidian');
  expect(projected.guards.find(guard=>guard.id==='obsidian')).toMatchObject({selectable:true,subtitle:'',title:'Obsidian'});
  expect(projected.guards.find(guard=>guard.id==='original')?.selectable).toBe(true);
});
test('forwards a selected Guard look to the settings owner',async()=>{
  await androidRuntime.getSnapshot();
  await androidRuntime.command(JSON.stringify({type:'settings.set',key:'look',value:'original'}));
  expect(JSON.parse(port.command.mock.calls.at(-1)![0])).toMatchObject({type:'settings.set',key:'look',value:'original',generation:'7',schema:1});
});
test('forwards filter.refresh to the generation-fenced catalog owner',async()=>{
  await androidRuntime.getSnapshot();
  port.command.mockResolvedValueOnce(JSON.stringify({status:'completed',value:'updated'}));
  await androidRuntime.command(JSON.stringify({type:'filter.refresh',id:'filter1'}));
  expect(JSON.parse(port.command.mock.calls[0][0])).toMatchObject({type:'filter.refresh',id:'filter1',generation:'7',schema:1});
});
test('surfaces each catalog refresh refusal as honest copy and never as a fabricated update',async()=>{
  await androidRuntime.getSnapshot();
  const expected:Record<string,string>={
    'catalog-offline':"Lava couldn't reach the update service. Your current filter is unchanged.",
    'catalog-stale':'That update is older than the catalog already on this device. Your current filter is unchanged.',
    'catalog-invalid':"That catalog update wasn't valid. Your current filter is unchanged.",
    'catalog-persist-failed':"Lava couldn't save the catalog update. Your current filter is unchanged.",
    'catalog-install-failed':"Lava couldn't apply the catalog update. Your current filter is unchanged.",
    'catalog-unavailable':'Catalog updates are not available on this device yet.',
  };
  for(const [reason,message] of Object.entries(expected)){
    port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason}));
    await expect(androidRuntime.command(JSON.stringify({type:'filter.refresh',id:'filter1'})))
      .rejects.toThrow(message);
  }
});


test('dispatches the custom DNS draft and returns the exact owner token',async()=>{
  await androidRuntime.getSnapshot();
  port.command.mockResolvedValueOnce(JSON.stringify({status:'completed',value:'token-1'}));
  const reply=JSON.parse(await androidRuntime.command(JSON.stringify({type:'dns.customDraft',choice:{id:'custom-dns',name:'Custom',primary:'',secondary:'',transport:'DoH',metadata:''}})));
  expect(reply.result).toBe('token-1');
  expect(JSON.parse(port.command.mock.calls[0][0])).toMatchObject({type:'dns.customDraft',schema:1,generation:'7'});
});

test('dispatches token-owned custom staging and returns the exact raw choice without a tier command',async()=>{
  await androidRuntime.getSnapshot();
  const choice={id:'custom-dns|DoQ',name:'',primary:'sdns://raw-primary',secondary:'sdns://raw-secondary',transport:'DoQ',metadata:''};
  port.command.mockResolvedValueOnce(JSON.stringify({status:'completed',value:choice}));
  const request={type:'dns.customStage',id:'token-1',context:'ctx',name:'',primary:choice.primary,secondary:choice.secondary};
  const reply=JSON.parse(await androidRuntime.command(JSON.stringify(request)));
  expect(reply.result).toEqual(choice);
  expect(port.command).toHaveBeenCalledTimes(1);
  expect(JSON.parse(port.command.mock.calls[0][0])).toMatchObject({...request,schema:1,generation:'7'});
});

test('surfaces the runtime-absent custom refusal honestly instead of faking a save',async()=>{
  await androidRuntime.getSnapshot();
  for(const reason of ['custom-resolver-runtime-absent','transport-unavailable']){
    port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason}));
    await expect(androidRuntime.command(JSON.stringify({type:'dns.custom',context:'ctx',name:'Home',primary:'https://dns.example/query',secondary:''})))
      .rejects.toThrow('Custom DNS is not available on this device yet.');
  }
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'custom-requires-plus'}));
  await expect(androidRuntime.command(JSON.stringify({type:'dns.customDraft'}))).rejects.toThrow('Custom DNS requires Lava Plus.');
});

test('scopes DNS refusal copy to DNS commands only',async()=>{
  await androidRuntime.getSnapshot();
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'transport-unavailable'}));
  await expect(androidRuntime.command(JSON.stringify({type:'dns.tiers',context:'ctx',tiers:[]}))).rejects.toThrow('That DNS option is not available in this build yet.');
  // The shared screen saves a selected custom tier through `dns.tiers`; the named runtime-absent
  // refusal must still surface the custom copy, not the generic transport copy.
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'custom-resolver-runtime-absent'}));
  await expect(androidRuntime.command(JSON.stringify({type:'dns.tiers',context:'ctx',tiers:[{id:'custom-dns|DoH',name:'Home',primary:'https://dns.example/query',secondary:''}]})))
    .rejects.toThrow('Custom DNS is not available on this device yet.');
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'unauthorized'}));
  await expect(androidRuntime.command(JSON.stringify({type:'customEntry.dismiss',id:'token-1'}))).rejects.toThrow('could not be completed');
});

test('forwards dns.toggle and maps an out-of-range tier refusal to honest copy',async()=>{
  await androidRuntime.getSnapshot();
  port.command.mockResolvedValueOnce(JSON.stringify({status:'completed'}));
  await androidRuntime.command(JSON.stringify({type:'dns.toggle',context:'device-dns|IP;1;mullvad|DoH',index:1,value:true}));
  expect(JSON.parse(port.command.mock.calls.at(-1)![0])).toMatchObject({type:'dns.toggle',context:'device-dns|IP;1;mullvad|DoH',index:1,value:true,generation:'7',schema:1});
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'invalid-tier-index'}));
  await expect(androidRuntime.command(JSON.stringify({type:'dns.toggle',context:'ctx',index:3,value:false})))
    .rejects.toThrow('That DNS tier cannot be toggled on this device.');
});

test('forwards the local-log clear and export commands and keeps the clear confirmation',async()=>{
  await androidRuntime.getSnapshot();
  port.command.mockResolvedValueOnce(JSON.stringify({status:'completed',value:'Domain history cleared.'}));
  const cleared=JSON.parse(await androidRuntime.command(JSON.stringify({type:'logs.clear',kind:'Clear domain history',surface:'activityViewing'})));
  expect(cleared.result).toBe('Domain history cleared.');
  expect(JSON.parse(port.command.mock.calls.at(-1)![0])).toMatchObject({type:'logs.clear',kind:'Clear domain history',surface:'activityViewing',generation:'7',schema:1});
  port.command.mockResolvedValueOnce(JSON.stringify({status:'completed'}));
  await androidRuntime.command(JSON.stringify({type:'logs.export',domains:false}));
  expect(JSON.parse(port.command.mock.calls.at(-1)![0])).toMatchObject({type:'logs.export',domains:false,generation:'7',schema:1});
});

test('tears down the custom draft without a prompt',async()=>{
  const alert=jest.spyOn(Alert,'alert');
  await androidRuntime.getSnapshot();
  await androidRuntime.command(JSON.stringify({type:'customEntry.dismiss',id:'token-1'}));
  expect(JSON.parse(port.command.mock.calls.at(-1)![0])).toMatchObject({type:'customEntry.dismiss',id:'token-1'});
  expect(alert).not.toHaveBeenCalled();
  alert.mockRestore();});
test('keeps native rejection and authentication cancellation visible',async()=>{
  await androidRuntime.getSnapshot();
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'stale-generation'}));
  await expect(androidRuntime.command(JSON.stringify({type:'protection.toggle'}))).rejects.toThrow('changed');
  port.command.mockResolvedValueOnce(JSON.stringify({status:'cancelled'}));
  await expect(androidRuntime.command(JSON.stringify({type:'navigation.authorize',surface:'appSettings'}))).rejects.toThrow('Authentication cancelled.');
});
test('does not resurrect filters from an older snapshot after native privacy redaction',()=>{
  acceptAndroidWire(JSON.stringify({...productFixture,revision,foreground:false,filters:{locked:true,available:false,rows:[]}}));
  const snapshot=acceptAndroidWire(JSON.stringify({...productFixture,revision:revision-1}));
  expect(snapshot.filters).toEqual([]);
  expect(snapshot.protection.disabled).toBe(true);
});

test('projects selected filter draft and saved baseline independently',()=>{
  const wire={...productFixture,filterSession:{id:'other',name:'Other',editing:true,lists:['new'],savedLists:['old'],blocked:['new.example'],allowed:[],savedBlocked:['old.example'],savedAllowed:[],names:{new:'New list',old:'Old list'}},filterEditing:{canSave:true,reviewCanConfirm:false,validation:null,refreshing:false,lists:[{id:'new',pending:false,undo:true}],blocked:[{id:'new.example',pending:false,undo:true}],allowed:[]}};
  const snapshot=projectAndroidSnapshot(wire);
  expect(snapshot.session.filterID).toBe('other');
  expect(snapshot.session.activeFilterID).toBe('filter1');
  expect(snapshot.session.editing).toBe(true);
  expect(snapshot.draft.blocked).toEqual(['new.example']);
  expect(snapshot.savedDraft.blocked).toEqual(['old.example']);
  expect(snapshot.session.savedBlocklists).toEqual(['old']);
  expect(snapshot.filterEditing?.lists?.[0]?.undo).toBe(true);
  expect(projectAndroidSnapshot({...wire,foreground:false,filters:{...wire.filters,locked:true,rows:[]}}).draft.blocked).toEqual([]);
});
test('passes native save and review results without optimistic success',async()=>{
  await androidRuntime.getSnapshot();
  port.command.mockResolvedValueOnce(JSON.stringify({status:'completed',value:'review'}));
  expect(JSON.parse(await androidRuntime.command(JSON.stringify({type:'filter.save',id:'filter1'}))).result).toBe('review');
  expect(JSON.parse(port.command.mock.calls[0][0])).toMatchObject({type:'filter.save',id:'filter1',generation:'7'});
});

test('forwards library edits and reports only the real native deletion commit',async()=>{
  const alert=jest.spyOn(Alert,'alert');
  await androidRuntime.getSnapshot();
  for(const command of [{type:'library.edit'},{type:'library.toggleDeletion',id:'other'},{type:'library.cancel'},{type:'filter.restoreDefaults'}]){
    await androidRuntime.command(JSON.stringify(command));
    expect(JSON.parse(port.command.mock.calls.at(-1)![0])).toMatchObject({...command,generation:'7',schema:1});
  }
  port.command.mockResolvedValueOnce(JSON.stringify({status:'completed',value:true}));
  expect(JSON.parse(await androidRuntime.command(JSON.stringify({type:'library.form',form:'delete',ids:['other']}))).result).toBe(true);
  expect(JSON.parse(port.command.mock.calls.at(-1)![0])).toEqual({type:'library.form',form:'delete',ids:['other'],generation:'7',schema:1});
  expect(alert).not.toHaveBeenCalled();
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'stale-generation'}));
  await expect(androidRuntime.command(JSON.stringify({type:'library.form',form:'delete',ids:['other']}))).rejects.toThrow('changed');
});
test('opens the shared native-owned create form and returns the exact completed draft ID',async()=>{
  await androidRuntime.getSnapshot();
  port.command.mockResolvedValueOnce(JSON.stringify({status:'completed',value:'filter-new'}));
  expect(JSON.parse(await androidRuntime.command(JSON.stringify({type:'library.form',form:'create'}))).result).toBe('filter-new');
  expect(JSON.parse(port.command.mock.calls[0]![0])).toEqual({type:'library.form',form:'create',generation:'7',schema:1});
  // Template and identity selection happen in the projected shared page; opening it invents neither.
  expect(port.command).toHaveBeenCalledTimes(1);
});
test('allows shared form controls while its original native continuation is pending',async()=>{
  await androidRuntime.getSnapshot();
  let complete!: (reply:string)=>void;
  port.command.mockImplementationOnce(()=>new Promise<string>(resolve=>{complete=resolve;}));
  const opening=androidRuntime.command(JSON.stringify({type:'library.form',form:'rename',id:'filter1'}));
  const controls=[
    {type:'foreground.enter',id:'visit-1'},
    {type:'foreground.identity',id:'visit-1',name:'Renamed',emoji:'👩🏽‍💻'},
    {type:'foreground.dirty',id:'visit-1',dirty:true},
    {type:'foreground.submit',id:'visit-1'},
  ];
  for(const control of controls){
    await androidRuntime.command(JSON.stringify(control));
    expect(JSON.parse(port.command.mock.calls.at(-1)![0])).toEqual({...control,generation:'7',schema:1});
  }
  complete(JSON.stringify({status:'completed',value:true}));
  expect(JSON.parse(await opening).result).toBe(true);
  expect(JSON.parse(port.command.mock.calls[0]![0])).toEqual({type:'library.form',form:'rename',id:'filter1',generation:'7',schema:1});
});
test('cancelled native forms return false and never fabricate a successful mutation',async()=>{
  await androidRuntime.getSnapshot();
  for(const command of [
    {type:'library.form',form:'create'}, {type:'library.form',form:'rename',id:'filter1'},
    {type:'library.form',form:'delete',ids:['other']}, {type:'filter.renameForm',id:'filter1'},
  ]){
    port.command.mockResolvedValueOnce(JSON.stringify({status:'cancelled'}));
    expect(JSON.parse(await androidRuntime.command(JSON.stringify(command))).result).toBe(false);
    expect(JSON.parse(port.command.mock.calls.at(-1)![0])).toEqual({...command,generation:'7',schema:1});
  }
});
test('surfaces native name, emoji and stale-template refusals without optimistic success',async()=>{
  await androidRuntime.getSnapshot();
  for(const [reason,copy] of [
    ['library-invalid-name','unused filter name'],['library-invalid-emoji','Choose one emoji.'],
    ['library-stale-staging','changed'],
  ]){
    port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason}));
    await expect(androidRuntime.command(JSON.stringify({type:'library.form',form:'rename',id:'filter1'}))).rejects.toThrow(copy);
  }
});
test('opens the shared detail rename under the native editing owner and forwards refusal honestly',async()=>{
  await androidRuntime.getSnapshot();
  port.command.mockResolvedValueOnce(JSON.stringify({status:'completed',value:true}));
  expect(JSON.parse(await androidRuntime.command(JSON.stringify({type:'filter.renameForm',id:'filter1'}))).result).toBe(true);
  expect(JSON.parse(port.command.mock.calls[0]![0])).toEqual({type:'filter.renameForm',id:'filter1',generation:'7',schema:1});
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'rename-refused'}));
  await expect(androidRuntime.command(JSON.stringify({type:'filter.renameForm',id:'filter1'}))).rejects.toThrow('Couldn’t save');
});
test('a pending form cannot rebind its native continuation to a successor graph',async()=>{
  await androidRuntime.getSnapshot();
  let refuse!: (reply:string)=>void;
  port.command.mockImplementationOnce(()=>new Promise<string>(resolve=>{refuse=resolve;}));
  const opening=androidRuntime.command(JSON.stringify({type:'filter.renameForm',id:'filter1'}));
  acceptAndroidWire(JSON.stringify({...productFixture,revision:++revision,generation:'8'}));
  const rejected=expect(opening).rejects.toThrow('changed');
  refuse(JSON.stringify({status:'rejected',reason:'stale-generation'}));
  await rejected;
  expect(JSON.parse(port.command.mock.calls[0]![0])).toEqual({type:'filter.renameForm',id:'filter1',generation:'7',schema:1});
});

test('projects confirmed retention and clears it when unavailable or backgrounded',()=>{
  const wire={...productFixture,logging:{keepCounts:true,keepHistory:false,keepNetworkActivity:true,keepLavaGuardProgress:true}};
  expect(projectAndroidSnapshot(wire).session.logs['Lava Guard Progress']).toBe(true);
  expect(projectAndroidSnapshot({...wire,logging:{...wire.logging,keepLavaGuardProgress:false}}).session.logs['Lava Guard Progress']).toBe(false);
  for(const unavailable of [{...wire,foreground:false},{...wire,available:false},{...wire,logging:undefined}]){
    expect(projectAndroidSnapshot(unavailable).session.logs).toEqual({});
    expect(projectAndroidSnapshot(unavailable).sudoku).toBeUndefined();
  }
});

test('opens existing native security controls through the generation-fenced host',async()=>{
  await androidRuntime.getSnapshot();
  await androidRuntime.command(JSON.stringify({type:'native.flow',flow:'security'}));
  expect(JSON.parse(port.command.mock.calls[0][0])).toMatchObject({type:'native.flow',flow:'security',generation:'7'});
});

const securityWire=(overrides:Partial<NonNullable<ProductSnapshot['security']>>={}):ProductSnapshot=>({...productFixture,security:{unavailable:false,hasAuthenticationMethod:true,updatingSurface:false,recoveryRequired:false,failure:null,protectedSurfaces:['appUnlock','appSettings'],authenticatedSurfaces:[],showBiometrics:false,canEnableBiometrics:false,biometricLockout:false,biometricTitle:'Biometrics',status:'',...overrides}});

test('projects the security family and protected-action opt-ins from the owner',()=>{
  const snapshot=projectAndroidSnapshot(securityWire());
  expect(snapshot.security.unavailable).toBe(false);
  expect(snapshot.security.hasAuthenticationMethod).toBe(true);
  expect(snapshot.security.showBiometrics).toBe(false);
  expect(snapshot.session.passcode).toBe(true);
  expect(snapshot.session.biometrics).toBe(false);
  expect(snapshot.session.protectedActions['App Unlock']).toBe(true);
  expect(snapshot.session.protectedActions['Update App Settings']).toBe(true);
  expect(snapshot.session.protectedActions['Pause Lava']).toBe(false);
  // A missing credential cannot protect a surface even if a stale set claims one.
  const noMethod=projectAndroidSnapshot(securityWire({hasAuthenticationMethod:false}));
  expect(Object.values(noMethod.session.protectedActions).every(value=>value===false)).toBe(true);
});

test('an unavailable or absent security owner renders honest unavailable and never fabricates protection',()=>{
  const unavailable=projectAndroidSnapshot(securityWire({unavailable:true,hasAuthenticationMethod:false,recoveryRequired:true,protectedSurfaces:['appUnlock']}));
  expect(unavailable.security.unavailable).toBe(true);
  expect(unavailable.session.passcode).toBe(false);
  expect(unavailable.session.protectedActions['App Unlock']).toBe(false);
  // No family at all is the same honest state, not an invented passcode-off row.
  const absent=projectAndroidSnapshot(productFixture);
  expect(absent.security.unavailable).toBe(true);
  expect(absent.security.showBiometrics).toBe(false);
});

test('forwards protectedActions settings.set to the security owner and surfaces a refusal',async()=>{
  await androidRuntime.getSnapshot();
  await androidRuntime.command(JSON.stringify({type:'settings.set',key:'protectedActions.Pause Lava',value:true}));
  expect(JSON.parse(port.command.mock.calls.at(-1)![0])).toMatchObject({type:'settings.set',key:'protectedActions.Pause Lava',value:true,generation:'7',schema:1});
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'setting-refused'}));
  await expect(androidRuntime.command(JSON.stringify({type:'settings.set',key:'protectedActions.Pause Lava',value:false})))
    .rejects.toThrow('previous setting is still in effect');
});

test('refuses biometrics with the honest platform-unavailable copy',async()=>{
  await androidRuntime.getSnapshot();
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'unavailable-per-platform'}));
  await expect(androidRuntime.command(JSON.stringify({type:'settings.set',key:'biometrics',value:true})))
    .rejects.toThrow('Biometrics are not available on this device yet.');
});

test('routes native.flow passcode to the native credential presenter',async()=>{
  await androidRuntime.getSnapshot();
  port.command.mockResolvedValueOnce(JSON.stringify({status:'completed'}));
  const reply=JSON.parse(await androidRuntime.command(JSON.stringify({type:'native.flow',flow:'passcode'})));
  expect(reply.result).toBeNull();
  expect(JSON.parse(port.command.mock.calls.at(-1)![0])).toMatchObject({type:'native.flow',flow:'passcode',generation:'7',schema:1});
  port.command.mockResolvedValueOnce(JSON.stringify({status:'cancelled'}));
  await expect(androidRuntime.command(JSON.stringify({type:'native.flow',flow:'passcode'}))).rejects.toThrow('Authentication cancelled.');
});

test('Today uses confirmed retained counters for the native current-day range only',()=>{
  const wire={...productFixture,logging:{keepCounts:true,keepHistory:true,keepNetworkActivity:true,keepLavaGuardProgress:true},activity:{...productFixture.activity,startDate:'2026-09-28',endDate:'2026-09-28',todayDate:'2026-09-28'}};
  expect(projectAndroidSnapshot(wire).protection.today).toEqual({countsEnabled:true,allowed:120,blocked:15});
  for(const unavailable of [
    {...wire,foreground:false},{...wire,available:false},{...wire,activity:{...wire.activity,locked:true}},
    {...wire,activity:{...wire.activity,startDate:'2026-09-27'}},
    {...wire,activity:{...wire.activity,allowed:null}},
    {...wire,activity:{...wire.activity,allowed:'9223372036854775807'}},
  ])expect(projectAndroidSnapshot(unavailable).protection.today).toBeUndefined();
  expect(projectAndroidSnapshot({...wire,logging:{...wire.logging,keepCounts:false}}).protection.today?.countsEnabled).toBe(false);
});

test('forwards share commands and surfaces the owner content without inventing a card',async()=>{
  await androidRuntime.getSnapshot();
  port.command.mockResolvedValueOnce(JSON.stringify({status:'completed',value:{code:'LF1-x',url:'https://lavasecurity.app/app/import/#LF1-x',image:null}}));
  const reply=JSON.parse(await androidRuntime.command(JSON.stringify({type:'share.query',id:'filter1'})));
  expect(JSON.parse(port.command.mock.calls.at(-1)![0])).toMatchObject({type:'share.query',id:'filter1',generation:'7'});
  expect(reply.result).toEqual({code:'LF1-x',url:'https://lavasecurity.app/app/import/#LF1-x',image:null});
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'share-card-unavailable'}));
  await expect(androidRuntime.command(JSON.stringify({type:'share.card',id:'filter1'}))).rejects.toThrow('Copy the setup code instead');
});

test('hands the pasted code to the backend review owner and never applies it locally',async()=>{
  await androidRuntime.getSnapshot();
  port.command.mockReset().mockResolvedValueOnce(JSON.stringify({status:'completed',value:{blocklistIds:['ads'],blockedDomainCount:2,allowedDomainCount:1,droppedCount:0,unknownSourceIds:[],canConfirm:true}}));
  const reply=JSON.parse(await androidRuntime.command(JSON.stringify({type:'native.flow',flow:'importCode',code:'LF1-shared'})));
  expect(JSON.parse(port.command.mock.calls.at(-1)![0])).toMatchObject({type:'native.flow',flow:'importCode',code:'LF1-shared',generation:'7'});
  expect(reply.result).toMatchObject({blocklistIds:['ads'],blockedDomainCount:2,allowedDomainCount:1,canConfirm:true});
});

test('reports the owner reason for the camera and document import adapters that are not wired',async()=>{
  await androidRuntime.getSnapshot();
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'share-camera-unavailable'}));
  await expect(androidRuntime.command(JSON.stringify({type:'native.flow',flow:'importScan'}))).rejects.toThrow('camera is not available');
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'share-document-unavailable'}));
  await expect(androidRuntime.command(JSON.stringify({type:'native.flow',flow:'import'}))).rejects.toThrow('photo or file is not available');
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'not-a-code'}));
  await expect(androidRuntime.command(JSON.stringify({type:'native.flow',flow:'importCode',code:'nope'}))).rejects.toThrow('Lava setup code');
});

test('confirms a reviewed import as new and as a replacement through the owner',async()=>{
  await androidRuntime.getSnapshot();
  port.command.mockReset();
  const review={blocklistIds:['ads'],blockedDomainCount:2,allowedDomainCount:1,droppedCount:0,unknownSourceIds:[],canConfirm:true};
  port.command.mockResolvedValueOnce(JSON.stringify({status:'completed',value:review}));
  await androidRuntime.command(JSON.stringify({type:'native.flow',flow:'importCode',code:'LF1-x'}));
  port.command.mockResolvedValueOnce(JSON.stringify({status:'completed'}));
  const reply=JSON.parse(await androidRuntime.command(JSON.stringify({type:'native.flow',flow:'importApply'})));
  expect(reply.result).toBeNull();
  expect(JSON.parse(port.command.mock.calls.at(-1)![0])).toMatchObject({type:'native.flow',flow:'importApply',generation:'7',schema:1});
  // A replacement carries the reviewed target and the explicit active-replacement confirmation,
  // and never re-sends the payload the review already consumed.
  port.command.mockResolvedValueOnce(JSON.stringify({status:'completed',value:review}));
  await androidRuntime.command(JSON.stringify({type:'native.flow',flow:'importCode',code:'LF1-x',target:'filter1'}));
  port.command.mockResolvedValueOnce(JSON.stringify({status:'completed'}));
  await androidRuntime.command(JSON.stringify({type:'native.flow',flow:'importApply',target:'filter1',confirmed:true}));
  const request=JSON.parse(port.command.mock.calls.at(-1)![0]);
  expect(request).toMatchObject({type:'native.flow',flow:'importApply',target:'filter1',confirmed:true});
  expect(request).not.toHaveProperty('code');
});

test('refuses a stale reviewed import and maps the confirmation refusals to copy',async()=>{
  await androidRuntime.getSnapshot();
  port.command.mockReset().mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'stale-review'}));
  await expect(androidRuntime.command(JSON.stringify({type:'native.flow',flow:'importApply'})))
    .rejects.toThrow('The filter changed since it was reviewed. Review the import again.');
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'plan-not-fully-accepted'}));
  await expect(androidRuntime.command(JSON.stringify({type:'native.flow',flow:'importApply'})))
    .rejects.toThrow('Part of this filter cannot be imported, so nothing was changed.');
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'active-replacement-not-confirmed'}));
  await expect(androidRuntime.command(JSON.stringify({type:'native.flow',flow:'importApply',target:'filter1'})))
    .rejects.toThrow('needs your explicit confirmation');
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'nothing-to-import'}));
  await expect(androidRuntime.command(JSON.stringify({type:'native.flow',flow:'importApply'})))
    .rejects.toThrow('nothing in this code to import');
});

test('offers a saved filter for sharing while the owner decides real eligibility',()=>{
  expect(projectAndroidSnapshot(productFixture).filters[0]?.shareable).toBe(true);
  const empty={...productFixture,filters:{...productFixture.filters,rows:[{...productFixture.filters.rows[0]!,empty:true}]}};
  expect(projectAndroidSnapshot(empty).filters[0]?.shareable).toBe(false);
});

const signedOutAccount={signedIn:false,status:'Not signed in',detail:'',appleTitle:'Sign in with Apple',googleTitle:'Sign in with Google',appleConnected:false,googleConnected:false,appleBusy:false,googleBusy:false,busy:false,message:'',googleAvailable:true,appleAvailable:false};
test('projects the confirmed account family and keeps an absent owner honestly unavailable',()=>{
  const signedIn={...signedOutAccount,signedIn:true,status:'Signed in with Google',googleTitle:'Signed in with Google',googleConnected:true,message:''};
  const projected=projectAndroidSnapshot({...productFixture,account:signedIn});
  expect(projected.account).toMatchObject({signedIn:true,googleConnected:true,appleConnected:false,googleTitle:'Signed in with Google',busy:false});
  // Provider capability stays on the wire; the canonical account shape carries no capability.
  expect((projected.account as Record<string,unknown>).googleAvailable).toBeUndefined();
  // No owner-projected family keeps the honest unavailable fallback, never a fabricated sign-in.
  expect(projectAndroidSnapshot(productFixture).account).toMatchObject({signedIn:false,busy:true,googleConnected:false,appleConnected:false});
});
test('routes account commands through the owner and never carries a credential',async()=>{
  await androidRuntime.getSnapshot();
  for(const type of ['account.google','account.signOut','account.delete']){
    port.command.mockResolvedValueOnce(JSON.stringify({status:'completed'}));
    await androidRuntime.command(JSON.stringify({type}));
    const request=JSON.parse(port.command.mock.calls.at(-1)![0]);
    expect(request).toMatchObject({type,schema:1,generation:'7'});
    for(const key of ['token','accessToken','refreshToken','account','accountID','userId','envelope'])expect(request).not.toHaveProperty(key);
  }
});
test('maps the owner account refusals to honest copy and fences a superseded operation',async()=>{
  await androidRuntime.getSnapshot();
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'account-google-unavailable'}));
  await expect(androidRuntime.command(JSON.stringify({type:'account.google'}))).rejects.toThrow('Google sign-in is not available');
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'account-superseded'}));
  await expect(androidRuntime.command(JSON.stringify({type:'account.signOut'}))).rejects.toThrow('account changed');
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'account-not-signed-in'}));
  await expect(androidRuntime.command(JSON.stringify({type:'account.delete'}))).rejects.toThrow('Sign in to your account first.');
});
test('routes Apple sign-in to the owner and surfaces its provider-unavailable capability',async()=>{
  port.getSnapshot.mockResolvedValueOnce(JSON.stringify({...productFixture,revision,account:signedOutAccount}));
  await androidRuntime.getSnapshot();
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'account-apple-unavailable'}));
  await expect(androidRuntime.command(JSON.stringify({type:'account.apple'}))).rejects.toThrow('Apple sign-in is not available on Android yet.');
  expect(JSON.parse(port.command.mock.calls.at(-1)![0])).toMatchObject({type:'account.apple',schema:1,generation:'7'});
});

test('scopes share/import copy to share/import commands so ordinary filter refusals stay generic',async()=>{
  await androidRuntime.getSnapshot();
  // `stale-review` is shared with filter save/apply; an ordinary command must not claim it is an import.
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'stale-review'}));
  await expect(androidRuntime.command(JSON.stringify({type:'filter.apply',id:'filter1',review:'token'}))).rejects.toThrow('Lava changed while this screen was open');
  // `unknown-filter` from a share command surfaces its precise copy…
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'unknown-filter'}));
  await expect(androidRuntime.command(JSON.stringify({type:'share.query',id:'gone'}))).rejects.toThrow('no longer saved on this device');
  // …but the same code from an ordinary filter command is generic.
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'unknown-filter'}));
  await expect(androidRuntime.command(JSON.stringify({type:'filter.open',id:'gone'}))).rejects.toThrow('could not be completed');
});

test('projects confirmed domain-history availability and the loaded row count',()=>{
  const projected=projectAndroidSnapshot({...productFixture,hasDomainHistory:true,domainHistoryCount:12});
  expect(projected.hasDomainHistory).toBe(true);
  expect(projected.domainHistoryCount).toBe(12);
  // A missing owner projection stays false/absent, never fabricated true.
  expect(projectAndroidSnapshot(productFixture).hasDomainHistory).toBe(false);
  expect(projectAndroidSnapshot(productFixture).domainHistoryCount).toBeUndefined();
});

test('forwards the canonical Activity and diagnostics reads and returns the owner value',async()=>{
  await androidRuntime.getSnapshot();
  const cases:[Record<string,unknown>,unknown][]=[
    [{type:'activity.query',start:1,end:2,hourly:true},{allowed:120,blocked:15,uptime:'2h'}],
    [{type:'domains.query',history:false,decision:'All',search:'',limit:31},[{id:'blocked:a.com',domain:'a.com',metadata:'3 times',icon:'xmark.circle.fill',tone:'orange'}]],
    [{type:'network.query'},[{id:'1',title:'Connected',subtitle:'Lava: Healthy',metadata:'Sep 1 · 10:00'} ]],
    [{type:'stats.query'},{app:[['Version','1.0']],healthSections:[],sampleNotice:null}],
  ];
  for(const [command,value] of cases){
    port.command.mockResolvedValueOnce(JSON.stringify({status:'completed',value}));
    const reply=JSON.parse(await androidRuntime.command(JSON.stringify(command)));
    expect(reply.result).toEqual(value);
    expect(JSON.parse(port.command.mock.calls.at(-1)![0])).toMatchObject({...command,schema:1,generation:'7'});
  }
});

test('forwards domain copy/history and stages a standalone domain review with honest refusal copy',async()=>{
  await androidRuntime.getSnapshot();
  await androidRuntime.command(JSON.stringify({type:'domains.copy',domain:'example.com'}));
  expect(JSON.parse(port.command.mock.calls.at(-1)![0])).toMatchObject({type:'domains.copy',domain:'example.com'});
  await androidRuntime.command(JSON.stringify({type:'domains.enableHistory'}));
  expect(JSON.parse(port.command.mock.calls.at(-1)![0])).toMatchObject({type:'domains.enableHistory'});
  // A staged review returns its value through the generic forwarding, never a fabricated success.
  port.command.mockResolvedValueOnce(JSON.stringify({status:'completed',value:{id:'filter-balanced',standaloneReview:'tok'}}));
  const staged=JSON.parse(await androidRuntime.command(JSON.stringify({type:'domains.stage',domain:'example.com',decision:'blocked'})));
  expect(staged.result).toEqual({id:'filter-balanced',standaloneReview:'tok'});
  expect(JSON.parse(port.command.mock.calls.at(-1)![0])).toMatchObject({type:'domains.stage',domain:'example.com',decision:'blocked',schema:1,generation:'7'});
  // A refused stage (the owner could not open the review: missing owner/library, stale
  // generation, or failed authorization) is honest copy, never a fake success. The unsaved-draft
  // guard surfaces separately as a `completed` soft rejection, so this code never means it.
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'domains-review-unavailable'}));
  await expect(androidRuntime.command(JSON.stringify({type:'domains.stage',domain:'example.com',decision:'blocked'})))
    .rejects.toThrow('Lava could not open this domain review right now. Try again.');
});

test('maps each headless read refusal to its own honest copy',async()=>{
  await androidRuntime.getSnapshot();
  const cases:Record<string,string>={
    'activity-locked':'Unlock Lava to view activity.',
    'activity-unavailable':'Activity data is not available yet.',
    'activity-range-unavailable':'That date range is not available.',
    'domains-unavailable':'Top domains are not available yet.',
    'domain-history-unavailable':'Domain history is not available yet.',
    'network-unavailable':'Network activity is not available yet.',
  };
  for(const [reason,message] of Object.entries(cases)){
    port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason}));
    await expect(androidRuntime.command(JSON.stringify({type:'stats.query'}))).rejects.toThrow(message);
  }
});

test('projects the native QA/debug capability and never fabricates it for a release snapshot',()=>{
  // A release snapshot reports qaTools:false, so no QA route can be reached from JS.
  expect(projectAndroidSnapshot(productFixture).qaTools).toBe(false);
  // The projected value is the wire capability, not a fixture default.
  expect(projectAndroidSnapshot({...productFixture,qaTools:true}).qaTools).toBe(true);
  // A malformed payload cannot smuggle the capability past the strict parser.
  expect(()=>parseProductSnapshot(JSON.stringify({...productFixture,qaTools:'yes'}))).toThrow(TypeError);
});

test('the Android route gate reflects the projected QA capability',()=>{
  const release=projectAndroidSnapshot(productFixture);
  const debug=projectAndroidSnapshot({...productFixture,qaTools:true});
  // DeviceQA renders the native `phoneQA` page and is DEBUG_ONLY: refused in release.
  expect(androidReviewRouteAllowed('DeviceQA',debug.qaTools,true)).toBe(true);
  expect(androidReviewRouteAllowed('DeviceQA',release.qaTools,true)).toBe(false);
  // Components maps to `designGallery` (DEBUG_ONLY), but review-gallery mode (`!hasApp`) still
  // declares it so the fixture host renders the design system.
  expect(androidReviewRouteAllowed('Components',false,true)).toBe(false);
  expect(androidReviewRouteAllowed('Components',true,true)).toBe(true);
  expect(androidReviewRouteAllowed('Components',false,false)).toBe(true);
  // VPNChaining is release-reachable only when the live chained owner projects its family; it is
  // never gated on the QA flag. Ordinary screens are ungated.
  expect(androidReviewRouteAllowed('VPNChaining',false,true)).toBe(false);
  expect(androidReviewRouteAllowed('VPNChaining',false,true,{ownerPresent:true})).toBe(true);
  expect(androidReviewRouteAllowed('VPNChaining',true,true,{ownerPresent:false})).toBe(false);
  expect(androidReviewRouteAllowed('VPNChaining',true,false,{ownerPresent:true})).toBe(false);
  expect(androidReviewRouteAllowed('Filters',false,true)).toBe(true);
});


test('routes vpn.* against the rotation generation and maps single-rotation refusals',async()=>{
  const vpn={setup:true,enabled:false,canEnable:true,canEdit:true,fallback:false,canChangeFallback:true,
    needsPlus:false,busy:false,restriction:'',error:'',unavailable:false,needsRepair:false,generation:'42',
    rows:[{name:'vpn.example.com:51820',mode:'Full tunnel',isEnabled:true}],draft:null};
  port.getSnapshot.mockResolvedValue(JSON.stringify({...productFixture,revision,vpn}));
  const projected=JSON.parse(await androidRuntime.getSnapshot());
  expect(projected.vpn).toMatchObject({generation:'42',setup:true,rows:[{mode:'Full tunnel'}]});

  // `vpn.begin` keeps the canonical ROTATION generation; the app graph generation rides separately
  // so the native credential fence still sees it.
  port.command.mockResolvedValueOnce(JSON.stringify({status:'completed',value:{id:'e1'}}));
  const reply=JSON.parse(await androidRuntime.command(JSON.stringify({type:'vpn.begin',id:'e1',generation:'42'})));
  expect(reply.result).toMatchObject({id:'e1'});
  expect(JSON.parse(port.command.mock.calls[0][0])).toMatchObject(
    {schema:1,type:'vpn.begin',id:'e1',generation:'42',productGeneration:'7'});

  // A second-row operation refuses with honest single-rotation copy, never the raw code.
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'vpn-multi-row-unsupported'}));
  await expect(androidRuntime.command(JSON.stringify({type:'vpn.swap',id:'e1'})))
    .rejects.toThrow('one WireGuard configuration');
  // The not-yet-ported native configuration presentation is named explicitly.
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'vpn-configuration-editor-unavailable'}));
  await expect(androidRuntime.command(JSON.stringify({type:'vpn.edit',id:'e1',index:0})))
    .rejects.toThrow('not available on Android');
  // A cancelled first import that could not be fully undone gets honest copy, never the raw code.
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'vpn-import-cleanup-failed'}));
  await expect(androidRuntime.command(JSON.stringify({type:'vpn.cancel',id:'e1'})))
    .rejects.toThrow('could not undo the cancelled import');
});

test('every vpn command type is admitted by the runtime allowlist',async()=>{
  await androidRuntime.getSnapshot();
  for(const command of [
    {type:'vpn.cancel',id:'e1'},{type:'vpn.reset',id:'e1'},{type:'vpn.swap',id:'e1'},
    {type:'vpn.commit',id:'e1'},{type:'vpn.toggle',key:'enabled',value:true},
    {type:'vpn.remove',id:'e1',index:0},{type:'vpn.rowToggle',generation:'42',index:0,value:true},
  ]){
    await androidRuntime.command(JSON.stringify(command));
  }
  expect(port.command).toHaveBeenCalledTimes(7);
});

test('projects an unsaved creation only on an available unlocked foreground graph and preserves saved active identity',()=>{
  const created={id:'new',name:'My copy',emoji:'🌿',lists:['template'],empty:false,blockedDomainCount:1,allowedExceptionCount:0};
  const source={...productFixture,newFilter:created};
  const projected=projectAndroidSnapshot(source);
  expect(projected.newFilter).toMatchObject({...created,frozen:false,shareable:false,count:'Unavailable'});
  expect(projected.filters.map(row=>row.id)).toEqual(productFixture.filters.rows.map(row=>row.id));
  expect(projected.session.activeFilterID).toBe('filter1');
  expect(projectAndroidSnapshot({...source,foreground:false}).newFilter).toBeUndefined();
  expect(projectAndroidSnapshot({...source,filters:{...source.filters,locked:true,rows:[]}}).newFilter).toBeUndefined();
});
test('renames the unsaved native identity and cancels it through its own detail command without committing a library batch',async()=>{
  const created={id:'new',name:'My copy',emoji:'🌿',lists:[],empty:true,blockedDomainCount:0,allowedExceptionCount:0};
  port.getSnapshot.mockResolvedValue(JSON.stringify({...productFixture,revision,newFilter:created}));
  await androidRuntime.getSnapshot();
  port.command.mockResolvedValueOnce(JSON.stringify({status:'completed',value:true}));
  await androidRuntime.command(JSON.stringify({type:'filter.renameForm',id:'new'}));
  expect(JSON.parse(port.command.mock.calls[0][0])).toMatchObject({type:'filter.renameForm',id:'new',generation:'7'});
  await androidRuntime.command(JSON.stringify({type:'filter.cancel',id:'new'}));
  expect(JSON.parse(port.command.mock.calls[1][0])).toMatchObject({type:'filter.cancel',id:'new',generation:'7'});
  expect(port.command).toHaveBeenCalledTimes(2);
});

test('admits first-run setup only on an explicitly available native bootstrap graph',()=>{
  const pending={...productFixture,available:false,onboardingRequired:true};
  expect(projectAndroidSnapshot(pending).available).toBe(false);
  expect(projectAndroidSnapshot({...pending,onboardingAvailable:true}).available).toBe(true);
  expect(projectAndroidSnapshot({...pending,onboardingAvailable:false,foreground:false}).available).toBe(false);
});


test.each([['GuardTab','Guard'],['GuardTab','Filters'],['GuardTab','Activity'],['SettingsTab','Settings']])('forwards the closed native destination %s/%s to the existing RN redirect adapter', (tab,screen)=>{
  const navigation={serial:7,tab,screen} as ProductSnapshot['navigation'];
  expect(projectAndroidSnapshot({...productFixture,navigation}).navigation).toEqual(navigation);
  expect(projectAndroidSnapshot(productFixture).navigation).toBeUndefined();
});


test('DNS connection and settings copy localize only native defaults using their own packet locale',()=>{
  configurePresentation({locale:'fr',textScales:null});
  try {
    const part=(name:string,nameIsDefault:boolean)=>({name,nameIsDefault,detail:'',transport:''});
    const packet=(primary:ReturnType<typeof part>,fallback:ReturnType<typeof part>|null=null)=>({...productFixture,presentation:{locale:'de',textScales:null},settingsSummary:{dns:'old summary'},connection:{dns:{primary,fallback},vpn:{eligible:false,enabled:false}}});
    const system=projectAndroidSnapshot(packet(part('Device DNS',true),part('Custom DNS',true)));
    expect(system.connection?.dns.primary.name).toBe('Geräte-DNS');
    expect(system.connection?.dns.fallback?.name).toBe('Eigenes DNS');
    expect(system.settingsSummary.dns).toBe('Geräte-DNS + Ersatz');
    for(const name of ['Cancel','Save','Device DNS','Custom DNS']){
      const authored=projectAndroidSnapshot(packet(part(name,false),part('Cancel',false)));
      expect(authored.connection?.dns.primary.name).toBe(name);
      expect(authored.connection?.dns.fallback?.name).toBe('Cancel');
      expect(authored.settingsSummary.dns).toBe(`${name} + Ersatz`);
    }
    // An old producer with no provenance cannot turn an identity into translated copy.
    const legacy=projectAndroidSnapshot({...productFixture,presentation:{locale:'de',textScales:null},connection:{dns:{primary:{name:'Device DNS',detail:'',transport:''}},vpn:{eligible:false,enabled:false}}});
    expect(legacy.connection?.dns.primary.name).toBe('Device DNS');
    expect(projectAndroidSnapshot(productFixture).connection).toBeUndefined();
  } finally {configurePresentation({locale:'en',textScales:null});}
});

test('custom DNS Plus refusal uses the actual translated catalog key in all supported locales',async()=>{
  await androidRuntime.getSnapshot();
  for(const locale of Object.keys(translations)){
    configurePresentation({locale,textScales:null});
    try {
      port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'custom-requires-plus'}));
      let refusal:unknown;
      try {await androidRuntime.command(JSON.stringify({type:'dns.customDraft'}));}catch(error){refusal=error;}
      expect(refusal).toBeInstanceOf(Error);
      expect((refusal as Error).message).toBe('Custom DNS requires Lava Plus.');
      expect(localized((refusal as Error).message)).toBe(translations[locale]!['Custom DNS requires Lava Plus.']);
    } finally {configurePresentation({locale:'en',textScales:null});}
  }
});


test('DNS defaults and fallback summaries resolve in every supported native packet locale',()=>{
  configurePresentation({locale:'en',textScales:null});
  const locales=[...Object.keys(translations).map(locale=>[locale,locale]),
    ['pt','pt-BR'],['pt-PT','pt-BR'],['zh-Hans-HK','zh-Hans']];
  for(const [locale,catalog] of locales){
    const table=translations[catalog!]!;
    const packet={...productFixture,presentation:{locale:locale!,textScales:null},settingsSummary:{dns:'Device DNS + Fallback'},
      connection:{dns:{primary:{name:'Device DNS',nameIsDefault:true,detail:'',transport:''},fallback:{name:'Custom DNS',nameIsDefault:true,detail:'',transport:'DoH'}},vpn:{eligible:false,enabled:false}}};
    const next=projectAndroidSnapshot(packet);
    expect(next.connection?.dns.primary.name).toBe(table['Device DNS']);
    expect(next.connection?.dns.fallback?.name).toBe(table['Custom DNS']);
    expect(next.settingsSummary.dns).toBe(table['%@ + Fallback']!.replace('%@',()=>table['Device DNS']!));
    const authored=projectAndroidSnapshot({...packet,connection:{...packet.connection,dns:{
      primary:{...packet.connection.dns.primary,nameIsDefault:false},
      fallback:{...packet.connection.dns.fallback,nameIsDefault:false},
    }}});
    expect(authored.connection?.dns.primary.name).toBe('Device DNS');
    expect(authored.connection?.dns.fallback?.name).toBe('Custom DNS');
    expect(authored.settingsSummary.dns).toBe(table['%@ + Fallback']!.replace('%@',()=>'Device DNS'));
  }
});

test('portable encrypted backup refusals use exact export and recovery copy',async()=>{
  await androidRuntime.getSnapshot();
  const cases=[
    ['backup.export','backup-export-unavailable','Unlock Lava and finish setup before exporting a backup.'],
    ['backup.export','backup-export-failed','Lava could not save the encrypted file. Choose a writable location and try again.'],
    ['backup.export','backup-export-too-large','This configuration is too large for a portable backup. No file was saved.'],
    ['backup.import','backup-restore-wrong-secret','That unlock secret did not match your backup. Nothing on this device was changed.'],
    ['backup.import','backup-restore-corrupt-payload','This backup is damaged or incomplete. Nothing on this device was changed.'],
    ['backup.import','backup-restore-unsupported-schema','This backup was made by a newer version of Lava. Update the app and try again.'],
    ['backup.import','backup-restore-rollback-failed','The restore could not be applied cleanly and your settings may have changed. Reopen Lava and check your filters.'],
    ['backup.pending.resume','backup-pending-missing','No encrypted import is saved for later.'],
    ['backup.pending.resume','backup-pending-unreadable','A saved encrypted import could not be read. Discard it and import the original file again.'],
    ['backup.pending.discard','backup-pending-discard-failed','Lava could not discard the saved import. Try again.'],
    ['backup.import','backup-pending-save-failed','Lava could not save the encrypted import. Keep the original file and try again.'],
    ['backup.import','backup-pending-save-unverified','Lava could not verify whether the encrypted import was saved. Keep the original file and check the pending import before retrying.'],
  ];
  for(const [type,reason,message] of cases){
    port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason}));
    await expect(androidRuntime.command(JSON.stringify({type}))).rejects.toThrow(message);
  }
});
