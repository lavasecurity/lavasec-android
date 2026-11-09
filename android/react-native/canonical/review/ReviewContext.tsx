import {PlusIntentProvider} from './plus-intents';
import {DNSEditorProvider} from './dns-editor';
import type {AppStore} from '../app/store';
import type {AppSnapshot} from '../app/contract';
import {createContext, useContext, useMemo, useEffect, useRef, useState, useSyncExternalStore, type ComponentType, type ReactNode} from 'react';
import {View} from 'react-native';
import {FeedbackProvider} from '../src/feedback';
import type {AppearanceStore} from './appearance-store';
import {initialPreviewDraft, type PreviewDraft} from './preview-model';
import {initialSession, type PreviewSession} from './session';
import {PresentationCover} from './PresentationCover';
import {presentationOwnerScope,presentationScaffoldOwnerScope} from '../app/read-cache';

export type ReviewState = {
  app?: AppStore;
  onboardingPreview?:boolean;
  live?: AppSnapshot;
  appearance: AppearanceStore;
  look: string;
  setLook: (look: string) => void;
  draft: PreviewDraft;
  setDraft: (draft: PreviewDraft) => void;
  session: PreviewSession;
  setSession: (session: PreviewSession) => void | Promise<void>;
  savedDraft: PreviewDraft;
  setSavedDraft: (draft: PreviewDraft) => void;
  activityExample?: boolean;
  reviewGallery?: boolean;
};
const context = createContext<ReviewState | null>(null);
function Provider({value,children}:{value:ReviewState|null;children:ReactNode}) {
  // Missing foreground fields conceal the current owner; they do not replace it.
  // Both editor drafts and purchase return work retire on the same real boundary.
  const owner=useRef<{app?:AppStore;generation:number;key?:string}>({generation:0});
  if(owner.current.app!==value?.app)owner.current={app:value?.app,generation:owner.current.generation+1};
  if(value?.app&&value.live)owner.current.key=`${owner.current.generation}:${presentationOwnerScope(value.live)}`;
  const feedback=useMemo(()=>({emit:(event:import('../src/feedback').FeedbackEvent)=>{
    if(value?.app) void value.app.command({type:'haptic',kind:event.semantic,controlID:event.controlID,value:event.value}).catch(()=>{});
  }}),[value?.app]);
  const available=!value?.app||(value.app.getPresentationAuthority?value.app.getPresentationAuthority():value.app.getSnapshot?!!value.app.getSnapshot().snapshot:!!value.live);
  return <PlusIntentProvider retirementKey={owner.current.key}><DNSEditorProvider retirementKey={owner.current.key}
    available={available}><context.Provider value={value}><FeedbackProvider value={feedback}>{children}</FeedbackProvider></context.Provider></DNSEditorProvider></PlusIntentProvider>;
}
export const ReviewContext = {...context,Provider};
export function useReview() {
  const state = useContext(context);
  if (!state) throw new Error('Review screen must be inside its isolated review provider.');
  return state;
}

/** Shared native scaffolds also support standalone review fixtures. */
export function useOptionalReview(){return useContext(context);}

const routeBodyConcealment=createContext(false);
/** Retained ordinary pages conceal their existing native scroll root in place. */
export function useRouteBodyConcealed(){return useContext(routeBodyConcealment);}

/** Same-owner routes retain their scaffold; scoped readers revoke private values.
 * Explicit body disposal remains available for content with no safe shell. */
