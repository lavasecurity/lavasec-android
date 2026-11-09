import React from 'react';
import {act,fireEvent,renderAsync,screen,waitFor} from '@testing-library/react-native';
import {DeviceEventEmitter,NativeModules,StyleSheet} from 'react-native';
import QARoot,{parseQAPresentation} from '../canonical/review/QARoot.android';
import {QAScreen,QADetailScreen} from '../canonical/src/flows/qa/QAScreens';
import type {QAState} from '../canonical/src/flows/qa/contract';
jest.mock('react-native-safe-area-context',()=>require('react-native-safe-area-context/jest/mock').default);
const port={getQaPresentation:jest.fn(),qaPresentationCommand:jest.fn(),qaPresentationLayout:jest.fn()};
const base:QAState={id:'visit-1',search:'',sections:[{title:'Network conditions',rows:[{id:'handover',title:'Cellular Handover',subtitle:'Native settings'}]}],
 formsVisible:true,hasConfiguration:false,staging:false,probeSuffix:'',canaryIP:'',hasResult:false};
function envelope(revision:number,state:QAState|null=base,interactive=true){return JSON.stringify({ownerID:'owner',revision,projection:state?{revision,state,interactive,canaryStatus:'Disarmed'}:null});}
async function emit(value:string){await act(async()=>DeviceEventEmitter.emit('lavaQaPresentation',value));}
beforeEach(()=>{jest.clearAllMocks();NativeModules.NativeLavaApp=port;port.getQaPresentation.mockResolvedValue(envelope(1,base,false));port.qaPresentationCommand.mockResolvedValue('null');port.qaPresentationLayout.mockResolvedValue(true);});

afterEach(()=>jest.restoreAllMocks());

test('the full viewport renders the actual shared screens and acknowledges the exact native visit before interaction',async()=>{
 await renderAsync(<QARoot ownerID="owner" textScale={1.35}/>);
 await waitFor(()=>expect(screen.UNSAFE_getByType(QAScreen)).toBeTruthy());
 const pane=screen.getByTestId('qa.shared');
 fireEvent(pane,'layout',{nativeEvent:{layout:{x:0,y:0,width:480,height:720}}});
 expect(port.qaPresentationLayout).toHaveBeenCalledWith('owner','visit-1',480,720);
 expect(screen.getByTestId('qa.handover',{includeHiddenElements:true})).toBeDisabled();
 await emit(envelope(2));
 fireEvent.press(screen.getByTestId('qa.handover'));
 expect(JSON.parse(port.qaPresentationCommand.mock.calls.at(-1)![1])).toEqual({id:'visit-1',action:'row',row:'handover'});
 expect(screen.queryByTestId('qa.stage')).toBeNull();
 const search=screen.getByLabelText('Search Device QA');
 expect(search.props.maxLength).toBe(200);expect(search.props.characterLimit).toBe(0);
 fireEvent(search,'change',{nativeEvent:{text:'Network'}});
 expect(JSON.parse(port.qaPresentationCommand.mock.calls.at(-1)![1])).toEqual({id:'visit-1',action:'search',value:'Network'});
 fireEvent.press(screen.getByText('VPN chaining'));
 expect(JSON.parse(port.qaPresentationCommand.mock.calls.at(-1)![1])).toEqual({id:'visit-1',action:'row',row:'chained-editor'});
 const suffix=screen.getByLabelText('Custom Probe Suffix');
 expect(suffix.props.maxLength).toBe(253);expect(suffix.props.characterLimit).toBe(0);expect(suffix.props.monospace).toBe(true);
 expect(StyleSheet.flatten(suffix.props.style).minHeight).toBeGreaterThanOrEqual(48);
});

test('QA result actions retire native click admission when their result or visit is withdrawn',async()=>{
 await renderAsync(<QARoot ownerID="owner"/>);
 await emit(envelope(2));
 expect(screen.getByTestId('qa_result').props.focusable).toBe(false);
 fireEvent.press(screen.getByTestId('qa_result'));expect(port.qaPresentationCommand).not.toHaveBeenCalled();
 await emit(envelope(3,{...base,hasResult:true}));
 expect(screen.getByTestId('qa_result').props.focusable).toBe(true);
 fireEvent.press(screen.getByTestId('qa_result'));
 expect(JSON.parse(port.qaPresentationCommand.mock.calls.at(-1)![1])).toEqual({id:'visit-1',action:'result'});
 await emit(envelope(4,{...base,hasResult:true},false));
 expect(screen.getByTestId('qa_result').props.focusable).toBe(false);
});

