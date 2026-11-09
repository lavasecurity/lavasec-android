import {AppState,DeviceEventEmitter,NativeModules} from 'react-native';
import {productFixture} from './productFixture';
import type {ProductSnapshot} from '../src/productContract';
import {AppCommandDetachedError,AppStore} from '../canonical/app/store';

const port:{getSnapshot:jest.Mock;getSnapshotForHost?:jest.Mock;command:jest.Mock}={getSnapshot:jest.fn(),getSnapshotForHost:jest.fn(),command:jest.fn()};
NativeModules.NativeLavaApp=port;
const {androidRuntime,androidRuntimeForHost,acceptAndroidWire}=require('../canonical/android/runtime') as typeof import('../canonical/android/runtime');
const {androidAppearanceForHost}=require('../canonical/specs/NativeLavaAppearance.android') as typeof import('../canonical/specs/NativeLavaAppearance.android');
const packet=(nativeHostID:string,revision=10,generation='7'):ProductSnapshot=>({...productFixture,nativeHostID,revision,generation,
  filters:{...productFixture.filters,rows:[{...productFixture.filters.rows[0]!,name:`Private ${nativeHostID}`}]}});
const deferred=<T,>()=>{let resolve!:(value:T)=>void;const promise=new Promise<T>(yes=>{resolve=yes;});return {promise,resolve};};
beforeEach(()=>{
  AppState.currentState='active';
  port.getSnapshot.mockReset().mockResolvedValue(JSON.stringify(packet('wrong-global',1000)));
  port.getSnapshotForHost=jest.fn(async(id:string)=>JSON.stringify(packet(id)));
  port.command.mockReset().mockResolvedValue(JSON.stringify({status:'completed'}));
});
afterEach(()=>jest.restoreAllMocks());

test('each root reads its originating Activity and commands retain its own cached graph after foreign broadcasts',async()=>{
  const source=androidRuntimeForHost('source'),qa=androidRuntimeForHost('qa');
  port.getSnapshotForHost!.mockImplementation(async(id:string)=>JSON.stringify(packet(id,10,id==='source'?'7':'8')));
  const sourceListener=jest.fn(),qaListener=jest.fn();
  const s=source.onSnapshot(sourceListener),q=qa.onSnapshot(qaListener);
  try {
    expect(JSON.parse(await source.getSnapshot()).filters[0].name).toBe('Private source');
    expect(JSON.parse(await qa.getSnapshot()).filters[0].name).toBe('Private qa');
    DeviceEventEmitter.emit('lavaAppSnapshot',JSON.stringify(packet('qa',100,'8')));
    DeviceEventEmitter.emit('lavaAppSnapshot',JSON.stringify({...packet('source',500),nativeHostID:undefined}));
    expect(sourceListener).not.toHaveBeenCalled();expect(qaListener).toHaveBeenCalledTimes(1);
    const result=JSON.parse(await source.command(JSON.stringify({type:'protection.toggle'})));
    expect(JSON.parse(port.command.mock.calls[0]![0])).toMatchObject({nativeHostID:'source',generation:'7'});
    expect(result.snapshot.filters[0].name).toBe('Private source');
    expect(port.getSnapshot).not.toHaveBeenCalled();
    expect(port.getSnapshotForHost!.mock.calls.map(([id])=>id)).toEqual(['source','qa','source']);
  } finally{s.remove();q.remove();}
});

test('foreign events never replace either real AppStore frame, even for the same credential generation',async()=>{
  const source=new AppStore(androidRuntimeForHost('source')),qa=new AppStore(androidRuntimeForHost('qa'));
  const stopSource=source.connect(),stopQA=qa.connect();
  try {
    await Promise.all([source.refresh(),qa.refresh()]);
    const accepted=source.getSnapshot().snapshot;
    DeviceEventEmitter.emit('lavaAppSnapshot',JSON.stringify(packet('qa',11)));
    expect(source.getSnapshot().snapshot).toBe(accepted);
    expect(qa.getSnapshot().snapshot?.filters[0]?.name).toBe('Private qa');
    expect(qa.getSnapshot().snapshot?.revision).toBe(11);
    expect(source.getSnapshot().snapshot?.nativeHostID).toBe('source');
    expect(qa.getSnapshot().snapshot?.nativeHostID).toBe('qa');
  }finally{stopSource();stopQA();}
});

