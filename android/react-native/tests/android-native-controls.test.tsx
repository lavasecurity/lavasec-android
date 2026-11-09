import React from 'react';
import {render,fireEvent} from '@testing-library/react-native';
import {Alert,Pressable} from 'react-native';

// `LavaNativePage`/`LavaDecoration` import the shared primitives, which read
// navigation focus. The leaf controls do not navigate; stub the hook surface.
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({navigate: jest.fn(), goBack: jest.fn(), setOptions: jest.fn()}),
  useIsFocused: () => true,
  useRoute: () => ({params: undefined}),
  usePreventRemove: jest.fn(),
}));

import Choice from '../canonical/specs/LavaChoiceNativeComponent';
import Switch from '../canonical/specs/LavaSwitchNativeComponent';
import Decoration from '../canonical/specs/LavaDecorationNativeComponent';
import Slider from '../canonical/specs/LavaSliderNativeComponent';
import NativePage from '../canonical/specs/LavaNativePageNativeComponent';
import ContextMenu from '../canonical/specs/LavaContextMenuNativeComponent';
import {AppearanceStore} from '../canonical/review/appearance-store';
import {configurePresentation} from '../canonical/app/presentation';

const options=[{value:'a',label:'A',},{value:'b',label:'B'},{value:'c',label:'C'}];

