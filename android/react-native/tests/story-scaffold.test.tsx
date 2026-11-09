import React from 'react';
import {act, fireEvent, render, screen} from '@testing-library/react-native';
import {Animated, AppState, Platform, ScrollView,StyleSheet,Text,View} from 'react-native';

const mockCatalogNavigation={setOptions:jest.fn(),addListener:jest.fn(()=>()=>{})};
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => mockCatalogNavigation,
  useIsFocused: () => true,
}));

let mockReduceMotion = false;
jest.mock('../canonical/review/navigation-scaffold', () => ({...jest.requireActual('../canonical/review/navigation-scaffold'),useReducedMotionPreference: () => mockReduceMotion}));

import {ProtectionHero, ConnectionScene,ConnectionPath,ConnectionPanel,CategoryLinks,CatalogSheet} from '../canonical/review/story-scaffold';
import {GuardMaterial, guardDuration} from '../canonical/review/guard-material';
import {connectionAttention} from '../canonical/review/connection-reveal';
import {connectionStages,type ConnectionStage} from '../canonical/review/connection-model';
import {configurePresentation,localized} from '../canonical/app/presentation';
import {initialSession} from '../canonical/review/session';
import Decoration from '../canonical/specs/LavaDecorationNativeComponent';
import {LavaPlusStory} from '../canonical/review/plus-scaffold';

beforeEach(() => {mockReduceMotion = false;});
import {LavaActionButton} from '../canonical/src';
import {Search,Sheet} from '../canonical/review/scaffold';
import {HeaderHeightContext} from '@react-navigation/elements';
import {BufferedInput} from '../canonical/review/BufferedInput';
import {LavaAppearanceContext} from '../canonical/src/appearance';
import {foundation} from '../canonical/src/foundation';
import {AndroidFieldPresentationContext} from '../canonical/review/presentation-owner.android';

test.each(['android','ios'] as const)('the %s Plus story uses the shared benefits and truthful included capabilities', os => {
  const original=Platform.OS;
  Platform.OS=os;
  const tree=render(<LavaPlusStory/>);
  try {
    fireEvent(screen.getByTestId('carousel.surface'),'layout',{nativeEvent:{layout:{x:0,y:0,width:320,height:360}}});
    expect(screen.getByTestId('plus.scene.routines')).toBeTruthy();
    expect(screen.getByTestId('plus.scene.connection',{includeHiddenElements:true})).toBeTruthy();
    expect(screen.getByTestId('plus.scene.guards',{includeHiddenElements:true})).toBeTruthy();
    expect(screen.getByRole('tab',{name:'Room for more'}).props.accessibilityState.selected).toBe(true);
    fireEvent.press(screen.getByRole('tab',{name:'Choose how you connect'}));
    expect(screen.getByRole('tab',{name:'Choose how you connect'}).props.accessibilityState.selected).toBe(true);
    expect(screen.getByTestId('carousel.page.room',{includeHiddenElements:true}).props.importantForAccessibility).toBe('no-hide-descendants');
    expect(screen.getByTestId('carousel.page.connection').props.importantForAccessibility).toBe('auto');
    fireEvent.press(screen.getByRole('button',{name:'Everything included'}));
    for(const capability of ['Custom blocklists','Custom DNS','VPN chaining','All Lava Guards']){
      expect(screen.getAllByText(capability).length).toBeGreaterThan(0);
    }
    expect(screen.queryByText('Not available in this build')).toBeNull();
    expect(screen.queryByText('Family Sharing')!==null).toBe(os==='ios');
    expect(screen.getByText('Up to 50')).toBeTruthy();
    expect(screen.getByText('Up to 2 million')).toBeTruthy();
  } finally {tree.unmount();Platform.OS=original;}
});

