import {Appearance,DeviceEventEmitter} from 'react-native';
import {androidRuntime,androidRuntimeForHost,readAndroidWire,readAndroidWireForHost} from '../android/runtime';
export type AppearanceSnapshot={preference:string;revision:number};
function appearanceForRuntime(runtime:typeof androidRuntime,readWire:typeof readAndroidWire,nativeHostID?:string){return {
  getSnapshot:async()=>{const p=await readWire();return {preference:p.preferences?.appearance??'system',revision:p.revision};},
  setPreference:async(preference:string)=>{
    await runtime.command(JSON.stringify({type:'settings.set',key:'appearance',value:preference}));
    Appearance.setColorScheme(preference==='system'?'unspecified':preference as 'light'|'dark');
    const p=await readWire();return {preference:p.preferences?.appearance??'system',revision:p.revision};
  },
  onSnapshot:(listener:(value:AppearanceSnapshot)=>void)=>DeviceEventEmitter.addListener('lavaAppSnapshot',(payload:string)=>{const p=JSON.parse(payload);if(nativeHostID!==undefined&&p.nativeHostID!==nativeHostID)return;listener({preference:p.preferences?.appearance??'system',revision:p.revision});}),
};}

export default appearanceForRuntime(androidRuntime,readAndroidWire);
export function androidAppearanceForHost(nativeHostID:string){
  return appearanceForRuntime(androidRuntimeForHost(nativeHostID),()=>readAndroidWireForHost(nativeHostID),nativeHostID);
}

export type Spec = typeof import('./NativeLavaAppearance.android').default;