describe('LavaChoice',()=>{
  test('holds an unresolved selection and reconciles on a selectionRevision bump',()=>{
    const change=jest.fn();
    const screen=render(<Choice label="X" controlID="x" options={options} value="a" disabled={false} onValueChange={change}/>);
    fireEvent.press(screen.getByRole('tab',{name:'B'}));
    expect(change).toHaveBeenCalledWith({nativeEvent:{value:'b'}});
    expect(screen.getByRole('tab',{name:'B'}).props.accessibilityState.selected).toBe(true);
    // The owner rejected the update (same authoritative value, new revision).
    screen.rerender(<Choice label="X" controlID="x" options={options} value="a" selectionRevision={1} disabled={false} onValueChange={change}/>);
    expect(screen.getByRole('tab',{name:'A'}).props.accessibilityState.selected).toBe(true);
  });

  test('a reselectValue can re-emit an already-selected option',()=>{
    const change=jest.fn();
    const screen=render(<Choice label="X" controlID="x" options={options} value="a" reselectValue="a" disabled={false} onValueChange={change}/>);
    fireEvent.press(screen.getByRole('tab',{name:'A'}));
    expect(change).toHaveBeenCalledWith({nativeEvent:{value:'a'}});
  });

  test('a disabled choice emits nothing and marks its tabs disabled',()=>{
    const change=jest.fn();
    const screen=render(<Choice label="X" controlID="x" options={options} value="a" disabled onValueChange={change}/>);
    fireEvent.press(screen.getByRole('tab',{name:'B'}));
    expect(change).not.toHaveBeenCalled();
    expect(screen.getByRole('tab',{name:'A'}).props.accessibilityState.disabled).toBe(true);
  });

  test('renders a stepper and steps through options',()=>{
    const change=jest.fn();
    const screen=render(<Choice label="Pause" controlID="pause" options={options} value="b" stepper disabled={false} onValueChange={change}/>);
    fireEvent.press(screen.getByLabelText('Pause Next'));
    expect(change).toHaveBeenCalledWith({nativeEvent:{value:'c'}});
    change.mockClear();
    // The stepper steps from the value it is currently showing (the held 'c').
    fireEvent.press(screen.getByLabelText('Pause Previous'));
    expect(change).toHaveBeenCalledWith({nativeEvent:{value:'b'}});
  });

  test('stepper adjustment labels use the frozen locale and retain boundary and event authority',()=>{
    configurePresentation({locale:'de',textScales:null});
    const change=jest.fn();
    const screen=render(<Choice label="Pause" controlID="pause" options={options} value="a" stepper disabled={false} onValueChange={change}/>);
    try{
      const previous=screen.getByRole('button',{name:'Pause Zurück'});
      expect(previous.props.accessibilityState.disabled).toBe(true);
      fireEvent.press(previous);
      expect(change).not.toHaveBeenCalled();
      fireEvent.press(screen.getByRole('button',{name:'Pause Weiter'}));
      expect(change).toHaveBeenCalledTimes(1);
      expect(change).toHaveBeenCalledWith({nativeEvent:{value:'b'}});
      screen.rerender(<Choice label="Pause" controlID="pause" options={options} value="a" selectionRevision={1} stepper disabled={false} onValueChange={change}/>);
      expect(screen.getByRole('button',{name:'Pause Zurück'}).props.accessibilityState.disabled).toBe(true);
      expect(screen.getByText('A')).toBeTruthy();
    }finally{screen.unmount();configurePresentation();}
  });

  test('renders each real page position and reconciles the selected page after refusal',()=>{
    const change=jest.fn();
    const screen=render(<Choice label="Pages" controlID="pages" options={options} value="a" pageControl disabled={false} onValueChange={change}/>);
    for(const [index,option] of options.entries()){
      expect(screen.getByRole('tab',{name:option.label}).props.accessibilityValue).toEqual({min:1,max:3,now:index+1});
    }
    fireEvent.press(screen.getByRole('tab',{name:'B'}));
    expect(change).toHaveBeenCalledWith({nativeEvent:{value:'b'}});
    expect(change).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('tab',{name:'B'}).props.accessibilityState.selected).toBe(true);
    screen.rerender(<Choice label="Pages" controlID="pages" options={options} value="a" selectionRevision={1} pageControl disabled={false} onValueChange={change}/>);
    expect(screen.getByRole('tab',{name:'A'}).props.accessibilityState.selected).toBe(true);
    expect(screen.getByRole('tab',{name:'B'}).props.accessibilityState.selected).toBe(false);
    expect(screen.getByRole('tab',{name:'B'}).props.accessibilityValue.now).toBe(2);
  });

  test('an absent page value fabricates no selected position and disabled pages emit nothing',()=>{
    const change=jest.fn();
    const screen=render(<Choice label="Pages" controlID="pages" options={options} value="retired-page" pageControl disabled onValueChange={change}/>);
    for(const option of options){
      const tab=screen.getByRole('tab',{name:option.label});
      expect(tab.props.accessibilityState).toEqual({selected:false,disabled:true});
      fireEvent.press(tab);
    }
    expect(change).not.toHaveBeenCalled();
  });

  test('measures itself through onSizeChange',()=>{
    const size=jest.fn();
    const screen=render(<Choice label="X" controlID="x" testID="x" options={options} value="a" disabled={false} onSizeChange={size}/>);
    fireEvent(screen.getByTestId('x'),'layout',{nativeEvent:{layout:{x:0,y:0,width:120,height:52}}});
    expect(size).toHaveBeenCalledWith({nativeEvent:{height:52}});
  });
});

describe('LavaSwitch',()=>{
  test('holds the optimistic value until resetRevision reconciles it',()=>{
    const change=jest.fn();
    const screen=render(<Switch label="S" value={false} optimistic disabled={false} pending={false} resetRevision={0} onValueChange={change}/>);
    fireEvent(screen.getByLabelText('S'),'valueChange',true);
    expect(change).toHaveBeenCalledWith({nativeEvent:{value:true}});
    expect(screen.getByLabelText('S').props.on).toBe(true);
    screen.rerender(<Switch label="S" value={false} optimistic disabled={false} pending={false} resetRevision={1} onValueChange={change}/>);
    expect(screen.getByLabelText('S').props.on).toBe(false);
  });

  test('pending and disabled both lock the control',()=>{
    const screen=render(<Switch label="S" value={false} optimistic disabled={false} pending resetRevision={0}/>);
    expect(screen.getByLabelText('S').props.enabled).toBe(false);
    screen.rerender(<Switch label="S" value={false} optimistic disabled pending={false} resetRevision={0}/>);
    expect(screen.getByLabelText('S').props.enabled).toBe(false);
  });

  test('measures itself through onSizeChange',()=>{
    const size=jest.fn();
    const screen=render(<Switch label="S" value={false} optimistic disabled={false} pending={false} resetRevision={0} onSizeChange={size}/>);
    fireEvent(screen.getByLabelText('S'),'layout',{nativeEvent:{layout:{x:0,y:0,width:52,height:32}}});
    expect(size).toHaveBeenCalledWith({nativeEvent:{width:52,height:32}});
  });
});