// Plan 309 F20: the onboarding handoff reuses the canonical Guard panel. The
// Ready and Off states must share the same intrinsic slots so localization and
// Dynamic Type cannot shift the handoff.
test('Ready and Off share the actual Guard material, slots and accessible status', async () => {
  const props = {title: 'Protection Off', description: 'Tap once to add local protection', mascot: <View testID="destination.mascot" />, accessibilityActions: [], onAccessibilityAction: () => {}};
  const tree = render(<ProtectionHero {...props} ready action={<LavaActionButton title="Open Guard" onPress={() => {}} />} />);
  const material = screen.getByTestId('guard.material');
  expect(screen.getByLabelText('Protection status').props.accessibilityValue.text).toBe('Ready. Your next step to a safer internet.');
  tree.rerender(<ProtectionHero {...props} action={<LavaActionButton title="Turn on" onPress={() => {}} />} />);
  expect(screen.getByTestId('guard.material')).toBe(material);
  expect(screen.getByLabelText('Protection status').props.accessibilityValue.text).toBe('Protection off. Tap once to turn on protection');
  expect(screen.getByTestId('destination.mascot')).toBeTruthy();
  await act(async () => {});
});


test('Guard engage and release cross-fade in place at the frozen cadence', () => {
  const timing = jest.spyOn(Animated, 'timing');
  const originalState = AppState.currentState; AppState.currentState = 'active';
  try {
    const tree = render(<GuardMaterial intent="rest"/>);
    timing.mockClear(); tree.rerender(<GuardMaterial intent="affirmed"/>);
    expect(timing.mock.calls.map(([,config]) => config.toValue)).toEqual([1,0]);
    for (const [,config] of timing.mock.calls) expect(config.duration).toBe(500);
    timing.mockClear(); tree.rerender(<GuardMaterial intent="rest"/>);
    expect(timing.mock.calls.map(([,config]) => config.toValue)).toEqual([0,0]);
    for (const [,config] of timing.mock.calls) expect(config.duration).toBe(550);
    expect(guardDuration('affirmed','neutral','affirmed')).toBe(500);
  } finally {timing.mockRestore(); AppState.currentState = originalState;}
});

test.each(['rest','recovery'] as const)('Reduce Motion restores from %s with an opacity fade and no spatial growth', intent => {
  mockReduceMotion = true;
  const timing = jest.spyOn(Animated, 'timing');
  const originalState = AppState.currentState; AppState.currentState = 'active';
  try {
    const tree = render(<GuardMaterial intent={intent}/>);
    timing.mockClear(); tree.rerender(<GuardMaterial intent="affirmed"/>);
    expect(timing.mock.calls.map(([,config]) => config.toValue)).toEqual([1,0]);
    for (const [,config] of timing.mock.calls) expect(config.duration).toBe(150);
  } finally {timing.mockRestore(); AppState.currentState = originalState;}
});

test('enabling Reduce Motion snaps an already growing restore to its current surface', () => {
  const timing = jest.spyOn(Animated, 'timing');
  const originalState = AppState.currentState; AppState.currentState = 'active';
  try {
    const tree = render(<GuardMaterial intent="recovery"/>);
    tree.rerender(<GuardMaterial intent="affirmed"/>);
    const radius = timing.mock.calls.at(-1)![0] as Animated.Value;
    const set = jest.spyOn(radius, 'setValue');
    mockReduceMotion = true; tree.rerender(<GuardMaterial intent="affirmed"/>);
    expect(set).toHaveBeenCalledWith(1);
  } finally {timing.mockRestore(); AppState.currentState = originalState;}
});