test('a stale-host read rejects before consulting a cached frame, including the legacy native read fallback',async()=>{
  const source=androidRuntimeForHost('source');
  await source.getSnapshot();
  port.getSnapshotForHost!.mockResolvedValueOnce(JSON.stringify(packet('qa',1)));
  await expect(source.getSnapshot()).rejects.toBeInstanceOf(AppCommandDetachedError);
  delete port.getSnapshotForHost;
  await expect(source.getSnapshot()).rejects.toBeInstanceOf(AppCommandDetachedError);
  port.getSnapshot.mockResolvedValueOnce(JSON.stringify(packet('source',11)));
  expect(JSON.parse(await source.getSnapshot()).revision).toBe(11);
});

test('native stale-host Promise and command refusals become detached outcomes without hiding actual errors',async()=>{
  const source=androidRuntimeForHost('source');
  port.getSnapshotForHost!.mockRejectedValueOnce(Object.assign(new Error('The native app window is inactive.'),{code:'stale-host'}));
  await expect(source.getSnapshot()).rejects.toBeInstanceOf(AppCommandDetachedError);
  await source.getSnapshot();
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'stale-host'}));
  await expect(source.command(JSON.stringify({type:'navigation.presentationReady',serial:1}))).rejects.toBeInstanceOf(AppCommandDetachedError);
  port.getSnapshotForHost!.mockRejectedValueOnce(new Error('Native parser failed.'));
  await expect(source.getSnapshot()).rejects.toThrow('Native parser failed.');
});

test('a delayed command cannot publish the successor Activity snapshot or report success to its old root',async()=>{
  const source=androidRuntimeForHost('source'),reply=deferred<string>();
  await source.getSnapshot();port.command.mockReturnValueOnce(reply.promise);
  const pending=source.command(JSON.stringify({type:'settings.set',key:'haptics',value:true}));
  port.getSnapshotForHost!.mockResolvedValueOnce(JSON.stringify(packet('qa',50)));
  reply.resolve(JSON.stringify({status:'completed',value:'committed'}));
  await expect(pending).rejects.toBeInstanceOf(AppCommandDetachedError);
  port.getSnapshotForHost!.mockResolvedValueOnce(JSON.stringify(packet('source',11)));
  expect(JSON.parse(await source.getSnapshot()).filters[0].name).toBe('Private source');
  expect(JSON.parse(port.command.mock.calls[0]![0])).toMatchObject({nativeHostID:'source'});
});

test('host binding overrides a caller-supplied nonce and preserves VPN rotation versus app graph generations',async()=>{
  const source=androidRuntimeForHost('source');await source.getSnapshot();
  await source.command(JSON.stringify({type:'vpn.begin',generation:'rotation-1',nativeHostID:'qa'}));
  expect(JSON.parse(port.command.mock.calls[0]![0])).toMatchObject({generation:'rotation-1',productGeneration:'7',nativeHostID:'source'});
});

test('the appearance reader and notifications remain bound to their originating window',async()=>{
  const source=androidAppearanceForHost('source'),listener=jest.fn();
  const subscription=source.onSnapshot(listener);
  try {
    port.getSnapshotForHost!.mockResolvedValueOnce(JSON.stringify({...packet('source'),preferences:{appearance:'dark',look:'original',matchTextSize:true,textSize:3,haptics:true}}));
    expect(await source.getSnapshot()).toEqual({preference:'dark',revision:10});
    DeviceEventEmitter.emit('lavaAppSnapshot',JSON.stringify({...packet('qa',20),preferences:{appearance:'light'}}));
    expect(listener).not.toHaveBeenCalled();
    DeviceEventEmitter.emit('lavaAppSnapshot',JSON.stringify({...packet('source',11),preferences:{appearance:'dark'}}));
    expect(listener).toHaveBeenCalledWith({preference:'dark',revision:11});
    await source.setPreference('dark');
    expect(JSON.parse(port.command.mock.calls[0]![0])).toMatchObject({nativeHostID:'source',type:'settings.set',key:'appearance',value:'dark'});
  }finally{subscription.remove();}
});

