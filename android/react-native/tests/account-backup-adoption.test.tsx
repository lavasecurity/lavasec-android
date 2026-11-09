import React from 'react';
import {act,fireEvent,render,screen} from '@testing-library/react-native';
import {AppState} from 'react-native';
import type {AccountBackupFlowState} from '../canonical/src/flows/account-backup/types';
import {AccountBackupFlow} from '../canonical/src/flows/account-backup/AccountBackupFlow';
import {Alert,localized} from '../canonical/app/presentation';

const mockCommand=jest.fn().mockResolvedValue(null),mockRetire=jest.fn().mockResolvedValue(undefined);
let mockCurrent='visit',mockAuthority='one',mockInteractive=true;
const mockApp={command:mockCommand,retireAccountBackup:mockRetire,getSnapshot:()=>({snapshot:{foregroundFlow:{id:mockCurrent}}})};
jest.mock('@react-navigation/native',()=>({useNavigation:()=>({setOptions:jest.fn()}),useRoute:()=>({params:{id:'visit'}}),usePreventRemove:jest.fn(),useIsFocused:()=>true}));
jest.mock('../canonical/review/ReviewContext',()=>({
  useReview:()=>({app:mockApp,live:{}}),useOptionalReview:()=>undefined,useRouteBodyConcealed:()=>false,
}));
jest.mock('../canonical/app/read-cache',()=>({mayInteractWithPresentation:()=>mockInteractive,domainReviewPrivacyScope:()=>mockAuthority}));
jest.mock('../canonical/app/use-presentation-readiness',()=>({usePresentationNativeLayout:()=>()=>{}}));
jest.mock('../canonical/review/scaffold',()=>({...jest.requireActual('../canonical/review/scaffold'),useToolbar:jest.fn(),useSheetHeaderInset:()=>0}));
jest.mock('react-native-safe-area-context',()=>require('react-native-safe-area-context/jest/mock').default);
jest.mock('../canonical/src/GuardianDrawing',()=>({GuardianDrawing:require('react-native').View}));
import {AndroidAccountBackup} from '../canonical/review/AccountBackupFlow.android';

export const setupState:AccountBackupFlowState={
  kind:'backupSetup',title:'Set up backup',busy:false,canBack:false,canClose:true,success:false,
  connections:[],deleting:false,step:'overview',error:'',copied:false,canContinue:false,
  supportsPasskey:true,selectedPasskey:false,saved:false,understands:false,uploading:false,uploadError:'',
  mode:'recoveryCode',modes:[],status:null,review:null,resolverConfirmationRequired:false,resolverConfirmed:false,
};
beforeEach(()=>{jest.clearAllMocks();mockCurrent='visit';mockAuthority='one';mockInteractive=true;Object.defineProperty(AppState,'currentState',{configurable:true,value:'active'});});
afterEach(()=>jest.restoreAllMocks());

test('the live Android adopter uses shared setup choices and sends only typed control values',async()=>{
  const view=render(<AndroidAccountBackup id="visit" state={setupState}/>);
  expect(screen.UNSAFE_getByType(AccountBackupFlow)).toBeTruthy();
  await act(async()=>fireEvent.press(screen.getByRole('button',{name:localized('Set up without Passkey')})));
  expect(mockCommand).toHaveBeenCalledWith({type:'accountBackup.command',id:'visit',action:'setup.begin',passkey:false});
  view.unmount();expect(mockRetire).toHaveBeenCalledWith('visit');
});

test('local upload failure offers the existing-envelope retry and cannot render success',async()=>{
  render(<AndroidAccountBackup id="visit" state={{...setupState,step:'upload',canContinue:true,uploadError:'Upload failed'}}/>);
  expect(screen.queryByText('Backup is ready')).toBeNull();
  expect(screen.getByText('Upload failed')).toBeTruthy();
  await act(async()=>fireEvent.press(screen.getByRole('button',{name:localized('Turn On Backup')})));
  expect(mockCommand).toHaveBeenCalledWith({type:'accountBackup.command',id:'visit',action:'setup.retry'});
});

test('native confirmed upload alone renders Done and uses read-free exact retirement',async()=>{
  render(<AndroidAccountBackup id="visit" state={{...setupState,success:true,step:'complete'}}/>);
  expect(screen.getByText('Backup is ready')).toBeTruthy();
  mockInteractive=false;
  await act(async()=>fireEvent.press(screen.getByRole('button',{name:'Done'})));
  expect(mockRetire).toHaveBeenCalledWith('visit');
  expect(mockCommand).not.toHaveBeenCalled();
});

test('account deletion confirmation cannot act on a replaced native visit',async()=>{
  const alert=jest.spyOn(Alert,'alert').mockImplementation(()=>{});
  const view=render(<AndroidAccountBackup id="visit" state={{...setupState,kind:'account'}}/>);
  fireEvent.press(screen.getByRole('button',{name:localized('Delete my Lava account')}));
  const confirm=alert.mock.calls[0]![2]!.find(button=>button.style==='destructive')!;
  mockAuthority='two';mockCurrent='successor';
  view.rerender(<AndroidAccountBackup id="visit" state={{...setupState,kind:'account'}}/>);
  await act(async()=>confirm.onPress?.());
  expect(mockCommand).not.toHaveBeenCalled();
});

test('device recovery uses Android key copy instead of the Apple keychain hint',()=>{
  render(<AndroidAccountBackup id="visit" state={{...setupState,kind:'backupRestore',mode:'deviceKey',
    modes:[{id:'deviceKey',title:'This device',unavailable:false}],canContinue:true}}/>);
  expect(screen.getByText('A local unlock key stays on this device.')).toBeTruthy();
  expect(screen.queryByText("Use this device's keychain")).toBeNull();
});

test('restore refusals stay visible beside the retained method and native credential leaf',()=>{
  render(<AndroidAccountBackup id="visit" state={{...setupState,kind:'backupRestore',error:'Wrong recovery phrase',
    modes:[{id:'recoveryCode',title:'Recovery phrase',unavailable:false}],canContinue:true}}/>);
  expect(screen.getByText('Wrong recovery phrase')).toBeTruthy();
  expect(screen.getByRole('button',{name:localized('Review Backup')})).toBeTruthy();
});
