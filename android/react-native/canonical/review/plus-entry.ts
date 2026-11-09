import {useEffect,useRef} from 'react';
import {useReviewNavigation} from './navigation';
import {useReview} from './ReviewContext';
import {capturePlusRetirement,usePlusIntents,type PlusReason,type PlusResume} from './plus-intents';

export function usePlusEntry(){
  const nav=useReviewNavigation();const intents=usePlusIntents();
  const {app}=useReview();
  const pending=useRef<string|undefined>(undefined);
  useEffect(()=>()=>{if(pending.current)intents.remove(pending.current);},[intents]);
  return (reason:PlusReason,resume?:PlusResume)=>nav.navigateToUpgrade(()=>{
    if(pending.current)intents.remove(pending.current);
    const sourceGeneration=app?.getSnapshot?.().snapshot?.generation;
    let intent:string|undefined;
    if(resume){
      const wrapped=async()=>capturePlusRetirement(await resume(()=>intent!==undefined&&intents.peek(intent)===wrapped),app?.getSnapshot?.().snapshot??undefined,sourceGeneration);
      intent=intents.add(wrapped);
    }
    pending.current=intent;
    return {reason,intent};
  });
}