test('the explicit unbound compatibility API still accepts legacy packets and adds no native nonce',async()=>{
  const legacy={...productFixture,revision:1001};acceptAndroidWire(JSON.stringify(legacy));
  port.getSnapshot.mockResolvedValue(JSON.stringify(legacy));
  await androidRuntime.command(JSON.stringify({type:'protection.toggle'}));
  expect(JSON.parse(port.command.mock.calls[0]![0])).not.toHaveProperty('nativeHostID');
  expect(port.getSnapshotForHost).not.toHaveBeenCalled();
});


test('cold public Retry controls use only exact host metadata without reading or projecting a failed graph',async()=>{
  const source=androidRuntimeForHost('source');
  port.getSnapshotForHost!.mockRejectedValue(new Error('Private graph read failed'));
  port.command.mockImplementation(async(raw:string)=>{const request=JSON.parse(raw);return JSON.stringify({status:'completed',value:
    request.type==='navigation.retryPresentation'?{generation:'7',serial:request.serial}:true});});
  expect(JSON.parse(await source.command(JSON.stringify({type:'navigation.retryPresentation',serial:1})))).toEqual({result:{generation:'7',serial:1}});
  expect(JSON.parse(await source.command(JSON.stringify({type:'navigation.retryPresentationReady',serial:1,generation:'7'})))).toEqual({result:true});
  expect(port.command.mock.calls.map(([raw])=>JSON.parse(raw))).toEqual([
    {schema:1,type:'navigation.retryPresentation',serial:1,nativeHostID:'source'},
    {schema:1,type:'navigation.retryPresentationReady',serial:1,generation:'7',nativeHostID:'source'},
  ]);
  expect(port.getSnapshot).not.toHaveBeenCalled();expect(port.getSnapshotForHost).not.toHaveBeenCalled();
});

test('cold Retry refuses unbound, malformed and stale native receipts without fallback private reads',async()=>{
  const source=androidRuntimeForHost('source');
  await expect(androidRuntime.command(JSON.stringify({type:'navigation.retryPresentation',serial:1}))).rejects.toThrow('Invalid public retry frame.');
  await expect(source.command(JSON.stringify({type:'navigation.retryPresentation',serial:1.5}))).rejects.toThrow('Invalid public retry frame.');
  port.command.mockResolvedValueOnce(JSON.stringify({status:'completed',value:{generation:'7',serial:99}}));
  await expect(source.command(JSON.stringify({type:'navigation.retryPresentation',serial:1}))).rejects.toThrow('Invalid public retry receipt.');
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'stale-host'}));
  await expect(source.command(JSON.stringify({type:'navigation.retryPresentationReady',generation:'7',serial:1}))).rejects.toBeInstanceOf(AppCommandDetachedError);
  expect(port.getSnapshot).not.toHaveBeenCalled();expect(port.getSnapshotForHost).not.toHaveBeenCalled();
});


test('normal presentation and Ready carry the original source authority revision instead of adopting a newer cache',async()=>{
  const source=androidRuntimeForHost('source');
  port.getSnapshotForHost!.mockResolvedValue(JSON.stringify({...packet('source'),security:{unavailable:false,readRevision:2}}));
  await source.getSnapshot();
  await source.command(JSON.stringify({type:'navigation.presentation',serial:2,surface:'appSettings',readRevision:1}));
  await source.command(JSON.stringify({type:'navigation.presentationReady',serial:2,readRevision:1}));
  expect(port.command.mock.calls.map(([raw])=>JSON.parse(raw))).toEqual([
    expect.objectContaining({type:'navigation.presentation',serial:2,readRevision:'1',generation:'7',nativeHostID:'source'}),
    expect.objectContaining({type:'navigation.presentationReady',serial:2,readRevision:'1',generation:'7',nativeHostID:'source'}),
  ]);
});


