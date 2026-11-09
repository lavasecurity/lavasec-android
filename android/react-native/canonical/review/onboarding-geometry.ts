import {createContext,useContext,useEffect,useState,type ComponentRef} from 'react';
import {AppState,Dimensions,View} from 'react-native';
import type {OnboardingFrame,OnboardingSetup} from '../app/contract';
import {validOnboardingDestination,type OnboardingDestination} from './onboarding-motion';
export type Anchor={current:ComponentRef<typeof View>|null};
type Geometry={panel:Anchor;mascot:Anchor;action:Anchor;source:Anchor;root:Anchor;frames?:OnboardingDestination;origin:{x:number;y:number};size:{width:number;height:number};sourceFrame?:OnboardingFrame;setup?:OnboardingSetup};
const idle:Anchor={current:null};
export const OnboardingGeometryContext=createContext<Geometry>({panel:idle,mascot:idle,action:idle,source:idle,root:idle,origin:{x:0,y:0},size:{width:0,height:0}});
export const useOnboardingDestination=()=>useContext(OnboardingGeometryContext);

type Anchors=Pick<Geometry,'root'|'source'|'panel'|'mascot'|'action'>;
/** Measurements stay in the shared tree. Invalid, rotated or retired geometry
 * must never leave a previous destination available to the traveling mascot. */
export function useOnboardingMeasurements(visit:string|undefined,{root,source,panel,mascot,action}:Anchors){
  const [origin,setOrigin]=useState({x:0,y:0});
  const [frames,setFrames]=useState<OnboardingDestination>();
  const [sourceFrame,setSourceFrame]=useState<OnboardingFrame>();
  const [destinationReady,setDestinationReady]=useState(false);
  const [measurementVisit,setMeasurementVisit]=useState<string>();
  useEffect(()=>{
    setFrames(undefined);setSourceFrame(undefined);setDestinationReady(false);setMeasurementVisit(undefined);
    if(!visit)return;let active=true,busy=false,last='',epoch=0;
    const invalidate=()=>{epoch++;last='';setFrames(undefined);setSourceFrame(undefined);setDestinationReady(false);setMeasurementVisit(undefined);};
    const measure=(anchor:Anchor)=>new Promise<OnboardingFrame|undefined>(resolve=>{
      const node=anchor.current;
      if(!node){resolve(undefined);return;}
      // Fabric may drop a callback when its native view retires during layout.
      // One absent Guard destination must not starve the setup source forever.
      const timeout=setTimeout(()=>resolve(undefined),200);
      node.measureInWindow((x,y,width,height)=>{clearTimeout(timeout);resolve(anchor.current===node?{x,y,width,height}:undefined);});
    });
    const finite=(f:OnboardingFrame|undefined):f is OnboardingFrame=>!!f&&[f.x,f.y,f.width,f.height].every(Number.isFinite)&&f.width>0&&f.height>0;
    const report=async()=>{
      if(busy||AppState.currentState!=='active')return;busy=true;const generation=epoch;
      try{
        const nodes=[root.current,source.current,panel.current,mascot.current,action.current];
        const destination=Promise.all([measure(panel),measure(mascot),measure(action)]);
        const [r,s]=await Promise.all([measure(root),measure(source)]);
        if(!active||generation!==epoch)return;
        if(nodes[0]!==root.current||nodes[1]!==source.current){invalidate();return;}
        setMeasurementVisit(visit);
        if(finite(r)){
          setOrigin(old=>old.x===r.x&&old.y===r.y?old:{x:r.x,y:r.y});
          const nextSource=finite(s)?{...s,x:s.x-r.x,y:s.y-r.y}:undefined;
          const within=nextSource&&nextSource.x>=-1&&nextSource.y>=-1&&nextSource.x+nextSource.width<=r.width+1&&nextSource.y+nextSource.height<=r.height+1;
          setSourceFrame(old=>nextSource?JSON.stringify(old)===JSON.stringify(nextSource)?old:nextSource:undefined);
        }else{last='';setFrames(undefined);setSourceFrame(undefined);}
        // A dropped Guard callback must not starve the independently mounted
        // setup source; destination acceptance still waits for all three views.
        const [p,m,a]=await destination;
        if(!active||generation!==epoch)return;
        if(nodes.some((node,index)=>node!==[root.current,source.current,panel.current,mascot.current,action.current][index])){invalidate();return;}
        // Android must give a never-focused underlying Runtime its real first
        // layout before opening an initial modal. This preflight has no invented
        // coordinates; modal-relative frames still require the actual root below.
        const nativeDestination=finite(p)&&finite(m)&&finite(a)&&validOnboardingDestination({panel:p,mascot:m,action:a});
        setDestinationReady(nativeDestination);
        if(!finite(r))return;
        const relative=(f:OnboardingFrame)=>({...f,x:f.x-r.x,y:f.y-r.y});
        const withinRoot=(f:OnboardingFrame)=>f.x>=-1&&f.y>=-1&&f.x+f.width<=r.width+1&&f.y+f.height<=r.height+1;
        if(finite(p)&&finite(m)&&finite(a)){
          const next={panel:relative(p),mascot:relative(m),action:relative(a)},key=JSON.stringify(next);
          if(validOnboardingDestination(next)&&withinRoot(next.panel)){if(key!==last){last=key;setFrames(next);}return;}
        }
        last='';setFrames(undefined);
      }finally{busy=false;}
    };
    void report();const interval=setInterval(()=>void report(),160);
    const rotation=Dimensions.addEventListener('change',()=>{invalidate();void report();});
    const lifecycle=AppState.addEventListener('change',value=>{if(value!=='active')invalidate();else void report();});
    return()=>{active=false;clearInterval(interval);rotation.remove();lifecycle.remove();};
  },[visit,root,source,panel,mascot,action]);
  const current=measurementVisit===visit;
  return {origin:current?origin:{x:0,y:0},frames:current?frames:undefined,sourceFrame:current?sourceFrame:undefined,destinationReady:current&&destinationReady};
}
