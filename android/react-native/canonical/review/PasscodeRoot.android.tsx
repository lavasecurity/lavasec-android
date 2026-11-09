import {useState} from 'react';
import {ScrollView,View,useWindowDimensions} from 'react-native';
import {PresentationContext,Text} from '../app/presentation';
import {useTextScale} from '../app/text-metrics';
import {PasscodeScreen} from '../src/flows/passcode/PasscodeScreen';
import {FlowSymbol} from '../src/flows/layout.android';
import {colors} from '../src/colors';
import NativeForm from '../specs/LavaPasscodeFormNativeComponent.android';

type Props={ownerID:string;title:string;subtitle:string;textScale?:number};
/** A separate secure native Dialog surface; it does not read the covered product store. */
export default function PasscodeRoot(props:Props){
  const ramps=['body','headline','subheadline','footnote','caption1','caption2','title1','title2','title3','largeTitle'];
  return <PresentationContext.Provider value={{locale:'en',textScales:props.textScale===undefined?null:
    Object.fromEntries(ramps.map(ramp=>[ramp,props.textScale!]))}}>
    <PasscodeBody key={props.ownerID} {...props}/>
  </PresentationContext.Provider>;
}
function PasscodeBody({ownerID,title,subtitle}:Props){
  const [metadata,setMetadata]=useState({height:160,authenticating:false,message:''});
  const [focusRequest,refocus]=useState(0);const scale=useTextScale();const window=useWindowDimensions();
  return <ScrollView style={{flex:1,backgroundColor:colors.groupedBackground}}
    contentContainerStyle={{flexGrow:1}} keyboardShouldPersistTaps="handled" keyboardDismissMode="none">
    <View style={{flex:1,minHeight:Math.min(440,window.height*.6)}}>
      <PasscodeScreen state={{id:ownerID,authenticating:metadata.authenticating,title,subtitle,message:metadata.message}}
        digits={null} onRefocus={()=>refocus(value=>value+1)}
        titleContent={<Text verbatim accessibilityRole="header" dynamicTypeRamp="title1"
          style={{fontSize:28,fontWeight:'600',textAlign:'center',color:colors.primaryText}}>{title}</Text>}
        subtitleContent={subtitle?<Text verbatim style={{fontSize:16,fontWeight:'400',textAlign:'center',color:colors.secondaryText}}>{subtitle}</Text>:null}
        glyph={<FlowSymbol name="lock.shield.fill" size={48} pointSize={40}/>}
        input={<NativeForm ownerID={ownerID} fontScale={scale} focusRequest={focusRequest}
          style={{alignSelf:'stretch',height:metadata.height,minHeight:48}}
          onStateChange={event=>{
            const next=event.nativeEvent;
            if(Number.isFinite(next.height)&&next.height>=48&&next.height<=4096&&
              typeof next.authenticating==='boolean'&&typeof next.message==='string')setMetadata(next);
          }}/>}
      />
    </View>
  </ScrollView>;
}