test('a regular command reuses its actual native published snapshot without a second private read',async()=>{
  const source=androidRuntimeForHost('source');await source.getSnapshot();
  const published={...packet('source',11),preferences:{appearance:'dark',look:'original',matchTextSize:true,textSize:3,haptics:true}};
  port.command.mockResolvedValueOnce(JSON.stringify({status:'completed',value:'saved',snapshot:published}));
  port.getSnapshotForHost!.mockRejectedValue(new Error('Second read must not run'));
  const result=JSON.parse(await source.command(JSON.stringify({type:'settings.set',key:'haptics',value:true})));
  expect(result.result).toBe('saved');expect(result.snapshot.revision).toBe(11);expect(result.snapshot.session.haptics).toBe(true);
  expect(port.getSnapshotForHost).toHaveBeenCalledTimes(1);
});

test('the actual command projection stays exact when a newer event has already advanced the cache and real AppStore',async()=>{
  const runtime=androidRuntimeForHost('source'),store=new AppStore(runtime),stop=store.connect();
  try {
    await store.refresh();const reads=port.getSnapshotForHost!.mock.calls.length;
    const current={...packet('source',12),filters:{...packet('source').filters,rows:[{...productFixture.filters.rows[0]!,name:'Newer actual state'}]}};
    const reply={...packet('source',11),filters:{...packet('source').filters,rows:[{...productFixture.filters.rows[0]!,name:'Command actual state'}]}};
    port.command.mockImplementation(async()=>{
      DeviceEventEmitter.emit('lavaAppSnapshot',JSON.stringify(current));
      return JSON.stringify({status:'completed',value:'committed',snapshot:reply});
    });
    const value=await store.command({type:'settings.set',key:'haptics',value:true});
    expect(value).toBe('committed');expect(store.getSnapshot().snapshot?.revision).toBe(12);
    expect(store.getSnapshot().snapshot?.filters[0]?.name).toBe('Newer actual state');
    const exact=JSON.parse(await runtime.command(JSON.stringify({type:'settings.set',key:'haptics',value:true})));
    expect(exact.snapshot.revision).toBe(11);expect(exact.snapshot.filters[0].name).toBe('Command actual state');
    expect(port.getSnapshotForHost).toHaveBeenCalledTimes(reads); // Both commands reuse their exact publication; no additional read.
  }finally{stop();}
});

test.each(['foreign','malformed'] as const)('an invalid %s published command receipt is rejected without a fallback read',async(kind)=>{
  const source=androidRuntimeForHost('source');await source.getSnapshot();
  port.command.mockResolvedValueOnce(JSON.stringify({status:'completed',value:'must not deliver',snapshot:kind==='foreign'?packet('successor',11):{schema:1}}));
  await expect(source.command(JSON.stringify({type:'settings.set',key:'haptics',value:true}))).rejects.toThrow();
  expect(port.getSnapshotForHost).toHaveBeenCalledTimes(1);
});


test.each(['completed','cancelled','rejected'] as const)('unavailable command projection preserves the original %s outcome without a second native read',async(status)=>{
  const source=androidRuntimeForHost('source');await source.getSnapshot();
  port.command.mockResolvedValueOnce(JSON.stringify({status,value:status==='completed'?'created-owner':false,reason:status==='rejected'?'library-invalid-name':undefined,projectionFailure:'unavailable'}));
  port.getSnapshotForHost!.mockRejectedValue(new Error('No fallback read is allowed'));
  const result=source.command(JSON.stringify({type:'library.form',form:'create'}));
  if(status==='rejected')await expect(result).rejects.toThrow('Choose an unused filter name.');
  else expect(JSON.parse(await result)).toEqual({result:status==='completed'?'created-owner':false,projectionFailure:'unavailable'});
  expect(port.getSnapshotForHost).toHaveBeenCalledTimes(1);
});