test('narration focuses only its final described stage, including a configured but muted VPN', () => {
  const stages: ConnectionStage[] = ['phone','filter','vpn','dns'].map(id => ({
    id: id as ConnectionStage['id'], title:id, shortTitle:id, symbol:'network', value:id,
    explanation:id, setupSummary:id, muted:id==='vpn',
  }));
  expect(connectionAttention(stages, ['phone','filter','vpn'])).toBe('vpn');
  const tree = render(<ConnectionScene stages={stages} attention={['phone','filter','vpn']} onSelect={() => {}}/>);
  expect(screen.getByTestId('connection.vpn').props.accessibilityState.selected).toBe(true);
  expect(screen.getByTestId('connection.filter').props.accessibilityState.selected).toBe(false);
  const glyphs = tree.UNSAFE_getAllByType(Decoration);
  expect(glyphs[2].props.tone).toBe('green');
  expect(glyphs[0].props.tone).toBe('secondary');
  tree.rerender(<ConnectionScene stages={stages} attention={['phone','filter','vpn','dns']} onSelect={() => {}}/>);
  expect(screen.getByTestId('connection.dns').props.accessibilityState.selected).toBe(true);
  expect(screen.getByTestId('connection.vpn').props.accessibilityState.selected).toBe(false);
  expect(tree.UNSAFE_getAllByType(Decoration)[2].props.tone).toBe('secondary');
});


const catalogMaterials=()=>screen.UNSAFE_getAllByType(Decoration).filter(node=>node.props.symbol==='catalog.control.material');
test.each(['light','dark'] as const)('shared catalog search and category capsules configure noninteractive native material in %s',scheme=>{
  const changed=jest.fn(),selected=jest.fn();
  render(<LavaAppearanceContext.Provider value={scheme}><Search surface="catalog" value="typed" onChange={changed} label="Search providers" resetRevision={9}/><CategoryLinks titles={['DoH','DoT']} onSelect={selected}/></LavaAppearanceContext.Provider>);
  const materials=catalogMaterials();expect(materials).toHaveLength(3);
  for(const material of materials){
    expect(material.props).toMatchObject({symbol:'catalog.control.material',colorScheme:scheme,pointerEvents:'none',accessible:false,accessibilityElementsHidden:true,importantForAccessibility:'no-hide-descendants'});
    expect(StyleSheet.flatten(material.props.style)).toEqual({position:'absolute',top:0,right:0,bottom:0,left:0});
    expect(material.props.guardianGestures).toBeUndefined();
  }
  const input=screen.getByLabelText('Search providers');
  expect(input.props).toMatchObject({value:'typed',resetRevision:9,kind:'search',editable:true,autoCapitalize:'none',autoCorrect:false,clearButtonMode:'while-editing'});
  expect(StyleSheet.flatten(input.props.style).minHeight).toBeGreaterThanOrEqual(48);
  fireEvent(input,'change',{nativeEvent:{text:'typed more'}});expect(changed).toHaveBeenCalledWith('typed more');
  for(const title of ['DoH','DoT']){
    const category=screen.getByRole('button',{name:title}),style=StyleSheet.flatten(category.props.style);
    expect(style).toMatchObject({borderRadius:foundation.radius.circle,overflow:'hidden'});expect(style.minHeight).toBeGreaterThanOrEqual(48);
  }
  fireEvent.press(screen.getByRole('button',{name:'DoT'}));expect(selected).toHaveBeenCalledWith('DoT');
});

test('ordinary card search retains its native input owner and catalog decoration does not bypass field scope',()=>{
  const changed=jest.fn();
  const view=render(<AndroidFieldPresentationContext.Provider value={true}><Search value="card draft" onChange={changed} label="Search cards" resetRevision={3}/></AndroidFieldPresentationContext.Provider>);
  expect(catalogMaterials()).toHaveLength(0);
  const input=screen.getByLabelText('Search cards');
  expect(input.props).toMatchObject({value:'card draft',resetRevision:3,kind:'search',editable:true});
  view.rerender(<AndroidFieldPresentationContext.Provider value={false}><Search surface="catalog" value="card draft" onChange={changed} label="Search cards" resetRevision={3}/></AndroidFieldPresentationContext.Provider>);
  expect(catalogMaterials()).toHaveLength(1);
  expect(screen.getByLabelText('Search cards').props.editable).toBe(false);
  fireEvent(screen.getByLabelText('Search cards'),'change',{nativeEvent:{text:'stale edit'}});expect(changed).not.toHaveBeenCalled();
  expect(screen.UNSAFE_getByType(BufferedInput).props.resetRevision).toBe(3);
});