describe('LavaDecoration',()=>{
  test('forwards look/mood/tone to the native leaf',()=>{
    const screen=render(<Decoration testID="green" mood="awake" tone="green" look="emerald"/>);
    const el=screen.getByTestId('green');
    expect(el.props.mood).toBe('awake');
    expect(el.props.look).toBe('emerald');
    expect(el.props.tone).toBe('green');
  });

  test('forwards each native cancelled contact, tap and hold once without replacing its event',()=>{
    const gesture=jest.fn();
    const screen=render(<Decoration testID="mascot" mood="awake" guardianGestures onGuardianGesture={gesture}/>);
    const el=screen.getByTestId('mascot');
    const delivered:Array<{nativeEvent:{gesture:string}}>=[];
    const native=(value:string)=>{
      const event={nativeEvent:{gesture:value}};
      delivered.push(event);
      fireEvent(el,'guardianGesture',event);
      expect(gesture).toHaveBeenCalledTimes(delivered.length);
      expect(gesture.mock.calls[delivered.length-1]![0]).toBe(event);
    };
    // Native cancellation has contact edges and no tap.
    native('start');native('end');
    expect(gesture.mock.calls.filter(call=>call[0].nativeEvent.gesture==='tap')).toHaveLength(0);
    for(let i=0;i<4;i++){native('start');native('end');native('tap');}
    expect(gesture.mock.calls.filter(call=>call[0].nativeEvent.gesture==='tap')).toHaveLength(4);
    native('start');native('end');native('tap');
    expect(gesture.mock.calls.filter(call=>call[0].nativeEvent.gesture==='tap')).toHaveLength(5);
    native('start');native('end');native('reveal');
    expect(gesture.mock.calls.filter(call=>call[0].nativeEvent.gesture==='reveal')).toHaveLength(1);
    expect(gesture.mock.calls.map(call=>call[0])).toEqual(delivered);
  });

  test('JavaScript press and hold events cannot synthesize or duplicate native gestures',()=>{
    jest.useFakeTimers();
    const gesture=jest.fn();
    const screen=render(<Decoration testID="mascot" mood="awake" guardianGestures onGuardianGesture={gesture}/>);
    try{
      const el=screen.getByTestId('mascot');
      fireEvent(el,'pressIn');
      fireEvent(el,'press');
      fireEvent(el,'pressOut');
      fireEvent(el,'longPress');
      jest.runOnlyPendingTimers();
      expect(gesture).not.toHaveBeenCalled();
      expect(screen.UNSAFE_queryAllByType(Pressable)).toHaveLength(0);
      const nativeTap={nativeEvent:{gesture:'tap'}};
      fireEvent(el,'guardianGesture',nativeTap);
      fireEvent(el,'press');
      expect(gesture).toHaveBeenCalledTimes(1);
      expect(gesture).toHaveBeenCalledWith(nativeTap);
    }finally{screen.unmount();jest.useRealTimers();}
  });

  test('native contact authority and original style survive enabled and ordinary decoration modes',()=>{
    const gesture=jest.fn();
    const style={width:96,height:96};
    const screen=render(<Decoration testID="mascot" mood="awake" look="emerald" style={style}
      guardianGestures onGuardianGesture={gesture}/>);
    expect(screen.getByTestId('mascot').props.style).toBe(style);
    expect(screen.getByTestId('mascot').props.guardianGestures).toBe(true);
    expect(screen.getByTestId('mascot').props.onGuardianGesture).toBe(gesture);
    screen.rerender(<Decoration testID="mascot" mood="asleep" look="emerald" style={style}
      guardianGestures={false} onGuardianGesture={gesture}/>);
    const el=screen.getByTestId('mascot');
    expect(el.props.style).toBe(style);
    expect(el.props.guardianGestures).toBe(false);
    expect(el.props.mood).toBe('asleep');
    expect(el.props.look).toBe('emerald');
    expect(el.props.onGuardianGesture).toBe(gesture);
    fireEvent(el,'pressIn');fireEvent(el,'press');fireEvent(el,'longPress');fireEvent(el,'pressOut');
    expect(gesture).not.toHaveBeenCalled();
  });

});

