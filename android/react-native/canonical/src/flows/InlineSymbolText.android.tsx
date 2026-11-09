import {Text,localized} from '../../app/presentation';
import {materialSymbol,materialSymbolFont} from '../../android/material-symbols';
import type {InlineSymbolTextProps} from './inline-symbol-text';

/** One native Text run preserves wrapping, tint, baseline and Android font scaling. */
export function InlineSymbolText({title,symbol,tintColor,textColor,fontSize,fontWeight,testID}:InlineSymbolTextProps){
  return <Text verbatim testID={testID} accessibilityLabel={localized(title)} allowFontScaling style={{color:textColor,fontSize,fontWeight:fontWeight==='bold'?'700':fontWeight==='semibold'?'600':'400'}}>
    {!!symbol&&<><Text verbatim accessible={false} style={{fontFamily:materialSymbolFont(symbol),fontWeight:'normal',color:tintColor}}>{materialSymbol(symbol)}</Text>{' '}</>}{localized(title)}
  </Text>;
}
