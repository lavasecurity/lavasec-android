import {useEffect,useState} from 'react';
import {Pressable,Text,View,type LayoutChangeEvent} from 'react-native';
import type {LavaChoiceProps} from '../android/componentProps';
import {colors} from '../src/colors';
import {localized} from '../app/presentation';

// The platform leaf owns a short-lived optimistic selection so a controlled
// parent that has not yet confirmed (or whose owner is still resolving) never
// makes the control snap back to the previous value. The authoritative `value`
// and every `selectionRevision` bump reconcile the held selection; a rejected
// update reaches the leaf as a revision bump and is then discarded.
export default function AndroidChoice(p:LavaChoiceProps) {
  const [pending,setPending]=useState<string>();
  const [height,setHeight]=useState(48);
  const displayed=pending!==undefined&&p.options.some(option=>option.value===pending)?pending:p.value;
  const tint=p.tintColor??colors.softGreen;
  useEffect(()=>{setPending(undefined);},[p.selectionRevision,p.value]);
  const commit=(next:string)=>{
    if(p.disabled)return;
    if(next!==displayed||next===p.reselectValue)setPending(next);
    p.onValueChange?.({nativeEvent:{value:next}} as never);
  };
  const onLayout=(event:LayoutChangeEvent)=>{
    const next=event.nativeEvent.layout.height;
    if(next>0)setHeight(next);
    p.onSizeChange?.({nativeEvent:{height:next}} as never);
  };
  if(p.pageControl){
    return <View testID={p.testID} accessibilityLabel={p.label} accessibilityRole="tablist" accessible={p.accessible} importantForAccessibility={p.importantForAccessibility}
      style={[p.style,{flexDirection:'row',minHeight:44,gap:8,alignItems:'center',justifyContent:'center'}]} onLayout={onLayout}>
      {p.options.map((option,index)=><Pressable key={option.value} accessibilityRole="tab" accessibilityLabel={option.label}
        // RN projects these public values to Android RangeInfo. Position belongs to
        // this real option; an absent displayed value still selects no page.
        accessibilityValue={{min:1,max:p.options.length,now:index+1}}
        accessibilityState={{selected:displayed===option.value,disabled:p.disabled}} disabled={p.disabled}
        onPress={()=>commit(option.value)} hitSlop={8}
        style={{width:10,height:10,borderRadius:5,backgroundColor:displayed===option.value?tint:colors.disabledSurface}}/>)}
    </View>;
  }
  if(p.stepper){
    const index=Math.max(0,p.options.findIndex(option=>option.value===displayed));
    const current=p.options[index];
    const step=(delta:number)=>{const next=p.options[index+delta];if(next)commit(next.value);};
    return <View testID={p.testID} accessibilityLabel={p.label} accessible={p.accessible} importantForAccessibility={p.importantForAccessibility}
      style={[p.style,{flexDirection:'row',height,gap:2,borderRadius:12,backgroundColor:colors.cardBackground,overflow:'hidden',alignItems:'center'}]} onLayout={onLayout}>
      <Pressable accessibilityRole="button" accessibilityLabel={`${p.label} ${localized('Previous')}`} accessibilityState={{disabled:p.disabled||index<=0}}
        disabled={p.disabled||index<=0} onPress={()=>step(-1)} style={{width:44,height:'100%',alignItems:'center',justifyContent:'center'}}>
        <Text style={{color:colors.primaryText,fontSize:20}}>−</Text></Pressable>
      <Text accessibilityRole="text" style={{flex:1,minWidth:32,textAlign:'center',color:colors.primaryText,fontSize:15,fontWeight:'600'}}>{current?.label??''}</Text>
      <Pressable accessibilityRole="button" accessibilityLabel={`${p.label} ${localized('Next')}`} accessibilityState={{disabled:p.disabled||index<0||index>=p.options.length-1}}
        disabled={p.disabled||index<0||index>=p.options.length-1} onPress={()=>step(1)} style={{width:44,height:'100%',alignItems:'center',justifyContent:'center'}}>
        <Text style={{color:colors.primaryText,fontSize:20}}>+</Text></Pressable>
    </View>;
  }
  return <View testID={p.testID} accessibilityLabel={p.label} accessibilityRole="tablist" accessible={p.accessible} importantForAccessibility={p.importantForAccessibility}
    style={[p.style,{flexDirection:'row',height:48,gap:2,borderRadius:12,backgroundColor:colors.cardBackground,overflow:'hidden'}]} onLayout={onLayout}>
    {p.options.map(option=><Pressable key={option.value} accessibilityRole="tab" accessibilityLabel={option.label} accessibilityState={{selected:displayed===option.value,disabled:p.disabled}} disabled={p.disabled}
      onPress={()=>commit(option.value)} style={{flex:1,justifyContent:'center',alignItems:'center',paddingHorizontal:8,backgroundColor:displayed===option.value?colors.softGreen:undefined}}>
      <Text style={{color:colors.primaryText,fontSize:14,fontWeight:displayed===option.value?'600':'400'}}>{option.label}</Text></Pressable>)}
  </View>;
}