test.each(['completed','cancelled','rejected'] as const)('stale-host projection detaches the old caller after native %s without reading a successor',async(status)=>{
  const source=androidRuntimeForHost('source');await source.getSnapshot();
  port.command.mockResolvedValueOnce(JSON.stringify({status,value:'old result',reason:status==='rejected'?'library-invalid-name':undefined,projectionFailure:'stale-host'}));
  port.getSnapshotForHost!.mockResolvedValue(JSON.stringify(packet('successor',50,'8')));
  await expect(source.command(JSON.stringify({type:'library.form',form:'create'}))).rejects.toBeInstanceOf(AppCommandDetachedError);
  expect(port.getSnapshotForHost).toHaveBeenCalledTimes(1);
});


test.each(['unknown','contradictory'] as const)('a %s publication failure envelope refuses data without a fallback read',async(kind)=>{
  const source=androidRuntimeForHost('source');await source.getSnapshot();
  port.command.mockResolvedValueOnce(JSON.stringify({status:'completed',value:'saved',projectionFailure:kind==='unknown'?'untrusted':'unavailable',...(kind==='contradictory'?{snapshot:packet('successor',50,'8')}:{})}));
  await expect(source.command(JSON.stringify({type:'library.form',form:'create'}))).rejects.toThrow('Invalid command publication receipt.');
  expect(port.getSnapshotForHost).toHaveBeenCalledTimes(1);
});

test('an unavailable query projection withholds its completed private values and revokes the real Store until an explicit fresh read',async()=>{
  const runtime=androidRuntimeForHost('source'),store=new AppStore(runtime),disconnect=store.connect();
  try{
    await store.refresh();const reads=port.getSnapshotForHost!.mock.calls.length;
    port.command.mockResolvedValueOnce(JSON.stringify({status:'completed',value:{privateRows:['undeliverable']},projectionFailure:'unavailable'}));
    await expect(store.command({type:'domains.query',history:false,decision:'blocked',search:''})).rejects.toThrow('Read access changed.');
    expect(store.getPresentationAuthority()).toBe(false);expect(store.getPresentationHydration().required).toBe(true);
    expect(store.getSnapshot().error).toBe('Lava could not refresh its state. Try refreshing again.');
    expect(port.getSnapshotForHost).toHaveBeenCalledTimes(reads);expect(port.command).toHaveBeenCalledTimes(1);
    port.getSnapshotForHost!.mockResolvedValueOnce(JSON.stringify(packet('source',11)));await store.refresh();
    expect(store.getPresentationAuthority()).toBe(true);expect(port.command).toHaveBeenCalledTimes(1);
  }finally{disconnect();}
});


test('exact VPN route retirement is read-free and preserves the original graph despite a newer cached frame',async()=>{
  const source=androidRuntimeForHost('source');await source.getSnapshot();
  const unsubscribe=source.onSnapshot(()=>{});
  DeviceEventEmitter.emit('lavaAppSnapshot',JSON.stringify(packet('source',20,'8')));
  const store=new AppStore(source);port.getSnapshotForHost!.mockClear();port.getSnapshot.mockClear();
  port.command.mockResolvedValue(JSON.stringify({status:'completed',value:null}));
  const before=store.getSnapshot();await store.retireVpnDraft({id:'original-parent',retirementGeneration:'7',nativeHostID:'source',retirementLease:'11111111-1111-4111-8111-111111111111'},Promise.reject(new Error('Original Cancel failed.')));
  expect(port.command).toHaveBeenCalledTimes(1);
  expect(JSON.parse(port.command.mock.calls[0]![0])).toEqual({schema:1,type:'vpn.retire',id:'original-parent',retirementGeneration:'7',nativeHostID:'source',retirementLease:'11111111-1111-4111-8111-111111111111'});
  expect(port.getSnapshotForHost).not.toHaveBeenCalled();expect(port.getSnapshot).not.toHaveBeenCalled();
  expect(store.getSnapshot()).toBe(before);unsubscribe.remove();
});

