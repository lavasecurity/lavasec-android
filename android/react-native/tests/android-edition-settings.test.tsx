import React from 'react';
import {Linking} from 'react-native';
import {fireEvent,render,screen} from '@testing-library/react-native';
import {initialSession} from '../canonical/review/session';
import type {AndroidEdition} from '../canonical/android/edition';
import {androidEditionRouteAllowed} from '../canonical/android/edition';
const mockNavigate=jest.fn(),mockRun=jest.fn();
let mockReview:any;
jest.mock('@react-navigation/native',()=>({useIsFocused:()=>true,useNavigation:()=>({setOptions:jest.fn()}),useRoute:()=>({params:undefined}),usePreventRemove:jest.fn()}));
jest.mock('../canonical/review/navigation',()=>({useReviewNavigation:()=>({navigate:mockNavigate}),previewNotice:jest.fn()}));
jest.mock('../canonical/review/ReviewContext',()=>({...jest.requireActual('../canonical/review/ReviewContext'),useReview:()=>mockReview,useOptionalReview:()=>undefined}));
jest.mock('../canonical/app/actions',()=>({...jest.requireActual('../canonical/app/actions'),useExclusiveAppAction:()=>({busy:false,isBusy:()=>false,run:mockRun})}));
import {EditionAccountScreen,EditionSettingsScreen} from '../canonical/android/EditionSettingsScreen';
const foss:AndroidEdition={id:'foss',accounts:false,cloudBackup:false,purchases:false,localFeaturesUnlocked:true,portableBackup:true,nativeSupport:false};
const play:AndroidEdition={id:'play',accounts:true,cloudBackup:true,purchases:true,localFeaturesUnlocked:false,portableBackup:true,nativeSupport:true};
function review(edition?:AndroidEdition){return {live:{edition,version:'2.2.3',build:'202039999',filters:[],session:{},plus:{enabled:false},
  account:{signedIn:false,busy:false},backup:{busy:false}},session:initialSession(),look:'original'};}
beforeEach(()=>{jest.clearAllMocks();mockRun.mockResolvedValue(undefined);mockReview=review(foss);});
afterEach(()=>jest.restoreAllMocks());

test('FOSS Settings renders only local backup and deliberate browser support',()=>{
  const open=jest.spyOn(Linking,'openURL').mockResolvedValue(undefined);
  render(<EditionSettingsScreen/>);
  expect(screen.queryByRole('button',{name:/Account & Backup/i})).toBeNull();
  expect(screen.queryByText('Get Lava Plus today')).toBeNull();
  expect(screen.queryByText('Feedback')).toBeNull();
  fireEvent.press(screen.getByText('Local Backup'));
  expect(mockNavigate).toHaveBeenCalledWith('Account');
  fireEvent.press(screen.getByText('Contact support in browser'));
  expect(open).toHaveBeenCalledWith('https://lavasecurity.app/support/');
  expect(mockRun).not.toHaveBeenCalled();
  expect(androidEditionRouteAllowed('Upgrade',foss)).toBe(false);
});

test('FOSS Account destination is local encrypted file actions with no login cloud or passkey controls',()=>{
  render(<EditionAccountScreen/>);
  for(const title of ['Sign in with Apple','Sign in with Google','Automatic backup','Set up backup'])expect(screen.queryByText(title)).toBeNull();
  fireEvent.press(screen.getByText('Export encrypted backup'));
  expect(mockRun).toHaveBeenLastCalledWith({type:'backup.export'});
  fireEvent.press(screen.getByText('Import encrypted backup'));
  expect(mockRun).toHaveBeenLastCalledWith({type:'backup.import'});
});

test('Play Settings retains account and paid Plus and also exposes portable files',()=>{
  mockReview=review(play);
  render(<EditionSettingsScreen/>);
  fireEvent.press(screen.getByRole('button',{name:/Account & Backup/i}));expect(mockNavigate).toHaveBeenLastCalledWith('Account');
  fireEvent.press(screen.getByText('Get Lava Plus today'));expect(mockNavigate).toHaveBeenLastCalledWith('Upgrade');
  fireEvent.press(screen.getByText('Export encrypted backup'));expect(mockRun).toHaveBeenLastCalledWith({type:'backup.export'});
  expect(screen.getByText('Feedback')).toBeTruthy();
  expect(screen.queryByText('Contact support in browser')).toBeNull();
  expect(androidEditionRouteAllowed('Upgrade',play)).toBe(true);
});

test('Play and legacy Account destination keep the existing sign-in controls',()=>{
  for(const edition of [play,undefined]){
    mockReview=review(edition);const view=render(<EditionAccountScreen/>);
    expect(screen.getByText('Sign in with Google')).toBeTruthy();expect(screen.getByText('Sign in with Apple')).toBeTruthy();
    expect(screen.queryByText('Export encrypted backup')).toBeNull();view.unmount();
  }
});

test('pending file custody is shown as awaiting activation and routes only native resume and discard',()=>{
  mockReview.live.portableBackup={pending:'available'};
  render(<EditionAccountScreen/>);
  expect(screen.getByText(/Current protection is unchanged/)).toBeTruthy();
  fireEvent.press(screen.getByText('Resume pending import'));
  expect(mockRun).toHaveBeenLastCalledWith({type:'backup.pending.resume'});
  fireEvent.press(screen.getByText('Discard pending import'));
  expect(mockRun).toHaveBeenLastCalledWith({type:'backup.pending.discard'});
});

test('unreadable pending files offer recovery without offering a false resume',()=>{
  mockReview.live.portableBackup={pending:'unreadable'};
  render(<EditionAccountScreen/>);
  expect(screen.queryByText('Resume pending import')).toBeNull();
  expect(screen.getByText('Discard pending import')).toBeTruthy();
  expect(screen.getByText(/import the original file again/)).toBeTruthy();
});
