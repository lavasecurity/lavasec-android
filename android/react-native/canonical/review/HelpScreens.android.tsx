import {View} from 'react-native';
import {SettingsIntro} from './settings-scaffold';
import {Info} from './scaffold';
import {FlowSheet} from './form-scaffold';

// Shortcuts/Focus are an explicitly classified iOS-only system integration.
// Keep the shared page scaffold and explain the platform boundary accurately.
export function AutoSwitchContent(){return <>
  <SettingsIntro summary="Automation through Apple Shortcuts and Focus is available on iOS."/>
  <Info title="Android availability" description="Switch saved filters from Your filters. Android does not provide Apple's Shortcuts or Focus Filter integration."/>
</>;}
export function AutoSwitchPage(){return <FlowSheet><View testID="auto-switch-page" style={{gap:20}}><AutoSwitchContent/></View></FlowSheet>;}
