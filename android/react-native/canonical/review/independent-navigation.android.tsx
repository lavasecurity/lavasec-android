import {createContext,useContext,useEffect,useId,useInsertionEffect,useLayoutEffect,useMemo,useRef,useState,type ContextType,type PropsWithChildren} from 'react';
import {NavigationIndependentTree,NavigationRouteContext,PreventRemoveContext,type NavigationState} from '@react-navigation/native';

/** The Runtime navigator has no removal relationship to the surrounding setup
 * stack. NavigationIndependentTree clears navigation/route ownership, while
 * the removal context needs the same boundary. Inner navigators still install
 * their own providers and keep every route's beforeRemove interception. */
export function AndroidIndependentNavigation({children}:PropsWithChildren){
  return <NavigationIndependentTree><RemovalAncestor.Provider value={undefined}><PreventRemoveContext.Provider value={undefined}>{children}</PreventRemoveContext.Provider></RemovalAncestor.Provider></NavigationIndependentTree>;
}

type RemovalValue=NonNullable<ContextType<typeof PreventRemoveContext>>;
const RemovalAncestor=createContext<{value:RemovalValue;routeKey?:string}|undefined>(undefined);
type RemovalDiagnostic='guard-parent'|'guard-routes'|'settings-parent'|'settings-routes';
const publicRoutes=new Set(['Guard','Settings','Filters','Activity','Explore','Library','Filter','Review','Share','Import','TopDomains','History','Customization','Guardian','Account','Upgrade','DNS','DNSPicker','CustomEntry','DNSPatch','Privacy','Security','Feedback','Legal','Stats','Network','ShareDetail','Passcode','AddDomain','AddBlocklist','VPNChaining','DeviceQA','AutoSwitch','Foreground','Components','Sudoku']);
/** Debug APKs bundle with Metro dev=false. The native QA capability explicitly
 * admits this finite observer; it never reads route params or emits identities. */
/** A route stack keeps its real tab ancestor and beforeRemove listeners. The
 * installed provider notifies that ancestor after every commit, including when
 * its registrations did not change. Synchronize each actual registration delta
 * once so a child's sync commit cannot enqueue another unchanged parent update. */
