import {Alert,BackHandler,NativeModules,type AlertButton} from 'react-native';
import {localized} from '../app/presentation';
export type ActivityDates={start:number;end:number;label:string;includesToday:boolean};
function dates(days:number):ActivityDates {
  const today=new Date();
  const start=new Date(today);start.setHours(0,0,0,0);start.setDate(start.getDate()-(days-1));
  const nextDay=new Date(today);nextDay.setHours(0,0,0,0);nextDay.setDate(nextDay.getDate()+1);
  // Native queries require whole Unix seconds. Advance the local calendar day
  // before subtracting one second, so DST days keep their actual boundaries.
  const end=Math.floor(nextDay.getTime()/1000)-1;
  return {start:Math.floor(start.getTime()/1000),end,label:`${start.toLocaleDateString()} – ${today.toLocaleDateString()}`,includesToday:true};
}
export default {
  close:()=>BackHandler.exitApp(),
  chooseFilterAction:(name:string,canSwitch:boolean,canShare:boolean)=>new Promise<string|null>(resolve=>{
    const cancel:AlertButton={text:localized('Cancel'),style:'cancel',onPress:()=>resolve(null)};
    const actions:AlertButton[]=[...(canSwitch?[{text:localized('Use this filter'),onPress:()=>resolve('switch')}]:[]),...(canShare?[{text:localized('Share'),onPress:()=>resolve('share')}]:[])];
    // Android alerts accept at most three buttons, including Cancel. View stays available
    // even for inactive frozen filters; switching/sharing remain independently authorized.
    const buttons:AlertButton[]=[{text:localized('View filter'),onPress:()=>resolve('view')}];
    if(actions.length===1)buttons.push(actions[0]!);
    else if(actions.length>1)buttons.push({text:localized('More actions'),onPress:()=>Alert.alert(name,undefined,[...actions,cancel],{cancelable:true,onDismiss:()=>resolve(null)})});
    Alert.alert(name,undefined,[...buttons,cancel],{cancelable:true,onDismiss:()=>resolve(null)});
  }),
  chooseActivityExport:(canShareChart:boolean):Promise<string|null>=>NativeModules.NativeLavaApp.chooseActivityExport(canShareChart,localized('Export'),localized('Share chart'),localized('Export diagnostics'),localized('Export all logs')),
  speakDemo:async(text:string,locale:string):Promise<boolean>=>{
    const native=NativeModules.NativeLavaApp;
    return typeof native?.speakDemo==='function'?native.speakDemo(text,locale):false;
  },
  stopDemo:()=>NativeModules.NativeLavaApp?.stopDemo?.(),
  getLegalNotices:():string=>NativeModules.NativeLavaApp.getLegalNotices(),
  getGuardAccents:()=> '{}',getBlocklistCatalog:()=> '[]',
  getSharePreview:(_filter:string):string=>{throw new Error('Preview content is not available in the Android app.');},
  copySharePreview:(_filter:string)=>{throw new Error('Use the native sharing flow.');},
  normalizeDomain:(input:string)=>{try{return new URL(`https://${input.trim()}`).hostname;}catch{return null;}},
  getActivityDates:async()=>dates(1),
  getActivityDatePreset:async(preset:string)=>dates(preset==='today'?1:preset==='week'?7:preset==='month'?30:14),
  pickActivityDates:(start:number,end:number):Promise<ActivityDates|null>=>NativeModules.NativeLavaApp.pickActivityDates(start,end),
};