test('shared catalog controls remain the same pinned input and category owners through an empty result and recovery',()=>{
  const changed=jest.fn(),titles=['DoH','DoT'];
  const content=(empty:boolean)=><CatalogSheet sections={empty?[]:[{title:'DoH',items:['Cloudflare']}]} categoryTitles={titles}
    search={empty?'no-match':''} onSearch={changed} searchLabel="Search providers" renderRow={item=><Text>{item}</Text>} empty={<Text>No providers found</Text>}/>;
  const view=render(content(false)),input=screen.getByLabelText('Search providers'),controls=screen.getByTestId('catalog.controls'),category=screen.getByRole('button',{name:'DoT'});
  const style=StyleSheet.flatten(category.props.style);
  const results=screen.getByTestId('sheet.results');
  expect(results.props.stickyHeaderIndices).toBeUndefined();
  for(let ancestor=controls.parent;ancestor;ancestor=ancestor.parent)expect(ancestor).not.toBe(results);
  expect(screen.UNSAFE_getAllByType(Decoration).some(node=>node.props.symbol==='sheet.header.material')).toBe(false);
  view.rerender(content(true));
  expect(screen.getByText('No providers found')).toBeOnTheScreen();
  expect(screen.getByLabelText('Search providers')).toBe(input);expect(screen.getByTestId('catalog.controls')).toBe(controls);
  expect(screen.getByRole('button',{name:'DoT'})).toBe(category);expect(StyleSheet.flatten(category.props.style)).toEqual(style);
  expect(catalogMaterials()).toHaveLength(3);
  fireEvent(input,'change',{nativeEvent:{text:''}});expect(changed).toHaveBeenCalledWith('');
  view.rerender(content(false));
  expect(screen.queryByText('No providers found')).toBeNull();expect(screen.getByText('Cloudflare')).toBeOnTheScreen();
  expect(screen.getByLabelText('Search providers')).toBe(input);expect(screen.getByRole('button',{name:'DoT'})).toBe(category);
});


// #874 uses the shared result owner: a changed search reveals its results,
// while selection updates and a retained return from a child leave scroll alone.
test.each(['android','ios'] as const)('%s catalog resets only a changed search and retains selection/child-return offsets',os=>{
  const platform=jest.replaceProperty(Platform,'OS',os),changed=jest.fn();
  const content=(search:string,selected=false,returned=false,header=56)=><HeaderHeightContext.Provider value={header}>
    <CatalogSheet sections={search?[ ]:[{title:'All',items:['A result']}]} categoryTitles={['All']}
      search={search} onSearch={changed} searchLabel="Search results"
      renderRow={item=><Text key={item}>{selected?'Selected result':item}</Text>} empty={<Text>No results</Text>}
      footer={<Text>{returned?'Returned from child':'Choose'}</Text>}/>
  </HeaderHeightContext.Provider>;
  const view=render(content(''));
  try{
    const sheet=screen.UNSAFE_getByType(Sheet),ref=sheet.props.scrollRef;
    const scrollTo=jest.spyOn(ref.current,'scrollTo').mockImplementation(()=>{}),input=screen.getByLabelText('Search results');
    const scroll=screen.UNSAFE_getAllByType(ScrollView).find(node=>!node.props.horizontal)!;
    scrollTo.mockClear();fireEvent.scroll(scroll,{nativeEvent:{contentOffset:{x:0,y:300}}});
    view.rerender(content('',true));view.rerender(content('',true,true));
    expect(screen.getByText('Selected result')).toBeOnTheScreen();expect(sheet.props.footer.props.children).toBe('Returned from child');
    expect(scrollTo).not.toHaveBeenCalled();expect(screen.getByLabelText('Search results')).toBe(input);
    view.rerender(content('no match',true,true));
    expect(screen.getByText('No results')).toBeOnTheScreen();
    expect(scrollTo).toHaveBeenCalledTimes(1);expect(scrollTo).toHaveBeenLastCalledWith({y:os==='ios'?-56:0,animated:false});
    view.rerender(content('no match',false,false));view.rerender(content('no match',false,true,70));
    expect(scrollTo).toHaveBeenCalledTimes(1);
    view.rerender(content('',false,true,70));
    expect(scrollTo).toHaveBeenCalledTimes(2);expect(scrollTo).toHaveBeenLastCalledWith({y:os==='ios'?-70:0,animated:false});
    expect(screen.UNSAFE_getAllByType(ScrollView).find(node=>!node.props.horizontal)).toBe(scroll);
    expect(screen.getByLabelText('Search results')).toBe(input);
    scrollTo.mockRestore();
  }finally{view.unmount();platform.restore();}
});

