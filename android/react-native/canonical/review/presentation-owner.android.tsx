import {createContext,useContext,useLayoutEffect,useRef,useState,useSyncExternalStore,type ComponentType} from 'react';
import {View} from 'react-native';
import {useIsFocused,useRoute,useNavigation} from '@react-navigation/native';
import {useReview} from './ReviewContext';
import {usePresentationReadiness} from '../app/use-presentation-readiness';
import type {AppStore} from '../app/store';

export type AndroidPresentation={serial:number;routeKey:string;ownerID?:string;accepted?:boolean;surface:'activityViewing'|'filterEditing'|'appSettings'|'credentials'|'none'};
export const AndroidPresentationContext=createContext<AndroidPresentation|null>(null);
// Retained fields below an independent modal must lose edit admission even
// when their nested navigator still reports focused. This shares the current
// route commit that precedes the native readiness frame.
export const AndroidFieldPresentationContext=createContext(true);
/** Focus-only animation receipt; hydration/native Ready do not wait for it. */
export const AndroidFieldAutoFocusContext=createContext(true);
/** User controls wait for the exact native Ready receipt; layout/readers never depend on it. */
export type AndroidInteractionReceipt={app:AppStore;key:string};
export const AndroidPresentationInteractionContext=createContext<boolean|AndroidInteractionReceipt>(true);
export const AndroidPresentationControlContext=createContext<{beginModal:(ownerID:string)=>AndroidPresentation;restoreMain:()=>void;ready?:(serial:number,epoch:number)=>void}|null>(null);
const noSubscribe=()=>()=>{};
/** A native route viewport and its scoped readers settle before the exact
 * protected presentation can be revealed. Retain the same route/body on pause. */
