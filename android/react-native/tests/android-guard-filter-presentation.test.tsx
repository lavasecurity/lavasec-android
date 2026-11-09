import React from 'react';
import {NativeModules,StyleSheet} from 'react-native';
import {render,fireEvent} from '@testing-library/react-native';
import {productFixture} from './productFixture';
import {parseProductSnapshot,type ProductSnapshot} from '../src/productContract';
import {LavaActionButton} from '../canonical/src/components.android';
import {colors} from '../canonical/src/colors';
import {materialSymbols} from '../canonical/android/material-symbols';
NativeModules.NativeLavaApp={getSnapshot:jest.fn(),command:jest.fn()};
const {projectAndroidSnapshot}=require('../canonical/android/runtime') as typeof import('../canonical/android/runtime');
jest.mock('@react-navigation/native',()=>({useIsFocused:()=>true,useNavigation:()=>({}),useRoute:()=>({})}));
const detail:NonNullable<ProductSnapshot['filterSession']>={id:'filter1',name:'Balanced',editing:false,
  lists:['hagezi','empty','unknown','custom'],savedLists:['hagezi'],blocked:['abc.com'],allowed:[],savedBlocked:['abc.com'],savedAllowed:[],
  names:{hagezi:'HaGeZi',empty:'Empty',unknown:'Unknown',custom:'Custom'},ruleCounts:{hagezi:53174,empty:0},customLists:['custom']};
const wire:ProductSnapshot={...productFixture,filterSession:detail};

test('real blocklist counts include valid zero, unknown custom counts remain pending, and covered details expose no counts',()=>{
  const projected=projectAndroidSnapshot(wire);
  expect(projected.blocklistMetadata).toMatchObject({hagezi:'53,174 rules',empty:'0 rules',unknown:'Not updated yet',custom:'Waiting for update · Custom blocklist'});
  expect(projectAndroidSnapshot({...wire,available:false}).blocklistMetadata).not.toHaveProperty('hagezi');
  expect(projectAndroidSnapshot({...wire,foreground:false}).blocklistMetadata).not.toHaveProperty('hagezi');
  expect(projectAndroidSnapshot({...wire,filters:{...wire.filters,locked:true}}).blocklistMetadata).not.toHaveProperty('hagezi');
  for(const count of [-1,1.5,'10',Number.MAX_SAFE_INTEGER+1])expect(()=>parseProductSnapshot(JSON.stringify({...wire,filterSession:{...detail,ruleCounts:{hagezi:count}}}))).toThrow();
  expect(parseProductSnapshot(JSON.stringify(wire)).filterSession?.ruleCounts).toEqual({hagezi:53174,empty:0});
});

test.each([
  [{},'Filter up to date',false],
  [{fresh:false},'Update recommended',true],
  [{fresh:true,lastStatus:'offline'},'Update recommended',true],
  [{fresh:true,sourceRepairRequired:true},'Update recommended',true],
  [{refreshing:true},'Filter up to date',false],
] as const)('filter detail follows catalog freshness %j',(patch,title,warning)=>{
  const snapshot=projectAndroidSnapshot({...wire,catalog:{...productFixture.catalog!,...patch}});
  expect(snapshot.filterStatus).toMatchObject({title,warning});
  expect(materialSymbols[snapshot.filterStatus!.icon]).toBeDefined();
});

test('inactive and empty detail statuses refer to the opened filter, not the active row elsewhere',()=>{
  const inactive={...wire,filters:{...wire.filters,rows:[...wire.filters.rows,{...wire.filters.rows[0]!,id:'other',active:false}]},filterSession:{...detail,id:'other'}};
  expect(projectAndroidSnapshot(inactive).filterStatus).toMatchObject({title:'Not in effect',icon:'pause.circle',warning:false});
  const empty=projectAndroidSnapshot({...wire,filterSession:{...detail,lists:[],blocked:[]}}).filterStatus!;
  expect(empty).toMatchObject({title:'Blocks nothing — not protected',warning:true});
  expect(materialSymbols[empty.icon]).toBeDefined();
});