// #878 / 39415e50: result content scrolls below sibling controls. RNTL
// drives the pinned controls' consumed measurements and result-count changes;
// native keyboard, rotation and pixel/gesture qualification stay separate.
test.each(['android','ios'] as const)('%s catalog retains natural results and owners across controls measurements and result counts',os=>{
  const platform=jest.replaceProperty(Platform,'OS',os);
  const content=(count:number,header=70)=><HeaderHeightContext.Provider value={header}>
    <CatalogSheet sections={count?[{title:'All',items:Array.from({length:count},(_,i)=>`Result ${i}`)}]:[]}
      categoryTitles={['All']} search="" onSearch={()=>{}} searchLabel="Search stable results"
      renderRow={item=><Text key={item}>{item}</Text>} empty={<Text>Empty catalog</Text>}/>
  </HeaderHeightContext.Provider>;
  const view=render(content(30));
  try{
    const results=screen.getByTestId('sheet.results'),controls=screen.getByTestId('sheet.pinned-header');
    const input=screen.getByLabelText('Search stable results'),category=screen.getByRole('button',{name:'All'});
    const inset=os==='ios'?70:0;
    for(let ancestor=controls.parent;ancestor;ancestor=ancestor.parent)expect(ancestor).not.toBe(results);
    expect(StyleSheet.flatten(controls.props.style)).toMatchObject({position:'absolute',top:inset});
    expect(StyleSheet.flatten(results.props.style).marginTop).toBe(inset);
    fireEvent(controls,'layout',{nativeEvent:{layout:{x:0,y:inset,width:390,height:128}}});
    expect(results.props.scrollIndicatorInsets).toEqual({top:128});
    expect(results.findAllByType(View).filter((node:{props:React.ComponentProps<typeof View>})=>StyleSheet.flatten(node.props.style)?.height===128)).toHaveLength(1);
    for(const count of [1,0,30]){
      view.rerender(content(count));
      for(const controlsHeight of [128,72]){
        fireEvent(controls,'layout',{nativeEvent:{layout:{x:0,y:inset,width:390,height:controlsHeight}}});
        expect(results.props.scrollIndicatorInsets).toEqual({top:controlsHeight});
        expect(results.findAllByType(View).filter((node:{props:React.ComponentProps<typeof View>})=>StyleSheet.flatten(node.props.style)?.height===controlsHeight)).toHaveLength(1);
        expect(StyleSheet.flatten(results.props.contentContainerStyle).minHeight).toBeUndefined();
        expect(results.props).toMatchObject({bounces:false,alwaysBounceVertical:false,automaticallyAdjustKeyboardInsets:true,
          keyboardShouldPersistTaps:'handled',keyboardDismissMode:'interactive'});
        expect(results.props.contentInset).toBeUndefined();expect(results.props.contentOffset).toBeUndefined();
        expect(results.props.stickyHeaderIndices).toBeUndefined();
        expect(screen.getByTestId('sheet.results')).toBe(results);
        expect(screen.getByLabelText('Search stable results')).toBe(input);
        expect(screen.getByRole('button',{name:'All'})).toBe(category);
      }
    }
    view.rerender(content(1,54));
    expect(StyleSheet.flatten(controls.props.style).top).toBe(os==='ios'?54:0);
    expect(StyleSheet.flatten(results.props.style).marginTop).toBe(os==='ios'?54:0);
    expect(screen.getByTestId('sheet.results')).toBe(results);expect(screen.getByLabelText('Search stable results')).toBe(input);
  }finally{view.unmount();platform.restore();}
});

