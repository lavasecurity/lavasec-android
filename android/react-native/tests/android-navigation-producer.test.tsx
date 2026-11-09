import React,{useLayoutEffect} from 'react';
import {View} from 'react-native';
import {act,render} from '@testing-library/react-native';
import {AndroidNavigationProducer,androidProducerCommandCounters,type AndroidProducerFacts,type AndroidProducerRow} from '../src/androidNavigationProducer';
import type {Spec} from '../canonical/specs/NativeLavaApp';

const facts:AndroidProducerFacts={qaTools:true,snapshotRevision:11,hydrationEpoch:2,hydrationRequired:false,presentationSerial:3,presentationStatus:'accepted',presentationAuthority:true,securityReadRevision:4};
const makeNative=(send:(request:string)=>Promise<string>)=>({getSnapshot:jest.fn(()=>Promise.resolve('snapshot')),command:jest.fn(send),onSnapshot:jest.fn(()=>({remove:jest.fn()}))}) as unknown as Spec;
const flush=(producer:AndroidNavigationProducer,update:Partial<AndroidProducerFacts>={})=>{producer.render();producer.commit({...facts,...update});};

test('observed port keeps every original receiver, request, promise, subscription and call order',async()=>{
 const emitted:AndroidProducerRow[]=[],producer=new AndroidNavigationProducer(row=>emitted.push(row));flush(producer);
 const calls:string[]=[];let finish!:(value:string)=>void;
 const promise=new Promise<string>(resolve=>{finish=resolve;});const snapshot=Promise.resolve('snapshot');const subscription={remove:jest.fn()};
 const native:Spec={
  getSnapshot(){expect(this).toBe(native);calls.push('snapshot');return snapshot;},
  command(request){expect(this).toBe(native);calls.push(request);return promise;},
  onSnapshot(listener){expect(this).toBe(native);expect(listener).toBe(listenerReceived);calls.push('subscribe');return subscription;},
 };
 const listenerReceived=jest.fn(),observed=producer.observe(native);
 expect(observed.getSnapshot()).toBe(snapshot);expect(observed.onSnapshot(listenerReceived)).toBe(subscription);
 const request='{"type":"filter.review","id":"private-filter"}';
 expect(observed.command(request)).toBe(promise);expect(calls).toEqual(['snapshot','subscribe',request]);
 finish('completed');await expect(promise).resolves.toBe('completed');flush(producer,{presentationSerial:4});
 expect(emitted.at(-1)).toMatchObject({commands:1,filterReviewCommands:1,otherCommands:0});
 expect(JSON.stringify(emitted)).not.toContain('private-filter');
});

test('synchronous native throws and asynchronous rejection preserve the original error and promise',async()=>{
 const producer=new AndroidNavigationProducer(()=>{});flush(producer);const original=new Error('native-original');
 const throws=makeNative(()=>{throw original;});
 let caught:unknown;try{producer.observe(throws).command('{"type":"filter.save"}');}catch(error){caught=error;}expect(caught).toBe(original);
 const rejected=Promise.reject(original),rejects=makeNative(()=>rejected);
 const actual=producer.observe(rejects).command('{"type":"filter.apply","review":"private-token"}');expect(actual).toBe(rejected);
 await expect(actual).rejects.toBe(original);expect(throws.command).toHaveBeenCalledTimes(1);expect(rejects.command).toHaveBeenCalledTimes(1);
});

test('QA logging starts only from a committed explicit native capability and parses no command payload',()=>{
 const emit=jest.fn(),producer=new AndroidNavigationProducer(emit),native=makeNative(()=>Promise.resolve('native'));
 const observed=producer.observe(native);flush(producer,{qaTools:false});
 observed.command('{"type":"filter.review","id":"before-QA"}');expect(emit).not.toHaveBeenCalled();
 flush(producer);expect(emit).toHaveBeenCalledTimes(1);
 const parse=jest.spyOn(JSON,'parse').mockImplementation(()=>{throw new Error('diagnostic must not parse a payload');});
 try{observed.command('{"type":"filter.domain","domain":"private-domain","nested":{"type":"filter.apply"}}');
  observed.command('{"type":"unknown-private-value","value":"private-input"}');
 }finally{parse.mockRestore();}
 flush(producer,{presentationSerial:4});expect(emit.mock.calls.at(-1)?.[0]).toMatchObject({commands:2,filterDomainCommands:1,filterApplyCommands:0,otherCommands:1});
 flush(producer,{qaTools:false});observed.command('{"type":"filter.apply"}');flush(producer,{qaTools:false});
 expect(emit).toHaveBeenCalledTimes(2);expect(native.command).toHaveBeenCalledTimes(4);
 expect(JSON.stringify(emit.mock.calls)).not.toMatch(/private-domain|private-input|private-value|before-QA/);
});

