import {act,fireEvent,render,screen} from '@testing-library/react-native';
import {AppState} from 'react-native';
import {ActivityScreen} from '../canonical/review/ActivityScreen';

let mockToolbar:any;
let mockChoice:string|null=null;
const mockChoose=jest.fn(async(_available:boolean)=>mockChoice);
const mockChart=jest.fn(),mockDiagnostics=jest.fn(),mockLogs=jest.fn();
const mockApp={getReadEpoch:()=>1,command:jest.fn().mockResolvedValue(null)};
const mockDates={start:1000,end:1100,label:'Today',includesToday:true};
jest.mock('@react-navigation/native',()=>({useIsFocused:()=>true}));
jest.mock('../canonical/app/use-route-view-state',()=>({useRouteViewState:require('react').useState}));
jest.mock('../canonical/app/read-cache',()=>({mayInteractWithPresentation:()=>true}));
jest.mock('../canonical/app/queries',()=>({useAppQuery:()=>({value:{allowed:12,blocked:4,uptime:'1m',buckets:[]},refresh:jest.fn()})}));
jest.mock('../canonical/review/ReviewContext',()=>({useReview:()=>({app:mockApp,live:{activityDates:mockDates}})}));
jest.mock('../canonical/review/navigation',()=>({useReviewNavigation:()=>({})}));
jest.mock('../canonical/review/local-log-actions',()=>({useLocalLogActions:()=>({exportBusy:false,clearBusy:false,exportLogs:mockDiagnostics,exportAllLogs:mockLogs})}));
jest.mock('../canonical/review/activity-share-actions',()=>({useActivityShare:()=>({busy:false,exportChart:mockChart})}));
jest.mock('../canonical/specs/NativeLavaReview',()=>({__esModule:true,default:{chooseActivityExport:(available:boolean)=>mockChoose(available)}}));
jest.mock('../canonical/src',()=>({LavaChoice:()=>null}));
jest.mock('../canonical/review/primitives',()=>({Screen:require('react-native').View,Copy:require('react-native').Text,Row:()=>null}));
jest.mock('../canonical/review/story-scaffold',()=>({StorySurface:require('react-native').View}));
jest.mock('../canonical/review/settings-scaffold',()=>({SettingsSurface:require('react-native').View,SettingsMessage:require('react-native').Text}));
jest.mock('../canonical/review/scaffold',()=>({QuietFooter:require('react-native').View,
  toolbarAction:(title:string,_icon:string,onPress:()=>void)=>({title,onPress}),
  useToolbar:(toolbar:any)=>{mockToolbar=toolbar;}}));
jest.mock('../canonical/review/activity-scaffold',()=>({ActivityCharts:({onChartChange}:any)=>{
  const React=require('react'),{View,Button}=require('react-native');
  return React.createElement(View,null,...['total','counts','rate'].map(chart=>React.createElement(Button,{key:chart,title:chart,onPress:()=>onChartChange(chart)})));
}}));
jest.mock('../canonical/review/ActivityShareCard',()=>({ActivityShareCard:()=>null}));

beforeEach(()=>{AppState.currentState='active';jest.clearAllMocks();mockChoice=null;});

test.each(['total','counts','rate'])('the %s chart exposes only supported export actions',async chart=>{
  render(<ActivityScreen/>);
  fireEvent.press(screen.getByRole('button',{name:chart.toUpperCase()}));
  const exportAction=()=>mockToolbar.unstable_headerRightItems().find((item:any)=>item.title==='Export').onPress();
  await act(async()=>exportAction());
  expect(mockChoose).toHaveBeenLastCalledWith(chart==='total');
  expect(mockChart).not.toHaveBeenCalled();
  for(const choice of ['diagnostics','logs']){
    mockChoice=choice;await act(async()=>exportAction());
  }
  expect(mockDiagnostics).toHaveBeenCalledTimes(1);expect(mockLogs).toHaveBeenCalledTimes(1);
  if(chart==='total'){
    mockChoice='chart';await act(async()=>exportAction());expect(mockChart).toHaveBeenCalledTimes(1);
  }
});