export function AndroidNestedRemovalBoundary(props:PropsWithChildren<{state?:Pick<NavigationState,'key'|'routes'>;diagnostics?:RemovalDiagnostic}>){
  return props.state?<AndroidOwnedRemovalBoundary {...props} state={props.state}/>:<AndroidForwardingRemovalBoundary {...props}/>;
}
function AndroidForwardingRemovalBoundary({children,state,diagnostics}:PropsWithChildren<{state?:Pick<NavigationState,'key'|'routes'>;diagnostics?:RemovalDiagnostic}>){
  const parent=useContext(PreventRemoveContext);
  const route=useContext(NavigationRouteContext);
  const set=parent?.setPreventRemove,notify=parent?.notifyPreventRemove;
  const observer=useRef({events:0,commits:0,setCalls:0,setChanges:0,additions:0,removals:0,notifyCalls:0,forwardedNotifications:0});
  const previous=useRef<{published:typeof parent;keys:Set<string>;state:typeof state}|undefined>(undefined);
  const scope=useMemo(()=>{
    if(!set||!notify)return undefined;
    const registrations=new Map<string,string>();let published=new Map<string,string>();
    const methods={
      setPreventRemove:(id:string,routeKey:string,prevent:boolean)=>{
        observer.current.setCalls++;
        const changed=prevent?registrations.get(id)!==routeKey:registrations.has(id);
        // Keep the actual provider's route validation and immediate registry
        // publication. The facade tracks only this nested stack's identifiers.
        // A late cleanup for an earlier key cannot erase the same consumer's
        // newer route. All admitted changes still reach the real provider.
        if(!prevent&&registrations.has(id)&&registrations.get(id)!==routeKey)return;
        set(id,routeKey,prevent);
        if(changed){observer.current.setChanges++;if(prevent)observer.current.additions++;else observer.current.removals++;}
        if(prevent)registrations.set(id,routeKey);else registrations.delete(id);
      },
      notifyPreventRemove:()=>{
        observer.current.notifyCalls++;
        // An insertion cleanup followed by reattachment can restore the same
        // owned registrations before a passive sync. Publish its final map;
        // transient delete/add calls alone are not a new parent state.
        if(published.size===registrations.size&&[...registrations].every(([id,key])=>published.get(id)===key))return;
        published=new Map(registrations);observer.current.forwardedNotifications++;notify();
      },
    };
    return {methods,counts:()=>({ownedRegistrations:registrations.size,ownedProtectedRoutes:new Set(registrations.values()).size}),retireAbsent:(keys:ReadonlySet<string>)=>{
      let changed=false;
      for(const [id,key] of registrations){
        if(keys.has(key))continue;
        registrations.delete(id);set(id,key,false);observer.current.setCalls++;observer.current.setChanges++;observer.current.removals++;changed=true;
      }
      if(changed)methods.notifyPreventRemove();
    }};
  },[set,notify]);
  // React skips insertion cleanup when deleting an already-hidden subtree.
  // Inside Navigator.layout, the real state supplies an independent retirement
  // witness. An invisible but still-present route keeps its exact registration.
  useLayoutEffect(()=>{if(state)scope?.retireAbsent(new Set(state.routes.map(route=>route.key)));},[scope,state]);
  useLayoutEffect(()=>{
    if(!diagnostics)return;
    const metrics=observer.current;metrics.commits++;
    const keys=new Set(Object.keys(parent?.preventedRoutes??{})),prior=previous.current;
    const publishedObjectChanged=prior?.published?.preventedRoutes!==parent?.preventedRoutes;
    const publishedContextChanged=prior?.published!==parent;
    const publishedRouteSetChanged=!prior||prior.keys.size!==keys.size||[...keys].some(key=>!prior.keys.has(key));
    const nativeStateChanged=prior?.state!==state;
    previous.current={published:parent,keys,state};
    // Preserve decisive deltas and sample unchanged commits. The lifetime cap
    // also covers old callbacks; logging never schedules React work or commands.
    if(metrics.events>=256||!(publishedObjectChanged||publishedContextChanged||nativeStateChanged||metrics.commits<=4||metrics.commits%4===0))return;
    console.info('LavaNavigationRemovalTrace '+JSON.stringify({boundary:diagnostics,...metrics,events:++metrics.events,
      publishedProtectedRoutes:keys.size,publishedObjectChanged,publishedContextChanged,publishedRouteSetChanged,nativeStateChanged,
      ...scope?.counts(),nativeRouteCount:state?.routes.length??0,
      routes:state?.routes.slice(-8).map(route=>publicRoutes.has(route.name)?route.name:'other')??[]}));
  });
  const value=useMemo(()=>parent&&scope?{...scope.methods,preventedRoutes:parent.preventedRoutes}:undefined,[parent?.preventedRoutes,scope]);
  const ancestor=useMemo(()=>value?{value,routeKey:route?.key}:undefined,[value,route?.key]);
  return <RemovalAncestor.Provider value={ancestor}><PreventRemoveContext.Provider value={value}>{children}</PreventRemoveContext.Provider></RemovalAncestor.Provider>;
}


/** Android's installed core provider invokes its state setter after every
 * commit, even when its published registry is unchanged. A retained Fabric
 * tree can keep that no-op update in the sync lane. Keep that core registry
 * empty, validate every admission through its real setter, and give the real
 * native-stack/usePreventRemove consumers an explicitly published owned map. */