test('iOS catalog category navigation accounts for its native overlay header',()=>{
  const platform=jest.replaceProperty(Platform,'OS','ios');
  const view=render(<HeaderHeightContext.Provider value={70}><CatalogSheet
    sections={[{title:'DoH',items:['One']},{title:'DoT',items:['Two']}]} search="" onSearch={()=>{}}
    searchLabel="Search categories" renderRow={item=><Text>{item}</Text>}/></HeaderHeightContext.Provider>);
  try{
    const ref=screen.UNSAFE_getByType(Sheet).props.scrollRef;
    const scrollTo=jest.spyOn(ref.current,'scrollTo').mockImplementation(()=>{});
    let section=screen.getByText('Two').parent;
    while(section&&typeof section.props.onLayout!=='function')section=section.parent;
    expect(section).not.toBeNull();
    fireEvent(section!,'layout',{nativeEvent:{layout:{x:0,y:240,width:390,height:100}}});
    fireEvent.press(screen.getByRole('button',{name:'DoT'}));
    expect(scrollTo).toHaveBeenLastCalledWith({y:170,animated:true});scrollTo.mockRestore();
  }finally{view.unmount();platform.restore();}
});


test('connection stages localize app copy before composition and keep provider identities verbatim in the actual path',()=>{
  configurePresentation({locale:'de',textScales:null});
  try {
    const stages=connectionStages(undefined,{...initialSession(),deviceDNS:true,fallback:true});
    expect(stages.find(stage=>stage.id==='phone')?.value).toBe(localized('This device'));
    expect(stages.find(stage=>stage.id==='dns')?.value).toBe('Geräte-DNS');
    expect(stages.find(stage=>stage.id==='dns')?.detail).toBe(localized('Fallback enabled'));
    const dns:ConnectionStage={id:'dns',title:'DNS',shortTitle:'DNS',symbol:'network',value:'Cancel',detail:'Save',explanation:'DNS finds a website’s address.',setupSummary:'',destination:'DNS'};
    const view=render(<ConnectionPath stages={[dns]} expanded onSelect={()=>{}}/>);
    expect(screen.getByRole('button',{name:'DNS, Cancel'})).toBeTruthy();
    expect(screen.getByText('Cancel')).toBeTruthy();
    expect(screen.getByText('Save')).toBeTruthy();
    expect(screen.queryByText(localized('Cancel'))).toBeNull();
    view.unmount();
  } finally {configurePresentation({locale:'en',textScales:null});}
});

test('expanded connection summary does not translate a provider identity or endpoint metadata again',()=>{
  configurePresentation({locale:'de',textScales:null});
  const dimensions=jest.spyOn(require('react-native'),'useWindowDimensions').mockReturnValue({width:1024,height:700,scale:1,fontScale:1});
  try {
    const dns:ConnectionStage={id:'dns',title:'DNS',shortTitle:'DNS',symbol:'network',value:'Cancel',detail:'Save',explanation:'',setupSummary:'',destination:'DNS'};
    render(<ConnectionPanel stages={[dns]} onSelect={()=>{}} onExplore={()=>{}}/>);
    expect(screen.getByText('Cancel\nSave')).toBeTruthy();
    expect(screen.queryByText(`${localized('Cancel')}\n${localized('Save')}`)).toBeNull();
  } finally {dimensions.mockRestore();configurePresentation({locale:'en',textScales:null});}
});
