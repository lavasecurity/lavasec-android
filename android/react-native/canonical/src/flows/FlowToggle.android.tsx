import {View,type ColorValue} from 'react-native';
import {Text} from '../../app/presentation';
import {LavaToggleControl} from '../toggle-row.android';
import {foundation} from '../foundation';

/** Native switch owns the requested/confirmed value; the label is Android text. */
export function FlowToggle({title,titleColor,value,onChange,disabled=false,optimistic=false}: {
  title:string;titleColor?:ColorValue;value:boolean;onChange:(value:boolean)=>void|Promise<unknown>;disabled?:boolean;optimistic?:boolean;
}){
  return <View style={{flexDirection:'row',alignItems:'center',gap:foundation.space.sm}}>
    <Text style={{flex:1,color:titleColor}}>{title}</Text>
    <LavaToggleControl title={title} value={value} onValueChange={onChange} disabled={disabled} optimistic={optimistic}/>
  </View>;
}
