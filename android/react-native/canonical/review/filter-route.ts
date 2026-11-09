import {createContext,useContext,useEffect,useRef,useState,useSyncExternalStore} from 'react';
import {Platform} from 'react-native';
import {usePresentationReadiness} from '../app/use-presentation-readiness';
import {mayInteractWithPresentation} from '../app/read-cache';
import {useIsFocused, useNavigation, useRoute} from '@react-navigation/native';
import {useReview} from './ReviewContext';

// Native visit teardown belongs to the navigation route, not its revocable body.
export const FilterDetailOwnerContext=createContext<{id?:string}|undefined>(undefined);

// Native filter selection is shared with automation and other tabs. A retained
// detail owns its opened identity; editing sheets must never adopt a new target.
export function useFilterRoute(restoreDetail=false) {
  const {app,live}=useReview();
  const owner=useContext(FilterDetailOwnerContext);
  const route=useRoute();const navigation=useNavigation();const focused=useIsFocused();
  const [initialID]=useState(live?.session?.filterID);
  const id=(route.params as {id?:string}|undefined)?.id??owner?.id??initialID;
  const exists=live?.newFilter?.id===id||live?.filters?.some(filter=>filter.id===id)!==false;
  const frozen=live?.filters?.find(filter=>filter.id===id)?.frozen??false;
  const editing=live?.session?.editing;
  const selected=live?.session?.filterID;
  const androidRuntime=Platform.OS==='android'&&typeof app?.getSnapshot==='function';
  // Android redacts FILTERS rows and the edit session independently from APP_UNLOCK.
  // That covered projection cannot prove deletion or authorize route retirement.
  const filterReadable=!(androidRuntime&&live?.security?.protectedSurfaces?.includes('filterEditing')===true&&
    live.security.authenticatedSurfaces?.includes('filterEditing')!==true);
  const ready=!app||filterReadable&&!!id&&exists&&selected===id&&(restoreDetail||!!editing);
  const visitKey=JSON.stringify([route.key,id,live?.generation,live?.nativeHostID]);
  const scope=JSON.stringify(androidRuntime?[id,exists,restoreDetail,visitKey]:[id,exists,restoreDetail]);
  const [failedScope,setFailedScope]=useState<string>();
  const restoring=useRef<{app:typeof app;key:string}|undefined>(undefined);
  const retiring=useRef<{app:typeof app;key:string}|undefined>(undefined);
  const currentVisit=useRef({app,key:visitKey});currentVisit.current={app,key:visitKey};
  const prepared=ready&&!(restoreDetail&&frozen&&editing);
  const authoritative=usePresentationReadiness(app,focused&&!!app,filterReadable&&(prepared||!id||!exists||!restoreDetail&&!editing||failedScope===scope||!!live?.filterPreparationPresented),scope);
  const interactive=useSyncExternalStore(app?.subscribe??(()=>()=>{}),()=>mayInteractWithPresentation(app));
  useEffect(()=>{
    if(!app||!focused||!authoritative||!filterReadable)return;
    const close=()=>{
      if(!mayInteractWithPresentation(app))return;
      if(!androidRuntime){navigation.goBack();return;}
      // Native fragments can retain a retiring body through later product/layout
      // commits. Use this committed admission, then remove only this route from
      // its captured stack; an old Review must never pop the underlying Filter.
      if(!interactive||retiring.current?.app===app&&retiring.current.key===visitKey)return;
      const state=navigation.getState();
      if(!state||state.routes[state.index]?.key!==route.key)return;
      retiring.current={app,key:visitKey};
      navigation.dispatch({type:'GO_BACK',source:route.key,target:state.key});
    };
    if(failedScope===scope){close();return;}
    if(!id||!exists){close();return;}
    if(live?.filterPreparationPresented)return;
    if(!restoreDetail&&!editing){close();return;}
    if(selected===id&&!(restoreDetail&&frozen&&editing))return;
    if(!restoreDetail){close();return;}
    if(androidRuntime){
      // Read/layout/focus commits revoke the React effect, not its native request.
      // Hold that exact route's request until settlement so a pending restoration
      // cannot repeatedly enter the native owner or replay cancelled authorization.
      if(restoring.current?.app===app&&restoring.current.key===visitKey)return;
      const request={app,key:visitKey};restoring.current=request;
      void app.command({type:'filter.open',id}).catch(()=>{
        if(restoring.current===request&&currentVisit.current.app===app&&currentVisit.current.key===visitKey)setFailedScope(scope);
      }).finally(()=>{if(restoring.current===request)restoring.current=undefined;});
      return;
    }
    let current=true;
    void app.command({type:'filter.open',id}).catch(()=>{if(current)setFailedScope(scope);});
    return()=>{current=false;};
  },[app,focused,id,exists,selected,restoreDetail,frozen,editing,live?.filterPreparationPresented,navigation,authoritative,filterReadable,interactive,failedScope,scope,androidRuntime,visitKey,route.key]);
  useEffect(()=>{
    if(!app||!restoreDetail||!id||owner)return;
    return()=>{void app.command({type:'filter.close',id}).catch(()=>{});};
  },[app,restoreDetail,id,owner]);
  return {id,ready};
}