test('native withdrawal defeats an old initial reply and a successor requires its own layout',async()=>{
 let finish!:(value:string)=>void;port.getQaPresentation.mockReturnValue(new Promise(resolve=>{finish=resolve;}));
 await renderAsync(<QARoot ownerID="owner"/>);
 await emit(envelope(5));expect(screen.getByTestId('qa.shared')).toBeTruthy();
 await emit(envelope(6,null));expect(screen.queryByTestId('qa.shared')).toBeNull();
 await act(async()=>finish(envelope(1)));expect(screen.queryByTestId('qa.shared')).toBeNull();
 const successor={...base,id:'visit-2'};await emit(envelope(7,successor,false));
 expect(screen.getByTestId('qa.handover',{includeHiddenElements:true})).toBeDisabled();
 fireEvent(screen.getByTestId('qa.shared'),'layout',{nativeEvent:{layout:{x:0,y:0,width:360,height:480}}});
 expect(port.qaPresentationLayout).toHaveBeenLastCalledWith('owner','visit-2',360,480);
});

test('guided evidence uses the shared detail tree and carries exact native detail identity',async()=>{
 await renderAsync(<QARoot ownerID="owner"/>);
 const state:QAState={...base,detail:{id:'detail-1',kind:'check',title:'Cellular Handover',steps:['Observe native network'],expected:'Allowed and blocked controls',prepared:false,configurationMatches:false,canRecord:false,canBlock:false,evidence:''}};
 await emit(envelope(2,state));
 expect(screen.UNSAFE_getByType(QADetailScreen)).toBeTruthy();
 expect(screen.getByTestId('qa.record.Passed')).toBeDisabled();
 expect(screen.getByTestId('qa.record.Passed').props.focusable).toBe(false);
 fireEvent.press(screen.getByTestId('qa.record.Passed'));
 expect(port.qaPresentationCommand).not.toHaveBeenCalled();
 const input=screen.getByLabelText('Observed result and evidence');
 expect(input.props.maxLength).toBe(4096);expect(input.props.characterLimit).toBe(0);
 fireEvent.changeText(input,'No physical device available');
 expect(JSON.parse(port.qaPresentationCommand.mock.calls.at(-1)![1])).toEqual({id:'visit-1',detail:'detail-1',action:'evidence',value:'No physical device available'});
 fireEvent.press(screen.getByRole('button',{name:'Settings'}));
 expect(JSON.parse(port.qaPresentationCommand.mock.calls.at(-1)![1])).toEqual({id:'visit-1',detail:'detail-1',action:'settings'});
 fireEvent.press(screen.getByLabelText('Close'));
 expect(JSON.parse(port.qaPresentationCommand.mock.calls.at(-1)![1])).toEqual({id:'visit-1',detail:'detail-1',action:'detail.close'});
});

test('unknown host malformed rows and invalid revisions cannot become a presentation',()=>{
 expect(parseQAPresentation(envelope(1),'other')).toBeNull();
 expect(parseQAPresentation('{broken','owner')).toBeNull();
 expect(parseQAPresentation(envelope(-1),'owner')).toBeNull();
 const value=JSON.parse(envelope(1));value.projection.state.sections=[{title:'Bad',rows:[null]}];
 expect(parseQAPresentation(JSON.stringify(value),'owner')).toBeNull();
});


test('custom probes require a nonblank suffix and an interactive visit',async()=>{
 await renderAsync(<QARoot ownerID="owner"/>);
 for(const [revision,suffix,interactive] of [[2,'',true],[3,'   ',true],[4,'test.sslip.io',false]] as const){
  await emit(envelope(revision,{...base,probeSuffix:suffix},interactive));
  const apply=screen.getByText('Apply Custom QA Probes',{includeHiddenElements:true});
  expect(apply).toBeDisabled();
  fireEvent.press(apply);expect(port.qaPresentationCommand).not.toHaveBeenCalled();
 }
 await emit(envelope(5,{...base,probeSuffix:'test.sslip.io'}));
 const apply=screen.getByText('Apply Custom QA Probes');
 expect(apply).toBeEnabled();fireEvent.press(apply);
 expect(JSON.parse(port.qaPresentationCommand.mock.calls.at(-1)![1])).toEqual({id:'visit-1',action:'probes.apply'});
});