describe('LavaSlider',()=>{
  test('forwards its declared props to the native host',()=>{
    const screen=render(<Slider testID="slider" label="Text Size" value={3} maximum={6} disabled={false} tintColor="#428260"/>);
    const el=screen.getByTestId('slider');
    expect(el.props.value).toBe(3);
    expect(el.props.maximum).toBe(6);
    expect(el.props.disabled).toBe(false);
    expect(el.props.tintColor).toBe('#428260');
  });

  test('surfaces drag start/stop and value events',()=>{
    const change=jest.fn();
    const tracking=jest.fn();
    const screen=render(<Slider testID="slider" label="Text Size" value={3} maximum={6} disabled={false} onValueChange={change} onTrackingChange={tracking}/>);
    const el=screen.getByTestId('slider');
    fireEvent(el,'trackingChange',{nativeEvent:{tracking:true}});
    fireEvent(el,'valueChange',{nativeEvent:{value:4}});
    fireEvent(el,'trackingChange',{nativeEvent:{tracking:false}});
    expect(tracking.mock.calls.map(call=>call[0].nativeEvent.tracking)).toEqual([true,false]);
    expect(change).toHaveBeenCalledWith({nativeEvent:{value:4}});
  });
});

describe('LavaContextMenu',()=>{
  test('localized native menu pages retain supplied actions and cancellation emits nothing',()=>{
    configurePresentation({locale:'de',textScales:null});
    const alert=jest.spyOn(Alert,'alert').mockImplementation(()=>{});
    const action=jest.fn();
    const actions=[{id:'copy',title:'Kopieren',symbol:'doc.on.doc'},
      {id:'blocked',title:'Blockieren',symbol:'xmark'},{id:'allowed',title:'Zulassen',symbol:'checkmark'}];
    const screen=render(<ContextMenu contextID="menu" testID="menu" actions={actions} onAction={action}/>);
    try{
      fireEvent(screen.getByTestId('menu'),'longPress');
      expect(alert).toHaveBeenCalledTimes(1);
      const first=alert.mock.calls[0]!;
      expect(first[0]).toBe('Optionen');
      expect(first[2]?.map(button=>button.text)).toEqual(['Kopieren','Weitere Optionen','Abbrechen']);
      expect(first[2]![2]!.style).toBe('cancel');
      first[2]![1]!.onPress!();
      expect(alert).toHaveBeenCalledTimes(2);
      const second=alert.mock.calls[1]!;
      expect(second[0]).toBe('Optionen');
      expect(second[2]?.map(button=>button.text)).toEqual(['Blockieren','Zulassen','Abbrechen']);
      expect(second[2]![2]!.style).toBe('cancel');
      // Android handles its Cancel button without a consumer action callback.
      second[2]![2]!.onPress?.();
      expect(action).not.toHaveBeenCalled();
      fireEvent(screen.getByTestId('menu'),'longPress');
      alert.mock.calls[2]![2]![1]!.onPress!();
      alert.mock.calls[3]![2]![0]!.onPress!();
      expect(action).toHaveBeenCalledTimes(1);
      expect(action).toHaveBeenCalledWith({nativeEvent:{id:'blocked'}});
      expect(alert.mock.calls.every(call=>call[2]!.length<=3)).toBe(true);
    }finally{screen.unmount();alert.mockRestore();configurePresentation();}
  });

  test('a supplied row label remains verbatim and an empty action set opens no menu',()=>{
    configurePresentation({locale:'de',textScales:null});
    const alert=jest.spyOn(Alert,'alert').mockImplementation(()=>{});
    const action=jest.fn();
    const screen=render(<ContextMenu contextID="row" testID="row" accessibilityLabel="component-menu.invalid, blocked"
      actions={[{id:'copy',title:'Kopieren',symbol:'doc.on.doc'}]} onAction={action}/>);
    try{
      fireEvent(screen.getByTestId('row'),'longPress');
      expect(alert.mock.calls[0]![0]).toBe('component-menu.invalid, blocked');
      expect(alert.mock.calls[0]![2]?.map(button=>button.text)).toEqual(['Kopieren','Abbrechen']);
      alert.mock.calls[0]![2]![1]!.onPress?.();
      expect(action).not.toHaveBeenCalled();
      screen.rerender(<ContextMenu contextID="row" testID="row" accessibilityLabel="component-menu.invalid, blocked" actions={[]} onAction={action}/>);
      fireEvent(screen.getByTestId('row'),'longPress');
      expect(alert).toHaveBeenCalledTimes(1);
    }finally{screen.unmount();alert.mockRestore();configurePresentation();}
  });
});

