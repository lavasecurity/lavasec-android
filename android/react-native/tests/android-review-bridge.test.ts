import {NativeModules} from 'react-native';
import NativeReview from '../canonical/specs/NativeLavaReview.android';
import {productFixture} from './productFixture';

const queryPort={getSnapshotForHost:jest.fn(),command:jest.fn()};

afterEach(()=>{jest.useRealTimers();delete NativeModules.NativeLavaApp;});

test('initial Today range queries only the current local calendar day',async()=>{
  jest.useFakeTimers().setSystemTime(new Date(2026,9,3,12,30));
  const range=await NativeReview.getActivityDates();
  expect(new Date(range.start*1000)).toEqual(new Date(2026,9,3));
  expect(new Date(range.end*1000)).toEqual(new Date(2026,9,3,23,59,59));
  expect(Number.isInteger(range.start)).toBe(true);
  expect(Number.isInteger(range.end)).toBe(true);
  expect(range.includesToday).toBe(true);
});

test.each([
  ['today',1,new Date(2026,0,1,12,30)],
  ['week',7,new Date(2024,2,10,12,30)],
  ['month',30,new Date(2024,2,1,12,30)],
  ['fortnight',14,new Date(2026,10,1,12,30)],
] as const)('%s emits whole-second local calendar bounds through both native query commands',async(preset,days,now)=>{
  jest.useFakeTimers().setSystemTime(now);
  const range=await NativeReview.getActivityDatePreset(preset);
  const expectedStart=new Date(now);expectedStart.setHours(0,0,0,0);expectedStart.setDate(expectedStart.getDate()-(days-1));
  const nextDay=new Date(now);nextDay.setHours(0,0,0,0);nextDay.setDate(nextDay.getDate()+1);
  expect(range.start).toBe(expectedStart.getTime()/1000);
  expect(range.end).toBe(nextDay.getTime()/1000-1);
  expect(Number.isInteger(range.start)).toBe(true);expect(Number.isInteger(range.end)).toBe(true);
  expect(range.includesToday).toBe(true);

  // Use the shipping host-bound runtime, so the bounds observed here are the
  // actual native wire values inherited by Activity and Top Domains.
  const nativeHostID=`date-query-${preset}`;
  const snapshot=JSON.stringify({...productFixture,nativeHostID,generation:'7',revision:10});
  queryPort.getSnapshotForHost.mockReset().mockResolvedValue(snapshot);
  const command=queryPort.command.mockReset().mockResolvedValue(JSON.stringify({status:'completed'}));
  NativeModules.NativeLavaApp=queryPort;
  const {androidRuntimeForHost}=require('../canonical/android/runtime') as typeof import('../canonical/android/runtime');
  const runtime=androidRuntimeForHost(nativeHostID);
  await runtime.getSnapshot();
  await runtime.command(JSON.stringify({type:'activity.query',start:range.start,end:range.end,hourly:false}));
  await runtime.command(JSON.stringify({type:'domains.query',start:range.start,end:range.end,history:false,decision:'All',search:'example.test',limit:26}));
  expect(command).toHaveBeenCalledTimes(2);
  for(const [payload] of command.mock.calls){
    const wire=JSON.parse(payload);
    expect(wire).toMatchObject({schema:1,generation:'7',nativeHostID,start:expectedStart.getTime()/1000,end:nextDay.getTime()/1000-1});
    expect(Number.isInteger(wire.start)).toBe(true);expect(Number.isInteger(wire.end)).toBe(true);
  }
  expect(command.mock.calls.map(([payload])=>JSON.parse(payload).type)).toEqual(['activity.query','domains.query']);
});

test('Legal uses the packaged native catalog and native picker cancellation remains null',async()=>{
  const notices=JSON.stringify({disclaimer:'Public notices',sections:[{title:'Runtime',notices:[{displayName:'Library',noticeText:'License'}]}]});
  const pick=jest.fn().mockResolvedValueOnce(null).mockResolvedValueOnce({start:10,end:20,label:'Accepted',includesToday:false});
  NativeModules.NativeLavaApp={getLegalNotices:jest.fn(()=>notices),pickActivityDates:pick};
  expect(JSON.parse(NativeReview.getLegalNotices()).sections[0].notices[0].noticeText).toBe('License');
  await expect(NativeReview.pickActivityDates(1,2)).resolves.toBeNull();
  await expect(NativeReview.pickActivityDates(3,4)).resolves.toMatchObject({start:10,end:20});
  expect(pick.mock.calls).toEqual([[1,2],[3,4]]);
});

test('narration waits for native completion and forwards stop without inventing success',async()=>{
  let complete:(value:boolean)=>void=()=>{};
  const speak=jest.fn(()=>new Promise<boolean>(resolve=>{complete=resolve;}));
  const stop=jest.fn();
  NativeModules.NativeLavaApp={speakDemo:speak,stopDemo:stop};
  let settled=false;
  const speech=NativeReview.speakDemo('A public Explore caption.','zh-Hant').then(value=>{settled=true;return value;});
  await Promise.resolve();
  expect(settled).toBe(false);
  expect(speak).toHaveBeenCalledWith('A public Explore caption.','zh-Hant');
  NativeReview.stopDemo();
  expect(stop).toHaveBeenCalledTimes(1);
  complete(false);
  await expect(speech).resolves.toBe(false);
});

test('only native successful narration completion reports true',async()=>{
  NativeModules.NativeLavaApp={speakDemo:jest.fn().mockResolvedValue(true),stopDemo:jest.fn()};
  await expect(NativeReview.speakDemo('A public Explore caption.','en')).resolves.toBe(true);
});

test('a missing native speech capability keeps the visual lesson usable',async()=>{
  delete NativeModules.NativeLavaApp;
  await expect(NativeReview.speakDemo('A public Explore caption.','en')).resolves.toBe(false);
  expect(()=>NativeReview.stopDemo()).not.toThrow();
});