export function useAndroidPresentationReady(presentation:AndroidPresentation|null,current:boolean,layout:boolean){
  const {app,live}=useReview();
  const control=useContext(AndroidPresentationControlContext);
  const readyControl=useRef(control);readyControl.current=control;
  const authority=useSyncExternalStore(app?.subscribe??noSubscribe,()=>app?.getPresentationAuthority?.()??true);
  const hydration=useSyncExternalStore(app?.subscribe??noSubscribe,()=>app?.getPresentationHydration?.().epoch??0);
  const pending=useSyncExternalStore(app?.subscribe??noSubscribe,()=>app?.getPresentationHydration?.().required??false);
  const sent=useRef<{app:typeof app;key:string}|undefined>(undefined),retry=useRef({app,key:'',count:0});
  const [interactionReceipt,setInteractionReceipt]=useState<AndroidInteractionReceipt>();
  const [settlement,setSettlement]=useState(0);
  const authorityKey=JSON.stringify([live?.security?.readRevision,live?.security?.authenticatedSurfaces]);
  const accepted=presentation?.accepted!==false;
  const readyKey=JSON.stringify([presentation?.serial,presentation?.routeKey,presentation?.ownerID,accepted,live?.generation,live?.nativeHostID,authorityKey,hydration]);
  const currentValue=useRef({app,presentation,current,readyKey});currentValue.current={app,presentation,current,readyKey};
  usePresentationReadiness(app,!!app&&current,layout,`android-route:${presentation?.serial}`);
  useLayoutEffect(()=>{
    // Native pause revokes admission even if this same retained route keeps its
    // serial. Each refreshed authority/layout epoch needs a new attestation.
    if(!authority||!current||!accepted||!layout||pending){sent.current=undefined;setInteractionReceipt(undefined);}
  },[authority,current,accepted,layout,pending]);
  useLayoutEffect(()=>{
    if(!app||!current||!layout||!authority)return;
    const frame=requestAnimationFrame(()=>app.completePresentationLayout(hydration));
    return()=>cancelAnimationFrame(frame);
  },[app,current,layout,authority,hydration]);
  useLayoutEffect(()=>{
    if(!app||!current||!layout||!authority||!accepted||pending||!presentation||sent.current?.app===app&&sent.current.key===readyKey)return;
    const serial=presentation.serial,key=readyKey,generation=live?.generation,hostID=live?.nativeHostID,readRevision=live?.security?.readRevision;let active=true;
    if(retry.current.app!==app||retry.current.key!==key)retry.current={app,key,count:0};
    const ownsCurrentFrame=()=>{
      const latest=app.getSnapshot?.().snapshot;
      return active&&currentValue.current.app===app&&currentValue.current.readyKey===key&&currentValue.current.current&&
        currentValue.current.presentation?.accepted!==false&&app.getPresentationAuthority()&&!app.getPresentationHydration().required&&
        (typeof app.getSnapshot!=='function'||!!latest&&latest.generation===generation&&latest.nativeHostID===hostID&&latest.security?.readRevision===readRevision);
    };
    const frame=requestAnimationFrame(()=>{
      if(!ownsCurrentFrame())return;
      sent.current={app,key};
      void app.command({type:'navigation.presentationReady',serial,...(presentation.ownerID?{ownerID:presentation.ownerID}:{}),...(readRevision!==undefined?{readRevision}:{})}).then(result=>{
        if(result&&typeof result==='object'&&'status' in result&&result.status!=='completed')throw new Error('The native presentation was not accepted.');
        if(sent.current?.app===app&&sent.current.key===key&&ownsCurrentFrame()){
          setInteractionReceipt({app,key});
          readyControl.current?.ready?.(serial,hydration);
        }
      }).catch(()=>{
        if(!active||currentValue.current.app!==app)return;
        if(sent.current?.app===app&&sent.current.key===key)sent.current=undefined;
        // A newer grant can settle before rejection of the old readiness
        // request. Reconsider it once, without repeatedly issuing requests.
        if(currentValue.current.readyKey===key&&currentValue.current.current&&retry.current.count++===0)setSettlement(value=>value+1);
      });
    });
    return()=>{active=false;cancelAnimationFrame(frame);};
  },[app,current,layout,authority,accepted,pending,presentation?.serial,readyKey,settlement]);
  return !app?true:!!current&&layout&&authority&&accepted&&!pending&&interactionReceipt?.app===app&&interactionReceipt.key===readyKey?interactionReceipt:false;
}
function AndroidRouteFrame({component:Component}:{component:ComponentType}){
  const presentation=useContext(AndroidPresentationContext),route=useRoute(),focused=useIsFocused();
  const navigation=useNavigation() as unknown as {isFocused:()=>boolean;addListener?:(event:string,callback:(event:{target?:string;data?:{closing?:boolean}})=>void)=>()=>void};
  const {app}=useReview();
  const [layout,setLayout]=useState(false),[openingComplete,setOpeningComplete]=useState<{visit:object;complete:true}|undefined>(undefined);
  // The AppStore is already bound to its immutable native host. First metadata
  // hydration must not retire a completed native opening of this same route.
  const visit=useRef<{app:typeof app;key:string;owner?:string}|undefined>(undefined);
  if(!visit.current||visit.current.app!==app||visit.current.key!==route.key||visit.current.owner!==(route.params as {id?:string}|undefined)?.id)
    visit.current={app,key:route.key,owner:(route.params as {id?:string}|undefined)?.id};
  useLayoutEffect(()=>{
    const captured=visit.current;let mounted=true;setOpeningComplete(undefined);
    const current=(event:{target?:string})=>mounted&&visit.current===captured&&(!event.target||event.target===captured?.key)&&navigation.isFocused();
    const start=navigation.addListener?.('transitionStart',event=>{if(current(event))setOpeningComplete(undefined);});
    const end=navigation.addListener?.('transitionEnd',event=>{if(current(event))setOpeningComplete(previous=>event.data?.closing===false&&captured?(previous?.visit===captured?previous:{visit:captured,complete:true}):undefined);});
    const blur=navigation.addListener?.('blur',()=>{if(mounted&&visit.current===captured)setOpeningComplete(undefined);});
    return()=>{mounted=false;start?.();end?.();blur?.();};
  },[app,route.key,(route.params as {id?:string}|undefined)?.id,navigation]);
  const current=focused&&presentation?.routeKey===route.key;
  const admitted=!presentation||!!current&&presentation.accepted!==false;
  const interactive=useAndroidPresentationReady(presentation,current,layout);
  return <AndroidPresentationInteractionContext.Provider value={interactive}><AndroidFieldPresentationContext.Provider value={admitted}><AndroidFieldAutoFocusContext.Provider value={focused&&admitted&&openingComplete?.visit===visit.current&&openingComplete?.complete===true}><View collapsable={false} style={{flex:1}} onLayout={event=>setLayout(event.nativeEvent.layout.width>0&&event.nativeEvent.layout.height>0)}><Component/></View></AndroidFieldAutoFocusContext.Provider></AndroidFieldPresentationContext.Provider></AndroidPresentationInteractionContext.Provider>;
}
const routeComponents=new Map<ComponentType,ComponentType>();
export function withAndroidPresentation(component:ComponentType):ComponentType{
  const existing=routeComponents.get(component);if(existing)return existing;
  const Route=()=> <AndroidRouteFrame component={component}/>;routeComponents.set(component,Route);return Route;
}
export function androidProtectedSurface(name:string,kind?:string):AndroidPresentation['surface']{
  if(name==='CustomEntry')return kind==='blocklist'?'filterEditing':'appSettings';
  if(name==='Foreground')return ['createFilter','renameFilter','deleteFilters'].includes(kind??'')?'filterEditing'
    :['licenses','automation'].includes(kind??'')?'none':'appSettings';
  if(['Activity','TopDomains','History','Stats','Network'].includes(name))return 'activityViewing';
  if(['Filters','Library','Filter','Review','Import','Share','ShareDetail','AddDomain','AddBlocklist','DomainCheck'].includes(name))return 'filterEditing';
  if(['Security','Passcode'].includes(name))return 'credentials';
  if(['Guard','Sudoku','Components'].includes(name))return 'none';
  return 'appSettings';
}

/** Native field/save admission binds the measured route to its exact service visit. */
export function androidPresentationOwnerID(name:string,id?:string):string|undefined {
  if(!id)return;
  if(name==='Foreground')return `foreground:${id}`;
  if(name==='CustomEntry')return `custom-entry:${id}`;
}