describe('LavaNativePage',()=>{
  test('forwards page/focused to the native containment host',()=>{
    const screen=render(<NativePage page="automation" focused testID="auto-switch-page"/>);
    const el=screen.getByTestId('auto-switch-page');
    expect(el.props.page).toBe('automation');
    expect(el.props.focused).toBe(true);
  });

  test('surfaces back and navigate events from the native page',()=>{
    const back=jest.fn();
    const navigate=jest.fn();
    const screen=render(<NativePage page="dnsPatch" focused testID="dns-patch-page" onBack={back} onNavigate={navigate}/>);
    const el=screen.getByTestId('dns-patch-page');
    fireEvent(el,'back',{nativeEvent:{}});
    fireEvent(el,'navigate',{nativeEvent:{destination:'VPNChaining'}});
    expect(back).toHaveBeenCalledTimes(1);
    expect(navigate).toHaveBeenCalledWith({nativeEvent:{destination:'VPNChaining'}});
  });
});

describe('AppearanceStore launch seed',()=>{
  const port=()=>({
    getSnapshot:jest.fn().mockResolvedValue({preference:'dark',revision:4}),
    setPreference:jest.fn().mockResolvedValue({preference:'light',revision:5}),
    onSnapshot:jest.fn().mockReturnValue({remove:jest.fn()}),
  });

  test('exposes the stored preference before the first native read resolves',()=>{
    const store=new AppearanceStore(port(),{preference:'dark'});
    expect(store.getSnapshot().snapshot).toEqual({preference:'dark',revision:0});
    expect(store.getSnapshot().error).toBeNull();
  });

  test('ignores an unknown seed preference',()=>{
    const store=new AppearanceStore(port(),{preference:'sepia'});
    expect(store.getSnapshot().snapshot).toBeNull();
  });

  test('the connecting refresh reconciles the seed with the native revision',async()=>{
    const p=port();
    const store=new AppearanceStore(p,{preference:'dark'});
    const disconnect=store.connect();
    await Promise.resolve();
    await Promise.resolve();
    expect(store.getSnapshot().snapshot).toEqual({preference:'dark',revision:4});
    disconnect();
  });
});
