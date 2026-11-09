import {useLayoutEffect,useRef,useSyncExternalStore} from 'react';
import {Platform} from 'react-native';
import {useNavigation,usePreventRemove,useRoute,type NavigationState,type PartialState} from '@react-navigation/native';
import {mayInteractWithPresentation} from '../app/read-cache';
import {useReview} from './ReviewContext';

const noSubscribe=()=>()=>{};
/** Keep one removal registration per Android route; native hydration only
 * changes admission of an action, never the nested prevention registry. */
export function useAndroidRouteRemoval(){
  const {app,live}=useReview();const navigation=useNavigation(),route=useRoute();
  const interactive=useSyncExternalStore(app?.subscribe??noSubscribe,()=>mayInteractWithPresentation(app));
  const original=useRef<{app:typeof app;key:string;nativeHostID?:string}|undefined>(undefined);
  // The bootstrap may precede the first native snapshot. Bind only the first
  // real host of this mounted route; a replacement window cannot adopt it.
  if(!original.current&&app&&live?.generation)original.current={app,key:route.key,nativeHostID:live.nativeHostID};
  // Each render captures its own graph authority. A queued old callback cannot
  // adopt a replacement graph, while the fresh hydrated callback may act.
  const generation=live?.generation;
  const mounted=useRef(true);
  useLayoutEffect(()=>{mounted.current=true;return()=>{mounted.current=false;};},[]);
  usePreventRemove(!!app&&(Platform.OS==='android'||!interactive),({data})=>{
    if(Platform.OS!=='android'||!app||!mounted.current)return;
    const state=navigation.getState();
    const currentRoute=state?.routes.find(item=>item.key===route.key);
    if(!state||!currentRoute)return;
    const current=typeof app.getSnapshot==='function'?app.getSnapshot().snapshot:live;
    const owner=original.current;
    if(typeof app.getSnapshot==='function'&&(!owner||owner.app!==app||owner.key!==route.key||!current||owner.nativeHostID!==current.nativeHostID))return;
    const id=(route.params as {id?:string}|undefined)?.id;
    // Native-stack may reuse this key for a successor foreground owner. Even
    // read-free acknowledgment may remove only the callback's exact owner.
    if(route.name==='Foreground'&&(!id||currentRoute.name!=='Foreground'||(currentRoute.params as {id?:string}|undefined)?.id!==id))return;
    // Native acknowledgment already erased this exact modal's private owner.
    // Its source/stack-bound pop is read-free cleanup, including graph changes;
    // no covered callback can use it to remove another foreground route.
    const retiredForeground=route.name==='Foreground'&&!!id&&current?.foregroundClosing?.includes(id)
      &&current.foregroundFlow?.id!==id&&state.routes[state.index]?.key===route.key
      &&data.action.type==='GO_BACK'&&data.action.source===route.key&&data.action.target===state.key;
    if(!retiredForeground&&(!mayInteractWithPresentation(app)||!generation||generation!==current?.generation))return;
    if(route.name==='Review'&&current?.preparation){
      // Close must reach the native compile owner before removing its renderer.
      // Replay the original action (with visited keys) only after exact retirement.
      void app.retirePreparation(current.preparation.id)
        .then(()=>{
          const latest=navigation.getState();
          if(mounted.current&&latest?.routes[latest.index]?.key===route.key)navigation.dispatch(data.action);
        }).catch(()=>{});
      return;
    }
    // Forward the original object, including React Navigation's visited keys.
    // A reconstructed GO_BACK would recursively invoke this same listener.
    navigation.dispatch(data.action);
  });
}

/** A native modal acknowledgment removes only its exact active stack route. */
export function androidForegroundCloseAction(state:NavigationState|PartialState<NavigationState>|undefined,id:string):{type:'GO_BACK';source:string;target:string}|undefined {
  if(!state)return;
  const route=state.routes[state.index??0];if(!route)return;
  if(route.state)return androidForegroundCloseAction(route.state,id);
  if(route.name!=='Foreground'||(route.params as {id?:string}|undefined)?.id!==id||!state.key||!route.key)return;
  return {type:'GO_BACK',source:route.key,target:state.key};
}