test('projection contains only approved bounded scalars even when its caller supplies hostile extra facts',()=>{
 const rows:AndroidProducerRow[]=[],producer=new AndroidNavigationProducer(row=>rows.push(row));
 flush(producer,{snapshotRevision:-1,hydrationEpoch:Number.POSITIVE_INFINITY,presentationSerial:NaN,presentationStatus:'private-owner' as any,securityReadRevision:undefined,
  nativeHostID:'private-host',routeKey:'private-route',domain:'private-domain',payload:'private-payload'} as any);
 expect(rows).toHaveLength(1);const row=rows[0]!;expect(Object.keys(row).sort()).toEqual([...androidProducerCommandCounters,'events','renders','commits','snapshotRevision','hydrationEpoch','presentationSerial','securityReadRevision',
  'snapshotPresent','hydrationRequired','presentationAuthority','securityReadRevisionPresent','presentationStatus'].sort());
 expect(row).toMatchObject({snapshotRevision:0,snapshotPresent:false,hydrationEpoch:0,presentationSerial:0,presentationStatus:'none',securityReadRevision:0,securityReadRevisionPresent:false});
 expect(JSON.stringify(row)).not.toMatch(/private-|host|route|domain|payload/);
});

test('root counters saturate and each observer emits at most 256 committed records',()=>{
 const rows:AndroidProducerRow[]=[],producer=new AndroidNavigationProducer(row=>rows.push(row));flush(producer);
 // Exercise the actual boundary without billions of iterations or a production test-only API.
 const counters=producer as unknown as {renders:number;commits:number;counters:Record<string,number>};
 counters.renders=2147483646;counters.commits=2147483646;counters.counters.commands=2147483646;counters.counters.filterReviewCommands=2147483646;
 const observed=producer.observe(makeNative(()=>Promise.resolve('native')));
 for(let index=0;index<300;index++){observed.command('{"type":"filter.review"}');flush(producer,{presentationSerial:10+index,snapshotRevision:2147483648});}
 expect(rows).toHaveLength(256);expect(rows.at(-1)).toMatchObject({events:256,renders:2147483647,commits:2147483647,commands:2147483647,filterReviewCommands:2147483647,snapshotRevision:2147483647});
 expect(rows.flatMap(row=>Object.values(row).filter(value=>typeof value==='number')).every(value=>value>=0&&value<=2147483647)).toBe(true);
});

test('transparent native commands and diagnostic printing create no additional React commit or action',async()=>{
 const emit=jest.fn(),producer=new AndroidNavigationProducer(emit),send=jest.fn(()=>Promise.resolve('native'));
 const native=makeNative(send),observed=producer.observe(native);let committed=0,rendered=0;
 function Probe({value}:{value:AndroidProducerFacts}){++rendered;producer.render();useLayoutEffect(()=>{++committed;producer.commit(value);});return <View testID="producer.probe"/>;}
 const view=render(<Probe value={facts}/>);const baseline={committed,rendered};
 await act(async()=>{await observed.command('{"type":"navigation.presentation","ownerID":"private-owner"}');await observed.command('{"type":"haptic","controlID":"private-control"}');});
 expect({committed,rendered}).toEqual(baseline);expect(send).toHaveBeenCalledTimes(2);expect(emit).toHaveBeenCalledTimes(1);
 view.rerender(<Probe value={{...facts,presentationSerial:4}}/>);
 expect(committed).toBe(baseline.committed+1);expect(rendered).toBe(baseline.rendered+1);expect(send).toHaveBeenCalledTimes(2);
 expect(emit.mock.calls.at(-1)?.[0]).toMatchObject({commands:2,presentationCommands:1,hapticCommands:1});
 expect(JSON.stringify(emit.mock.calls)).not.toMatch(/private-owner|private-control/);view.unmount();
});


test('a throwing logging sink cannot alter the next native promise or original synchronous error',async()=>{
 const sink=jest.fn((_row:AndroidProducerRow)=>{throw new Error('diagnostic sink unavailable');}),producer=new AndroidNavigationProducer(sink);
 expect(()=>flush(producer)).not.toThrow();expect(sink).toHaveBeenCalledTimes(1);
 const promise=Promise.resolve('original'),original=new Error('native-original');
 const native=makeNative(request=>{if(typeof request!=='string')throw original;return promise;}),observed=producer.observe(native);
 expect(observed.command('{"type":"filter.review","id":"private-filter"}')).toBe(promise);
 const malformed={toString(){throw new Error('must not convert a command payload');}};
 let caught:unknown;try{observed.command(malformed as unknown as string);}catch(error){caught=error;}
 expect(caught).toBe(original);expect(native.command).toHaveBeenNthCalledWith(2,malformed);
 await expect(promise).resolves.toBe('original');expect(()=>flush(producer,{presentationSerial:4})).not.toThrow();
 expect(sink).toHaveBeenCalledTimes(2);expect(sink.mock.calls.at(-1)?.[0]).toMatchObject({events:2,commands:2,filterReviewCommands:1,otherCommands:1});
 expect(native.command).toHaveBeenCalledTimes(2);
});