function AndroidOwnedRemovalBoundary({children,state,diagnostics}:PropsWithChildren<{state:Pick<NavigationState,'key'|'routes'>;diagnostics?:RemovalDiagnostic}>){
  const core=useContext(PreventRemoveContext),ancestor=useContext(RemovalAncestor);
  const aggregateID=useId(),set=core?.setPreventRemove;
  const observer=useRef({events:0,commits:0,setCalls:0,setChanges:0,additions:0,removals:0,notifyCalls:0,forwardedNotifications:0});
  const active=useRef<object|undefined>(undefined);
  const [publication,publish]=useState<{scope:object;map:Map<string,string>}|undefined>(undefined);
  const scope=useMemo(()=>{
    if(!set)return undefined;
    const registrations=new Map<string,string>();let published=new Map<string,string>();
    const owned={
      methods:{
        setPreventRemove:(id:string,routeKey:string,prevent:boolean)=>{
          observer.current.setCalls++;
          // A saved cleanup for route A must never erase the same consumer's
          // current route B. Actual true admission retains core's validation.
          if(!prevent&&registrations.get(id)!==routeKey)return;
          if(prevent){set(id,routeKey,true);set(id,routeKey,false);}
          const changed=prevent?registrations.get(id)!==routeKey:registrations.has(id);
          if(changed){observer.current.setChanges++;if(prevent)observer.current.additions++;else observer.current.removals++;}
          if(prevent)registrations.set(id,routeKey);else registrations.delete(id);
        },
        notifyPreventRemove:()=>{
          if(active.current!==owned)return;
          observer.current.notifyCalls++;
          // Compare outside React's setter. An unchanged passive notification
          // must not enqueue any state update, including a no-op updater.
          if(published.size===registrations.size&&[...registrations].every(([id,key])=>published.get(id)===key))return;
          published=new Map(registrations);observer.current.forwardedNotifications++;
          publish({scope:owned,map:published});
        },
      },
      counts:()=>({ownedRegistrations:registrations.size,ownedProtectedRoutes:new Set(registrations.values()).size}),
      retireAbsent:(keys:ReadonlySet<string>)=>{
        let changed=false;
        for(const [id,key] of registrations){if(!keys.has(key)){registrations.delete(id);observer.current.setChanges++;observer.current.removals++;changed=true;}}
        if(changed)owned.methods.notifyPreventRemove();
      },
    };
    return owned;
  },[set]);
  // Only a committed provider can publish. Child insertion registrations may
  // arrive first, but an abandoned render must never replace the live scope.
  useInsertionEffect(()=>{active.current=scope;return ()=>{if(active.current===scope)active.current=undefined;};},[scope]);
  const map=publication&&publication.scope===scope?publication.map:undefined;
  const preventedRoutes=useMemo(()=>{
    const routes:RemovalValue['preventedRoutes']={};
    for(const key of map?.values()??[])routes[key]={preventRemove:true};
    return routes;
  },[map]);
  const value=useMemo(()=>scope?{...scope.methods,preventedRoutes}:undefined,[scope,preventedRoutes]);
  const parentSet=ancestor?.value.setPreventRemove,parentNotify=ancestor?.value.notifyPreventRemove,parentKey=ancestor?.routeKey;
  const isPrevented=(map?.size??0)>0;
  useInsertionEffect(()=>{
    if(parentKey===undefined||!parentSet)return;
    parentSet(aggregateID,parentKey,isPrevented);
    return ()=>parentSet(aggregateID,parentKey,false);
  },[aggregateID,parentKey,parentSet,isPrevented]);
  useEffect(()=>{parentNotify?.();return ()=>parentNotify?.();},[parentNotify,map]);
  // Hidden-but-present routes stay protected. Only actual route retirement
  // removes an owned child whose hidden insertion cleanup never ran.
  useLayoutEffect(()=>scope?.retireAbsent(new Set(state.routes.map(route=>route.key))),[scope,state]);
  const previous=useRef<{core:typeof core;keys:Set<string>;state:typeof state}|undefined>(undefined);
  useLayoutEffect(()=>{
    if(!diagnostics)return;
    const metrics=observer.current;metrics.commits++;
    const keys=new Set(Object.keys(core?.preventedRoutes??{})),prior=previous.current;
    const publishedObjectChanged=prior?.core?.preventedRoutes!==core?.preventedRoutes,publishedContextChanged=prior?.core!==core;
    const publishedRouteSetChanged=!prior||prior.keys.size!==keys.size||[...keys].some(key=>!prior.keys.has(key));
    const nativeStateChanged=prior?.state!==state;previous.current={core,keys,state};
    if(metrics.events>=256||!(publishedObjectChanged||publishedContextChanged||nativeStateChanged||metrics.commits<=4||metrics.commits%4===0))return;
    console.info('LavaNavigationRemovalTrace '+JSON.stringify({boundary:diagnostics,...metrics,events:++metrics.events,
      publishedProtectedRoutes:keys.size,publishedObjectChanged,publishedContextChanged,publishedRouteSetChanged,nativeStateChanged,
      ...scope?.counts(),nativeRouteCount:state.routes.length,
      routes:state.routes.slice(-8).map(route=>publicRoutes.has(route.name)?route.name:'other')}));
  });
  return <PreventRemoveContext.Provider value={value}>{children}</PreventRemoveContext.Provider>;
}
