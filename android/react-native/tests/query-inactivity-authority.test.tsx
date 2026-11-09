import {useState} from 'react';
import {Text} from 'react-native';
import {act,screen,waitFor} from '@testing-library/react-native';
import {installLifecycleMock,mountJourney,completed,deferred,type Reply} from './interruptionHarness';
import {useAppQuery} from '../canonical/app/queries';
import {productFixture} from './productFixture';

jest.mock('@react-navigation/native',()=>({useIsFocused:()=>true}));
afterEach(()=>jest.restoreAllMocks());
const offWire={...productFixture,backgroundPrivacyCoverRequired:false};
function ShareQueryStation(){
  const [id,setID]=useState('filter1');
  const query=useAppQuery<{code:string}>({type:'share.query',id},'same-share-page');
  return <>
    <Text testID="share.code">{query.value?.code??'pending'}</Text>
    <Text testID="share.error">{query.error??''}</Text>
    <Text testID="share.refresh" onPress={()=>query.refresh()}>Refresh</Text>
    <Text testID="share.switch" onPress={()=>setID('filter2')}>Switch</Text>
  </>;
}

test('native opt-out retains the painted share frame but cannot accept a late secret or refresh it without current native read authority',async()=>{
  const lifecycle=installLifecycleMock(),late=deferred<Reply>();let reads=0;
  const harness=mountJourney({lifecycle,wire:offWire,render:()=> <ShareQueryStation/>});
  harness.owner.setHandler(command=>{
    if(command.type!=='share.query')return completed();
    ++reads;return reads===1?completed({code:'already-painted'}):reads===2?late.promise:completed({code:'fresh-authorized'});
  });
  try{
    await harness.ready();
    await waitFor(()=>expect(screen.getByTestId('share.code')).toHaveTextContent('already-painted'));
    let refreshing!:Promise<void>;
    act(()=>{refreshing=screen.getByTestId('share.refresh').props.onPress();});
    await waitFor(()=>expect(reads).toBe(2));
    harness.emitAppState('inactive');
    expect(harness.store.getPresentationAuthority()).toBe(false);
    expect(screen.getByTestId('share.code')).toHaveTextContent('already-painted');
    await act(async()=>{await screen.getByTestId('share.refresh').props.onPress();});
    expect(reads).toBe(2);
    await act(async()=>{late.resolve(completed({code:'late-inactive-secret'}));await refreshing;});
    expect(screen.getByTestId('share.code')).toHaveTextContent('already-painted');
    expect(screen.getByTestId('share.error')).toHaveTextContent('');
    // Returning JS to active cannot authorize a query while native still emits
    // only the redacted frame for the retained owner.
    await act(async()=>harness.owner.publish({foreground:false,available:false}));
    harness.emitAppState('active');
    await act(async()=>{await harness.store.refresh();await screen.getByTestId('share.refresh').props.onPress();});
    expect(harness.store.getPresentationAuthority()).toBe(false);expect(reads).toBe(2);
    await act(async()=>harness.owner.publish({foreground:true,available:true}));
    await harness.ready();
    await waitFor(()=>expect(screen.getByTestId('share.code')).toHaveTextContent('fresh-authorized'));
    expect(reads).toBe(3);
  }finally{harness.unmount();}
});

test('changing the exact share target during inactivity cannot reuse a painted secret under the same caller retention key',async()=>{
  const lifecycle=installLifecycleMock();
  const harness=mountJourney({lifecycle,wire:offWire,render:()=> <ShareQueryStation/>});
  harness.owner.setHandler(command=>completed(command.type==='share.query'?{code:`secret-${command.id}`}:undefined));
  try{
    await harness.ready();await waitFor(()=>expect(screen.getByTestId('share.code')).toHaveTextContent('secret-filter1'));
    harness.emitAppState('inactive');
    act(()=>screen.getByTestId('share.switch').props.onPress());
    expect(screen.getByTestId('share.code')).toHaveTextContent('pending');
    await act(async()=>{await screen.getByTestId('share.refresh').props.onPress();});
    expect(harness.count('share.query')).toBe(1);
    harness.emitAppState('active');await harness.ready();
    await waitFor(()=>expect(screen.getByTestId('share.code')).toHaveTextContent('secret-filter2'));
    expect(harness.count('share.query')).toBe(2);
  }finally{harness.unmount();}
});

test('an unlocked Open Lava session keeps painted rows through interruption but retires them at actual background',async()=>{
  const lifecycle=installLifecycleMock(),late=deferred<Reply>();let reads=0;
  const harness=mountJourney({lifecycle,wire:{...productFixture,backgroundPrivacyCoverRequired:true,appPrivacyCoverRequired:false},render:()=> <ShareQueryStation/>});
  harness.owner.setHandler(command=>{
    if(command.type!=='share.query')return completed();
    ++reads;return reads===1?completed({code:'same-session-paint'}):late.promise;
  });
  try {
    await harness.ready();await waitFor(()=>expect(screen.getByTestId('share.code')).toHaveTextContent('same-session-paint'));
    harness.emitAppState('inactive');
    expect(harness.store.getPresentationAuthority()).toBe(false);
    expect(harness.store.getSnapshot().appPrivacyCoverRequired).toBe(false);
    expect(screen.getByTestId('share.code')).toHaveTextContent('same-session-paint');
    await act(async()=>{await screen.getByTestId('share.refresh').props.onPress();});
    expect(reads).toBe(1);
    harness.emitAppState('background');
    expect(harness.store.getSnapshot().appPrivacyCoverRequired).toBe(true);
    await waitFor(()=>expect(screen.getByTestId('share.code')).toHaveTextContent('pending'));
  } finally {harness.unmount();}
});