export function LiveRenderBoundary({component: Component,retainBody=true,directScrollRoot=false,retireOnPolicyChange=true}: {component: ComponentType;retainBody?:boolean;directScrollRoot?:boolean;retireOnPolicyChange?:boolean}) {
  const {app,live}=useReview();
  const owner=useRef<string|undefined>(undefined);

  const required=useSyncExternalStore(app?.subscribe??(()=>()=>{}),()=>!!app?.getPresentationHydration?.().required);
  const coverRequired=app?.getSnapshot?.()?.appPrivacyCoverRequired===true;
  const authority=useSyncExternalStore(app?.subscribe??(()=>()=>{}),()=>app?.getPresentationAuthority?.()??!!live);
  const concealed=!!app&&coverRequired&&(!authority||!live||required);
  // A redacted cold snapshot is metadata, not permission to construct a private page.
  // Once prepared, retain that same body through a warm cover; a fresh scope only
  // replaces it after its own authoritative frame has arrived.
  const prepared=useRef(false);
  if(!app||authority&&live){prepared.current=true;if(app&&live)owner.current=retireOnPolicyChange?presentationOwnerScope(live):presentationScaffoldOwnerScope(live);}
  if(app&&!prepared.current)return <View style={{flex:1}}/>;
  if(app&&!live&&!retainBody)return <View style={{flex:1}}>{concealed&&<PresentationCover testID="lava-route-privacy-cover"/>}</View>;
  // Native-confirmed all-off may retain already-painted values through inactive
  // and resume frames. Query authority still revokes on inactivity; policy or
  // data-scope changes discard values. Keep
  // the screen's native scroll/form identity through ordinary navigation and
  // settings updates; scoped readers clear protected values under the cover.
  // UIKit presents sheets in a separate controller above the root frame. Keep
  // its ScrollView direct and add the opaque cover as a sibling in that route.
  // A native parent draft may outlive privacy revocation. Keep its body inert
  // and inaccessible so removal cleanup cannot close a retained child editor.
  // An actual owner/policy replacement remounts it and retires the old draft.
  // A trusted frame may stay painted during an interruption, but the root and
  // this boundary both revoke interaction/accessibility until fresh authority.
  const retained=!!live&&app?.getSnapshot?.()?.displaySnapshot===live&&live.foreground!==false&&
    live.available!==false&&!live.presentationBlocked&&!live.security?.unavailable;
  const inert=!!app&&(!authority||!live||concealed);
  const hidden=!!app&&(!live||concealed||!authority&&!retained);
  // Explore's ordinary large title must keep Screen's ScrollView as the direct
  // native route child. A React-only provider puts the same concealment fence
  // on that existing scroll root without changing UIKit's view hierarchy.
  const body=retainBody?(directScrollRoot
    ?<routeBodyConcealment.Provider value={hidden}><Component key={owner.current}/></routeBodyConcealment.Provider>
    :<View style={{flex:1,opacity:hidden?0:1}} pointerEvents={inert?'none':'auto'} accessibilityElementsHidden={inert} importantForAccessibility={inert?'no-hide-descendants':'auto'}><Component key={owner.current}/></View>)
    :<Component/>;
  return <>{body}{concealed&&<View style={{position:'absolute',top:0,bottom:0,left:0,right:0,zIndex:1}}><PresentationCover testID="lava-route-privacy-cover"/></View>}</>;
}

/** The isolated gallery may edit fixtures; a production callback must never copy native data here. */
export function usePreviewFixtureState(enabled: boolean) {
  const [draft,setDraft]=useState(initialPreviewDraft);
  const [savedDraft,setSavedDraft]=useState(initialPreviewDraft);
  const [session,setSession]=useState(initialSession);
  useEffect(()=>{if(!enabled){setDraft(initialPreviewDraft());setSavedDraft(initialPreviewDraft());setSession(initialSession());}},[enabled]);
  return {
    draft:enabled?draft:initialPreviewDraft(),savedDraft:enabled?savedDraft:initialPreviewDraft(),session:enabled?session:initialSession(),
    setDraft:(value:PreviewDraft)=>{if(enabled)setDraft(value);},
    setSavedDraft:(value:PreviewDraft)=>{if(enabled)setSavedDraft(value);},
    setSession:(value:PreviewSession)=>{if(enabled)setSession(value);},
  };
}
