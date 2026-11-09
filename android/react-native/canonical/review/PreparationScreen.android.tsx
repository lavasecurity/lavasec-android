import {useEffect,useMemo,useRef} from 'react';
import {View,type LayoutChangeEvent} from 'react-native';
import {useIsFocused} from '@react-navigation/native';
import {localized,Text} from '../app/presentation';
import {usePresentationAuthority,usePresentationNativeLayout,usePresentationReadiness} from '../app/use-presentation-readiness';
import {mayInteractWithPresentation} from '../app/read-cache';
import {PreparationScreen} from '../src/flows/preparation/PreparationScreen';
import {PreparationGuardian} from '../src/flows/preparation/PreparationGuardian';
import type {PreparationCommandPort} from '../src/flows/preparation/contract';
import {colors} from '../src/colors';
import {foundation} from '../src/foundation';
import {useReview} from './ReviewContext';
import {useReducedMotionPreference} from './navigation-scaffold';
import {PresentationCover} from './PresentationCover';
import {Symbol} from './primitives';

/** Presentation only. Native owns progress, retries, publication and semantic effects. */
export function AndroidPreparation({filterId}:{filterId?:string}){
  const {app,live,look}=useReview();const focused=useIsFocused();
  const authoritative=usePresentationAuthority(app);const onLayout=usePresentationNativeLayout();
  const reduceMotion=useReducedMotionPreference();
  const snapshot=live?.preparation&&live.preparation.filterId===filterId?live.preparation:undefined;
  const current=useRef(snapshot?.id);if(snapshot)current.current=snapshot.id;
  const measured=useRef<string|undefined>(undefined);
  const active=focused&&authoritative&&mayInteractWithPresentation(app);
  const enter=()=>{if(snapshot&&active&&measured.current===snapshot.id)void app?.command({type:'preparation.enter',id:snapshot.id}).catch(()=>{});};
  const layout=(event:LayoutChangeEvent)=>{
    onLayout(event);
    if(snapshot&&event.nativeEvent.layout.width>0&&event.nativeEvent.layout.height>0){measured.current=snapshot.id;enter();}
  };
  useEffect(enter,[app,snapshot?.id,active]);
  usePresentationReadiness(app,focused,!!snapshot,`preparation:${snapshot?.id??'covered'}`);
  const port=useMemo<PreparationCommandPort>(()=>({send:command=>{
    if(!app)return Promise.reject(new Error(localized('The filter change could not be committed.')));
    return app.command(command);
  }}),[app]);
  useEffect(()=>()=>{if(current.current)void app?.retirePreparation(current.current).catch(()=>{});},[app]);
  if(!snapshot||!authoritative)return <PresentationCover/>;
  return <View key={snapshot.id} testID="preparation.native-viewport" style={{flex:1}} onLayout={layout}>
    <PreparationScreen key={snapshot.id} snapshot={snapshot} port={port} active={active} reduceMotion={reduceMotion}
      copy={{previousFilter:localized('Your previous filter is still active.'),retry:localized('Try Again'),
        upgrade:localized('Upgrade'),keepCurrent:localized('Keep Current Filter'),
        backToEdit:localized('Back to Edit'),backToReview:localized('Back to Review')}}
      guardian={<PreparationGuardian active={active} look={look}/>}
      successGlyph={<Symbol name="checkmark.circle.fill" size={68}/>}
      failureGlyph={<Symbol name="exclamationmark.triangle.fill" size={68} tone="error"/>}
      background={colors.groupedBackground} progressColor={colors.safeGreen} progressTrack={colors.separator}
      renderText={({children,kind,style,header,testID,...text})=><Text {...text} verbatim testID={testID}
        accessibilityRole={header||kind==='title'?'header':undefined}
        style={[{fontSize:kind==='title'?foundation.type.title.fontSize:foundation.type.supporting.fontSize,
          fontWeight:kind==='title'?'600':'400',color:kind==='title'?colors.primaryText:colors.secondaryText,
          textAlign:'center'},style]}>{children}</Text>}/>
  </View>;
}