test.each([
  {type:'vpn.retire',id:'original-parent',retirementGeneration:'7',nativeHostID:'other',retirementLease:'11111111-1111-4111-8111-111111111111'},
  {type:'vpn.retire',id:'original-parent',retirementGeneration:'7',nativeHostID:'source',retirementLease:'11111111-1111-4111-8111-111111111111',value:'private'},
  {type:'vpn.retire',id:'',retirementGeneration:'7',nativeHostID:'source',retirementLease:'11111111-1111-4111-8111-111111111111'},
  {type:'vpn.retire',id:'original-parent',retirementGeneration:'07',nativeHostID:'source',retirementLease:'11111111-1111-4111-8111-111111111111'},
  {type:'vpn.retire',id:'original-parent',retirementGeneration:'9223372036854775808',nativeHostID:'source',retirementLease:'11111111-1111-4111-8111-111111111111'},
  {type:'vpn.retire',id:'original-parent',retirementGeneration:7,nativeHostID:'source',retirementLease:'11111111-1111-4111-8111-111111111111'},
])('malformed or foreign VPN retirement cannot read or reach native: %j',async request=>{
  const source=androidRuntimeForHost('source');
  await expect(source.command(JSON.stringify(request))).rejects.toThrow('Invalid VPN retirement receipt.');
  expect(port.command).not.toHaveBeenCalled();expect(port.getSnapshotForHost).not.toHaveBeenCalled();expect(port.getSnapshot).not.toHaveBeenCalled();
});

test('unbound, unknown and native stale/successor VPN retirement all fail closed without read/publication',async()=>{
  await expect(androidRuntime.command(JSON.stringify({type:'vpn.retire',id:'old',retirementGeneration:'7',nativeHostID:'source',retirementLease:'11111111-1111-4111-8111-111111111111'}))).rejects.toThrow('Invalid VPN retirement receipt.');
  const source=androidRuntimeForHost('source');
  await expect(source.command(JSON.stringify({type:'vpn.retireUnknown'}))).rejects.toThrow();
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'stale-vpn-retirement'}));
  await expect(source.command(JSON.stringify({type:'vpn.retire',id:'successor',retirementGeneration:'8',nativeHostID:'source',retirementLease:'11111111-1111-4111-8111-111111111111'}))).rejects.toThrow('no longer current');
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'stale-host'}));
  await expect(source.command(JSON.stringify({type:'vpn.retire',id:'old',retirementGeneration:'7',nativeHostID:'source',retirementLease:'11111111-1111-4111-8111-111111111111'}))).rejects.toBeInstanceOf(AppCommandDetachedError);
  expect(port.getSnapshotForHost).not.toHaveBeenCalled();expect(port.getSnapshot).not.toHaveBeenCalled();
});

test('a VPN retirement reply cannot smuggle a snapshot, result value or projection failure',async()=>{
  const source=androidRuntimeForHost('source');
  for(const reply of [{status:'completed',snapshot:packet('source')},{status:'completed',value:true},{status:'completed',projectionFailure:'unavailable'},{status:'completed',reason:'stale-vpn-retirement'}]){
    port.command.mockResolvedValueOnce(JSON.stringify(reply));
    await expect(source.command(JSON.stringify({type:'vpn.retire',id:'old',retirementGeneration:'7',nativeHostID:'source',retirementLease:'11111111-1111-4111-8111-111111111111'}))).rejects.toThrow('Invalid VPN retirement receipt.');
  }
  expect(port.getSnapshotForHost).not.toHaveBeenCalled();expect(port.getSnapshot).not.toHaveBeenCalled();
});


test('retirement freezes the original tuple before awaiting the captured Cancel outcome',async()=>{
  const source=androidRuntimeForHost('source'),store=new AppStore(source),after=deferred<string>();
  const tuple={id:'original-parent',retirementGeneration:'7',nativeHostID:'source',retirementLease:'11111111-1111-4111-8111-111111111111'};
  const retirement=store.retireVpnDraft(tuple,after.promise);
  tuple.id='successor';tuple.retirementGeneration='8';tuple.nativeHostID='other';tuple.retirementLease='22222222-2222-4222-8222-222222222222';
  expect(port.command).not.toHaveBeenCalled();
  after.resolve('Original outcome');await retirement;
  expect(JSON.parse(port.command.mock.calls[0]![0])).toEqual({schema:1,type:'vpn.retire',id:'original-parent',retirementGeneration:'7',nativeHostID:'source',retirementLease:'11111111-1111-4111-8111-111111111111'});
  expect(port.getSnapshotForHost).not.toHaveBeenCalled();expect(port.getSnapshot).not.toHaveBeenCalled();
});