test('busy uses native command truth and never erases hard unavailability',()=>{
  const busy={...wire,protection:{...wire.protection,disabled:true,commandInFlight:true}};
  expect(projectAndroidSnapshot(busy).protection).toMatchObject({disabled:true,configuring:true});
  expect(projectAndroidSnapshot({...busy,available:false}).protection).toMatchObject({disabled:true,configuring:false});
  expect(projectAndroidSnapshot({...wire,protection:{...wire.protection,disabled:true}}).protection.configuring).toBe(false);
});

test.each(['starting','stopping'])('the %s transition retains the shared stop label without enabling a native action',lifecycle=>{
  const transition={...wire,protection:{...wire.protection,lifecycle,action:null,disabled:true}};
  expect(projectAndroidSnapshot(transition).protection).toMatchObject({action:'Turn off protection',disabled:true});
  expect(projectAndroidSnapshot({...transition,available:false}).protection).toMatchObject({action:'Unavailable',disabled:true});
  expect(projectAndroidSnapshot({...transition,foreground:false}).protection).toMatchObject({action:'Unavailable',disabled:true});
});

test.each([
  ['starting','unavailable','waking','unresolved'],['protected','healthy','awake','affirmed'],
  ['protected','internet_unverified','concerned','unknown'],['protected','rechecking','retrying','recovery'],
  ['protected','unavailable','waking','unknown'],['protected','encrypted_fallback','concerned','recovery'],
  ['protected','recovering','retrying','recovery'],['reconnecting','unavailable','retrying','recovery'],
  ['paused','healthy','paused','paused'],['stopping','healthy','sleeping','stopping'],
])('mascot and panel share the %s / %s presentation',(lifecycle,connectivity,mood,materialIntent)=>{
  expect(projectAndroidSnapshot({...wire,protection:{...wire.protection,lifecycle,connectivity}}).protection).toMatchObject({mood,materialIntent});
});

test('unverified internet and wake rechecking have distinct status without changing the native action',()=>{
  for(const [connectivity,title,subtitle] of [
    ['internet_unverified','Internet unverified','Local filtering is active. Internet access on this network has not been verified.'],
    ['rechecking','Checking protection','Lava is checking protection after waking.'],
  ]) {
    const protection={...wire.protection,lifecycle:'protected',connectivity:connectivity!,action:'turnOff' as const};
    expect(projectAndroidSnapshot({...wire,protection}).protection).toMatchObject({title,subtitle,action:'Turn off protection'});
    expect(projectAndroidSnapshot({...wire,protection:{...protection,lifecycle:'paused'}}).protection.title).toBe('Protection is paused');
  }
});

test('a pending action retains its accepted paint and rejects a second tap; disabled errors still dim',()=>{
  const press=jest.fn();const screen=render(<LavaActionButton title="Turn on protection" tone="affirmative" busy={false} onPress={press}/>);
  const background=()=>StyleSheet.flatten(screen.getByRole('button').props.style).backgroundColor;
  const resting=background();
  screen.rerender(<LavaActionButton title="Turn on protection" tone="affirmative" disabled busy onPress={press}/>);
  expect(background()).toEqual(resting);expect(background()).toEqual(colors.safeControlGreen);
  expect(screen.getByRole('button').props.accessibilityState).toEqual({disabled:true,busy:true});
  fireEvent.press(screen.getByRole('button'));expect(press).not.toHaveBeenCalled();
  screen.rerender(<LavaActionButton title="Turn on protection" disabled busy={false} onPress={press}/>);
  expect(background()).toEqual(colors.disabledSurface);
});

test('a new empty filter uses the shared empty status before it is saved',()=>{
  const snapshot=projectAndroidSnapshot({...wire,newFilter:{id:'new',name:'New',emoji:'',empty:true,blockedDomainCount:0,allowedExceptionCount:0,lists:[]},
    filterSession:{...detail,id:'new',lists:[],blocked:[]}});
  expect(snapshot.filterStatus).toMatchObject({title:'Blocks nothing',warning:true});
});
