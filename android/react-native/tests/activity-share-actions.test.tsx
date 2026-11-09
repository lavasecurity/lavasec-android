import {act,renderHook} from '@testing-library/react-native';
import type {ActivityShareCardContent} from '../canonical/review/ActivityShareCard';
import {useActivityShare} from '../canonical/review/activity-share-actions';

const mockCommand=jest.fn();
const mockRetire=jest.fn().mockResolvedValue(undefined);
const mockApp={command:mockCommand,retireActivityShare:mockRetire};
jest.mock('../canonical/review/ReviewContext',()=>({useReview:()=>({app:mockApp})}));
jest.mock('@react-navigation/native',()=>({useIsFocused:()=>true}));
const dates={start:1000,end:1100,label:'Matching range',includesToday:true};
const content:ActivityShareCardContent={token:'old-custom',payload:'https://lavasecurity.app/',moduleCount:33,
  chart:'total',period:'custom',dateLabel:'January 1, 2026',startLabel:'January 1, 2026',endLabel:'January 1, 2026',
  allowed:2,blocked:1,uptime:'1m',buckets:[]};

test.each(['today','week','month'] as const)('changing Custom to %s with identical dates retires a pending query and a card awaiting capture',async period=>{
  for(const phase of ['query','render']) {
    let resolve!:(value:ActivityShareCardContent)=>void;
    const pending=new Promise<ActivityShareCardContent>(yes=>{resolve=yes;});
    mockCommand.mockReset().mockResolvedValue(null).mockImplementationOnce(()=>pending);
    mockRetire.mockClear();
    const view=renderHook(({selected}:{selected:ActivityShareCardContent['period']})=>useActivityShare({dates,chart:'total',period:selected,loaded:true}),{initialProps:{selected:'custom' as ActivityShareCardContent['period']}});
    act(()=>view.result.current.exportChart());
    expect(view.result.current.busy).toBe(true);
    if(phase==='render')await act(async()=>resolve(content));
    view.rerender({selected:period});
    expect(view.result.current.busy).toBe(false);
    expect(view.result.current.content).toBeUndefined();
    if(phase==='query')await act(async()=>resolve(content));
    expect(mockCommand).not.toHaveBeenCalledWith(expect.objectContaining({type:'activity.share'}));
    expect(mockRetire).toHaveBeenCalledTimes(1);
    expect(mockRetire).toHaveBeenCalledWith('old-custom');
    // Retirement must also release the pending guard for a fresh selection.
    mockCommand.mockResolvedValueOnce({...content,token:'fresh-preset',period});
    await act(async()=>view.result.current.exportChart());
    expect(mockCommand).toHaveBeenLastCalledWith({type:'activity.card.query',start:dates.start,end:dates.end,hourly:period==='today',chart:'total',period});
    expect(view.result.current.content?.token).toBe('fresh-preset');
    await act(async()=>view.result.current.ready(true));
    expect(mockCommand).toHaveBeenCalledWith({type:'activity.share',token:'fresh-preset'});
    view.unmount();
  }
});

test('unmount retires a late native query without sharing or painting its private result',async()=>{
  let resolve!:(value:ActivityShareCardContent)=>void;
  mockCommand.mockReset().mockImplementationOnce(()=>new Promise(yes=>{resolve=yes;}));
  mockRetire.mockClear();
  const view=renderHook(()=>useActivityShare({dates,chart:'total',period:'today',loaded:true}));
  act(()=>view.result.current.exportChart());view.unmount();
  await act(async()=>resolve(content));
  expect(mockRetire).toHaveBeenCalledWith(content.token);
  expect(mockCommand).toHaveBeenCalledTimes(1);
});

test('a double export produces one query and capture only after committed readiness',async()=>{
  mockCommand.mockReset().mockResolvedValueOnce(content).mockResolvedValue(undefined);
  mockRetire.mockClear();
  const view=renderHook(()=>useActivityShare({dates,chart:'total',period:'today',loaded:true}));
  await act(async()=>{view.result.current.exportChart();view.result.current.exportChart();});
  expect(mockCommand).toHaveBeenCalledTimes(1);
  await act(async()=>view.result.current.ready(true));
  expect(mockCommand).toHaveBeenCalledTimes(2);
  expect(mockCommand).toHaveBeenLastCalledWith({type:'activity.share',token:content.token});
  expect(mockRetire).toHaveBeenCalledWith(content.token);
  expect(view.result.current.content).toBeUndefined();view.unmount();
});

 test('a stale native export is silent even when its human message is localized',async()=>{
  mockCommand.mockReset().mockRejectedValueOnce(Object.assign(new Error('La selección cambió.'),{code:'stale-share'}));
  const view=renderHook(()=>useActivityShare({dates,chart:'total',period:'today',loaded:true}));
  await act(async()=>view.result.current.exportChart());
  expect(view.result.current.error).toBeUndefined();
  expect(view.result.current.busy).toBe(false);view.unmount();
});


test.each(['query','capture'])('a %s refusal remains visible until the selection changes',async phase=>{
  const reason=new Error('The Activity chart is still being prepared. Try sharing again.');
  mockCommand.mockReset();mockRetire.mockClear();
  if(phase==='query')mockCommand.mockRejectedValueOnce(reason);
  else mockCommand.mockResolvedValueOnce(content).mockRejectedValueOnce(reason);
  const view=renderHook(({selected}:{selected:ActivityShareCardContent['period']})=>useActivityShare({dates,chart:'total',period:selected,loaded:true}),{initialProps:{selected:'custom' as ActivityShareCardContent['period']}});
  await act(async()=>view.result.current.exportChart());
  if(phase==='capture')await act(async()=>view.result.current.ready(true));
  expect(view.result.current.error).toBe(reason.message);
  expect(view.result.current.busy).toBe(false);
  expect(view.result.current.content).toBeUndefined();
  view.rerender({selected:'week'});
  expect(view.result.current.error).toBeUndefined();
  view.unmount();
});
