// Ported from the pinned iOS `ReactNative/tests/scaffold-layout.test.tsx`
// (lavasec-ios-internal @ c1fdb8e5), adapting imports to the Android canonical
// root. #832 replaced the width-threshold StoryColumns branch with a Yoga-wrap
// reflow; this journey pins that the same input keeps its draft and one scroll.
import {act,fireEvent, render, screen} from '@testing-library/react-native';
import {Platform,ScrollView,StyleSheet,Text,TextInput,View,type ScrollViewInstance} from 'react-native';
import {createRef,useState} from 'react';
import {HeaderHeightContext} from '@react-navigation/elements';

const mockSheetNavigation={setOptions:jest.fn(),addListener:jest.fn((_name:string,_callback:(event:{data:{closing:boolean}})=>void)=>()=>{})};
jest.mock('@react-navigation/native',()=>({useNavigation:()=>mockSheetNavigation,useIsFocused:()=>true}));
jest.mock('../canonical/specs/LavaTextFieldNativeComponent',()=>({__esModule:true,default:require('react-native').View}));
jest.mock('../canonical/specs/LavaDecorationNativeComponent',()=>({__esModule:true,default:require('react-native').View}));

import {Screen} from '../canonical/review/primitives';
import {StoryColumns} from '../canonical/review/story-scaffold';
import {foundation} from '../canonical/src/foundation';
import {Sheet} from '../canonical/review/scaffold';

// Latest shared composition keeps stable lanes while clearing Yoga's cached basis on rotation.
test('StoryColumns uses the current portrait stack and landscape wrap without unmounting lanes',()=>{
  const dimensions=jest.spyOn(require('react-native'),'useWindowDimensions');
  const expectedWidth=foundation.layout.compactThreshold-foundation.space.screenHorizontal;
  for(const frame of [{width:393,height:852},{width:852,height:393}]){
    const landscape=frame.width>frame.height;
    dimensions.mockReturnValue({...frame,scale:3,fontScale:1});
    const view=render(<StoryColumns primary={<Text testID="lane.primary">Primary lane</Text>} secondary={<Text testID="lane.secondary">Secondary lane</Text>}/>);
    const columns=StyleSheet.flatten(screen.getByTestId('story.columns').props.style);
    expect(columns.flexDirection).toBe(landscape?'row':'column');
    expect(columns.flexWrap).toBe(landscape?'wrap':'nowrap');
    expect(columns.alignItems).toBe(landscape?'flex-start':'stretch');
    for(const lane of ['primary','secondary']){
      const column=StyleSheet.flatten(screen.getByTestId(`story.column.${lane}`).props.style);
      expect(column.flexBasis).toBe('auto');
      expect(column.width).toBe(landscape?expectedWidth:'auto');
      expect(screen.getByTestId(`lane.${lane}`)).toBeOnTheScreen();
    }
    view.unmount();
  }
  dimensions.mockRestore();
});

test('responsive columns share one page scroll and preserve drafts across rotation',()=>{
  const dimensions=jest.spyOn(require('react-native'),'useWindowDimensions');
  dimensions.mockReturnValue({width:852,height:393,scale:3,fontScale:1});
  function Draft(){const [value,setValue]=useState('');return <TextInput testID="column.draft" value={value} onChangeText={setValue}/>;}
  const content=()=><Screen wide><StoryColumns primary={<Draft/>} secondary={<Text>Second column</Text>}/></Screen>;
  render(content());
  expect(screen.UNSAFE_getAllByType(ScrollView)).toHaveLength(1);
  expect(screen.getByTestId('screen.scroll').props.contentInsetAdjustmentBehavior).toBe('automatic');
  expect(StyleSheet.flatten(screen.getByTestId('screen.scroll').props.contentContainerStyle).height).toBeUndefined();
  fireEvent.changeText(screen.getByTestId('column.draft'),'Keep this draft');
  dimensions.mockReturnValue({width:393,height:852,scale:3,fontScale:1});
  screen.rerender(content());
  expect(screen.UNSAFE_getAllByType(ScrollView)).toHaveLength(1);
  // The real-Yoga regression checks the first native resize frame without a
  // JS rerender. This journey checks that the same input keeps its draft.
  expect(screen.getByTestId('column.draft').props.value).toBe('Keep this draft');
  dimensions.mockRestore();
});