test('generic app.command cannot bypass the captured cancellation outcome retirement handoff',async()=>{
  const source=androidRuntimeForHost('source'),store=new AppStore(source);
  await expect(store.command({type:'vpn.retire',id:'old',retirementGeneration:'7',nativeHostID:'source',retirementLease:'11111111-1111-4111-8111-111111111111'})).rejects.toThrow('requires its captured cancellation outcome');
  expect(port.command).not.toHaveBeenCalled();expect(port.getSnapshotForHost).not.toHaveBeenCalled();expect(port.getSnapshot).not.toHaveBeenCalled();
});


test.each([undefined,null,[],{},7,'guessed','11111111-1111-4111-8111-11111111111z'])('v2 a missing or malformed native retirement lease is rejected without private reads: %j',async retirementLease=>{
  const source=androidRuntimeForHost('source');
  await expect(source.command(JSON.stringify({type:'vpn.retire',id:'old',retirementGeneration:'7',nativeHostID:'source',retirementLease}))).rejects.toThrow('Invalid VPN retirement receipt.');
  expect(port.command).not.toHaveBeenCalled();expect(port.getSnapshotForHost).not.toHaveBeenCalled();expect(port.getSnapshot).not.toHaveBeenCalled();
});

test.each(['0','9223372036854775807'])('v2 canonical Long bound %s preserves the full issued tuple without a snapshot overlay',async retirementGeneration=>{
  const source=androidRuntimeForHost('source');
  const receipt={id:'old',retirementGeneration,nativeHostID:'source',retirementLease:'11111111-1111-4111-8111-111111111111'};
  expect(JSON.parse(await source.command(JSON.stringify({type:'vpn.retire',...receipt})))).toEqual({result:null});
  expect(JSON.parse(port.command.mock.calls[0]![0])).toEqual({schema:1,type:'vpn.retire',...receipt});
  expect(port.getSnapshotForHost).not.toHaveBeenCalled();expect(port.getSnapshot).not.toHaveBeenCalled();
});

 test.each(['activity.share','activity.card.query'])('%s failures use Activity copy and preserve cancellation identity',async type=>{
  const source=androidRuntimeForHost('source');await source.getSnapshot();
  for(const reason of ['activity-chart-unavailable','activity-counts-unavailable','share-card-unavailable','share-card-not-ready','stale-share']){
    port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason}));
    await expect(source.command(JSON.stringify({type,token:'card',start:1000,end:1100,hourly:true,chart:'total',period:'today'})))
      .rejects.toMatchObject({message:expect.stringContaining('Activity'),code:reason});
  }
});
test('import permission refusal preserves its native reason for Settings recovery',async()=>{
  const source=androidRuntimeForHost('source');await source.getSnapshot();
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'share-permission-denied'}));
  await expect(source.command(JSON.stringify({type:'native.flow',flow:'importScan',visitID:'visit'})))
    .rejects.toMatchObject({code:'share-permission-denied'});
});

test('public security Retry binds the original host without reading private data or authorizing a page',async()=>{
  const source=androidRuntimeForHost('retry-host');
  port.getSnapshotForHost!.mockRejectedValue(new Error('Security storage unavailable'));
  await expect(source.getSnapshot()).rejects.toThrow('Security storage unavailable');
  port.getSnapshotForHost!.mockClear();
  expect(JSON.parse(await source.command(JSON.stringify({type:'presentation.retry'})))).toEqual({result:null});
  expect(port.getSnapshotForHost).not.toHaveBeenCalled();
  expect(port.command.mock.calls.map(([raw])=>JSON.parse(raw))).toEqual([
    {schema:1,type:'presentation.retry',nativeHostID:'retry-host'},
  ]);
  port.command.mockResolvedValueOnce(JSON.stringify({status:'rejected',reason:'stale-host'}));
  await expect(source.command(JSON.stringify({type:'presentation.retry'}))).rejects.toBeInstanceOf(AppCommandDetachedError);
});
