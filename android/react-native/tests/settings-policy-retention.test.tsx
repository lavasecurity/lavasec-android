import React,{useState} from 'react';
import {AppState,Button,Text,View} from 'react-native';
import {fireEvent,render,screen} from '@testing-library/react-native';
import type {AppSnapshot} from '../canonical/app/contract';
import type {AppStore} from '../canonical/app/store';
import {mayInteractWithPresentation,readPrivacyScope} from '../canonical/app/read-cache';
import {LiveRenderBoundary,ReviewContext} from '../canonical/review/ReviewContext';
import {initialPreviewDraft} from '../canonical/review/preview-model';
import {initialSession} from '../canonical/review/session';

let mounted:number;
function SettingsBody(){
  const [pending,setPending]=useState(()=>{mounted++;return false;});
  return <View testID="settings.body"><Button title="Begin native change" onPress={()=>setPending(true)}/><Text testID="settings.pending">{pending?'Pending':'Idle'}</Text></View>;
}
function harness(retireOnPolicyChange:boolean){
  let authorized=true;
  let snapshot={generation:'g1',backgroundPrivacyCoverRequired:false,security:{ownerRevision:'owner1',unavailable:false,readRevision:1},session:{passcode:true,protectedActions:{'View Activities':false}}} as unknown as AppSnapshot;
  let state={snapshot};const hydration={epoch:1,required:false};
  const app={subscribe:()=>()=>{},getSnapshot:()=>state,getPresentationAuthority:()=>authorized,getPresentationHydration:()=>hydration} as unknown as AppStore;
  const value=()=>({app,live:snapshot,appearance:{} as never,look:'lava',setLook:()=>{},draft:initialPreviewDraft(),setDraft:()=>{},savedDraft:initialPreviewDraft(),setSavedDraft:()=>{},session:initialSession(),setSession:()=>{}});
  const tree=()=><ReviewContext.Provider value={value()}><LiveRenderBoundary component={SettingsBody} retireOnPolicyChange={retireOnPolicyChange}/></ReviewContext.Provider>;
  const view=render(tree());
  return {app,view,policy:()=>{const before=readPrivacyScope(snapshot);snapshot={...snapshot,security:{...snapshot.security,readRevision:2},session:{...snapshot.session,protectedActions:{...snapshot.session.protectedActions,'View Activities':true}}};state={snapshot};view.rerender(tree());expect(readPrivacyScope(snapshot)).not.toBe(before);},authority:(value:boolean)=>{authorized=value;view.rerender(tree());},owner:()=>{snapshot={...snapshot,security:{...snapshot.security,ownerRevision:'owner2'}};state={snapshot};view.rerender(tree());}};
}
beforeEach(()=>{AppState.currentState='active';mounted=0;});

test('same-owner Settings retains its pending native change through policy edits while reads and actions revoke',()=>{
  const h=harness(false),body=screen.getByTestId('settings.body');
  fireEvent.press(screen.getByRole('button',{name:'BEGIN NATIVE CHANGE'}));
  h.policy();
  expect(mounted).toBe(1);expect(screen.getByTestId('settings.body')).toBe(body);expect(screen.getByTestId('settings.pending')).toHaveTextContent('Pending');
  h.authority(false);
  expect(mayInteractWithPresentation(h.app)).toBe(false);
  expect(screen.getByTestId('settings.body',{includeHiddenElements:true})).toBe(body);
  let cover=body.parent;while(cover&&cover.props.pointerEvents===undefined)cover=cover.parent;
  expect(cover?.props.pointerEvents).toBe('none');expect(cover?.props.accessibilityElementsHidden).toBe(true);expect(cover?.props.importantForAccessibility).toBe('no-hide-descendants');
  h.authority(true);expect(screen.getByTestId('settings.pending')).toHaveTextContent('Pending');
  h.owner();expect(mounted).toBe(2);expect(screen.getByTestId('settings.pending')).toHaveTextContent('Idle');
});

test('private form boundaries still retire their old body on security-policy replacement',()=>{
  const h=harness(true),body=screen.getByTestId('settings.body');
  fireEvent.press(screen.getByRole('button',{name:'BEGIN NATIVE CHANGE'}));
  h.policy();expect(screen.getByTestId('settings.body')).not.toBe(body);expect(mounted).toBe(2);expect(screen.getByTestId('settings.pending')).toHaveTextContent('Idle');
});