// Selected shared composition adoption from iOS #874 / 9964eac7. Native layout
// events exercise the actual viewport state; the Android host keeps its own
// zero header inset and detached footer while both platforms retain the input.
test.each([
  ['android',false],['android',true],['ios',false],['ios',true],
] as const)('%s short/empty sheets retain their measured viewport and draft (pinned=%s)',(os,pinned)=>{
  const platform=jest.replaceProperty(Platform,'OS',os);
  function Editor(){const [value,setValue]=useState('');return <TextInput testID="sheet.editor" value={value} onChangeText={setValue}/>;}
  const content=(empty:boolean)=><HeaderHeightContext.Provider value={64}><Sheet
    header={pinned?<Text>Search controls</Text>:undefined} footer={<Text testID="sheet.footer.action">Confirm</Text>}>
    <Editor/><Text>{empty?'No results':'A result list'}</Text>
  </Sheet></HeaderHeightContext.Provider>;
  const view=render(content(false));
  try{
    const scroll=screen.UNSAFE_getByType(ScrollView),editor=screen.getByTestId('sheet.editor'),inset=os==='ios'?64:0;
    fireEvent(scroll,'layout',{nativeEvent:{layout:{x:0,y:0,width:390,height:700}}});
    fireEvent.changeText(editor,'Retained draft');
    expect(scroll.props).toMatchObject({automaticallyAdjustKeyboardInsets:true,automaticallyAdjustContentInsets:false,
      contentInsetAdjustmentBehavior:'never',contentInset:{top:inset},scrollIndicatorInsets:{top:inset},contentOffset:{x:0,y:-inset},
      keyboardShouldPersistTaps:'handled',keyboardDismissMode:'interactive',pointerEvents:'auto',importantForAccessibility:'auto'});
    expect(StyleSheet.flatten(scroll.props.contentContainerStyle).minHeight).toBe(700-inset);
    if(pinned)expect(scroll.props.stickyHeaderIndices).toEqual([0]);
    else expect(scroll.props.stickyHeaderIndices).toBeUndefined();
    view.rerender(content(true));
    expect(screen.getByText('No results')).toBeOnTheScreen();
    expect(screen.UNSAFE_getByType(ScrollView)).toBe(scroll);expect(screen.getByTestId('sheet.editor')).toBe(editor);
    expect(StyleSheet.flatten(scroll.props.contentContainerStyle).minHeight).toBe(700-inset);
    // The actual smaller native viewport delivered with the keyboard updates
    // the lower bound; no content-driven remount replaces the accepted draft.
    fireEvent(scroll,'layout',{nativeEvent:{layout:{x:0,y:0,width:390,height:340}}});
    expect(StyleSheet.flatten(scroll.props.contentContainerStyle).minHeight).toBe(340-inset);
    view.rerender(content(false));
    expect(screen.UNSAFE_getByType(ScrollView)).toBe(scroll);expect(editor.props.value).toBe('Retained draft');
    expect(StyleSheet.flatten(scroll.props.contentContainerStyle).height).toBeUndefined();
    if(os==='android'){
      const footer=screen.getByTestId('sheet.footer.action');
      for(let ancestor=footer.parent;ancestor;ancestor=ancestor.parent)expect(ancestor).not.toBe(scroll);
      expect(mockSheetNavigation.addListener.mock.calls).toEqual([]);
    }
  }finally{view.unmount();platform.restore();mockSheetNavigation.addListener.mockClear();}
});

test('an unpinned iOS form settles its initial measured header inset once and preserves later scrolling',()=>{
  const platform=jest.replaceProperty(Platform,'OS','ios'),scrollRef=createRef<ScrollViewInstance>();
  const view=render(<HeaderHeightContext.Provider value={56}><Sheet scrollRef={scrollRef}><Text>Short form</Text></Sheet></HeaderHeightContext.Provider>);
  try{
    const scroll=screen.UNSAFE_getByType(ScrollView),scrollTo=jest.spyOn(scrollRef.current!,'scrollTo').mockImplementation(()=>{});
    const transition=mockSheetNavigation.addListener.mock.calls.at(-1)![1];
    act(()=>transition({data:{closing:false}}));
    expect(scrollTo).toHaveBeenCalledTimes(1);expect(scrollTo).toHaveBeenCalledWith({x:0,y:-56,animated:false});
    fireEvent.scroll(scroll,{nativeEvent:{contentOffset:{x:0,y:180}}});
    act(()=>transition({data:{closing:false}}));
    expect(scrollTo).toHaveBeenCalledTimes(1);
    fireEvent(scroll,'layout',{nativeEvent:{layout:{x:0,y:0,width:390,height:20}}});
    expect(StyleSheet.flatten(scroll.props.contentContainerStyle).minHeight).toBe(0);
    scrollTo.mockRestore();
  }finally{view.unmount();platform.restore();mockSheetNavigation.addListener.mockClear();}
});

test.each(['android','ios'] as const)('%s catalog results have a natural content range while keeping the native footer adapter',os=>{
  const platform=jest.replaceProperty(Platform,'OS',os);
  const view=render(<HeaderHeightContext.Provider value={64}><Sheet scrollMode="list" header={<Text>Catalog controls</Text>}
    footer={<Text testID="list.footer.action">Confirm selection</Text>}><Text>One result</Text></Sheet></HeaderHeightContext.Provider>);
  try{
    const scroll=screen.getByTestId('sheet.results');
    expect(StyleSheet.flatten(scroll.props.contentContainerStyle).minHeight).toBeUndefined();
    expect(scroll.props.contentInsetAdjustmentBehavior).toBe(os==='ios'?'automatic':'never');
    expect(scroll.props.contentInset).toBeUndefined();expect(scroll.props.contentOffset).toBeUndefined();
    expect(scroll.props.stickyHeaderIndices).toBeUndefined();
    expect(scroll.props).toMatchObject({bounces:false,alwaysBounceVertical:false,automaticallyAdjustKeyboardInsets:true});
    if(os==='android'){
      const footer=screen.getByTestId('list.footer.action');
      for(let ancestor=footer.parent;ancestor;ancestor=ancestor.parent)expect(ancestor).not.toBe(scroll);
      expect(mockSheetNavigation.addListener.mock.calls).toEqual([]);
    }else expect(mockSheetNavigation.setOptions).toHaveBeenLastCalledWith(expect.objectContaining({unstable_sheetFooter:expect.any(Function)}));
  }finally{view.unmount();platform.restore();mockSheetNavigation.addListener.mockClear();}
});
