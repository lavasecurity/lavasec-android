import {Linking} from 'react-native';
import {useExclusiveAppAction} from '../app/actions';
import {localizedFormat} from '../app/presentation';
import {useReview} from '../review/ReviewContext';
import {useReviewNavigation} from '../review/navigation';
import {Copy,Row,Screen} from '../review/primitives';
import {SettingsGroup,SettingsIntro} from '../review/settings-scaffold';
import {ConnectionPanel,StoryColumns,StoryStack} from '../review/story-scaffold';
import {connectionStages} from '../review/connection-model';
import {ListRow} from '../review/scaffold';
import {colors} from '../src/colors';
import {AccountScreen} from '../review/SettingsScreens';

function LocalBackupActions(){
  const backup=useExclusiveAppAction('backup');
  const {live}=useReview();
  const pending=live?.portableBackup?.pending??'none';
  return <SettingsGroup title="Local encrypted backup">
    <ListRow action disabled={backup.busy} icon="square.and.arrow.up" title="Export encrypted backup"
      onPress={()=>{if(!backup.isBusy())void backup.run({type:'backup.export'});}}/>
    <ListRow action disabled={backup.busy} icon="square.and.arrow.down" title="Import encrypted backup"
      onPress={()=>{if(!backup.isBusy())void backup.run({type:'backup.import'});}}/>
    {pending!=='none'&&<SettingsIntro summary={pending==='available'?
      'Encrypted import saved for later. Current protection is unchanged. Activation may need source downloads or features available in this edition.':
      'A saved encrypted import could not be read. Discard it and import the original file again.'}/>}
    {pending==='available'&&<ListRow action disabled={backup.busy} icon="square.and.arrow.down" title="Resume pending import"
      onPress={()=>{if(!backup.isBusy())void backup.run({type:'backup.pending.resume'});}}/>}
    {pending!=='none'&&<ListRow action disabled={backup.busy} icon="trash" title="Discard pending import"
      onPress={()=>{if(!backup.isBusy())void backup.run({type:'backup.pending.discard'});}}/>}
  </SettingsGroup>;
}

export function EditionAccountScreen(){
  const {live}=useReview();
  if(live?.edition?.accounts!==false)return <AccountScreen/>;
  return <Screen><SettingsIntro summary="Save an encrypted file and restore it on another device or Lava edition. Keep your password or recovery phrase separately; Lava cannot recover it."/>
    <LocalBackupActions/>
  </Screen>;
}

/** Android distribution choices live here so canonical iOS screens remain unchanged. */
export function EditionSettingsScreen(){
  const nav=useReviewNavigation();const {live,app,session}=useReview();
  const report=useExclusiveAppAction('report');
  const foss=live?.edition?.id==='foss';
  const stages=connectionStages(live,session);
  return <Screen wide><StoryColumns primary={<ConnectionPanel stages={stages}
    onSelect={stage=>stage.destination?nav.navigate(stage.destination):nav.navigate('Explore',{part:'phone'})}
    onExplore={()=>nav.navigate('Explore')}/>}
    secondary={<StoryStack>
      <SettingsGroup title="Your Lava">
        <Row intent="page" icon={foss?'lock.fill':'person.crop.circle'} title={foss?'Local Backup':'Account & Backup'} onPress={()=>nav.navigate('Account')}/>
        <Row intent="page" icon="slider.horizontal.3" title="Customization" onPress={()=>nav.navigate('Customization')}/>
        {!foss&&<Row intent="page" icon="shield.fill" title={live?.plus?.enabled?'Thank you for using Lava Plus':'Get Lava Plus today'} onPress={()=>nav.navigate('Upgrade')}/>}
      </SettingsGroup>
      {foss?<SettingsIntro summary="FOSS edition · All local features are available. Accounts and cloud backup are available in the Google Play edition."/>:<LocalBackupActions/>}
      <SettingsGroup title="Privacy & security">
        <Row intent="page" icon="eyeglasses" testID="row.Privacy & data" title="Privacy & Data" onPress={()=>nav.navigate('Privacy')}/>
        <Row intent="page" icon="lock.fill" title="Security" onPress={()=>nav.navigate('Security')}/>
      </SettingsGroup>
      <SettingsGroup title="Support">
        <Row intent="external" icon="questionmark.circle" title="Help" onPress={()=>{void Linking.openURL('https://lavasecurity.app/support/');}}/>
        <Row intent={foss?'external':'page'} icon="ladybug" title={foss?'Contact support in browser':'Feedback'} onPress={()=>foss?void Linking.openURL('https://lavasecurity.app/support/'):app?void report.run({type:'native.flow',flow:'feedback'}):nav.navigate('Feedback')}/>
        <Row intent="page" icon="doc.text" title="Legal Notices" onPress={()=>nav.navigate('Legal')}/>
      </SettingsGroup>
      <SettingsGroup title="Advanced">
        <Row intent="page" icon="waveform.path.ecg.rectangle" testID="row.Network activity" title="Network Activity" onPress={()=>nav.navigate('Network')}/>
        <Row intent="page" icon="number" testID="row.Nerd stats" title="Nerd Stats" onPress={()=>nav.navigate('Stats')}/>
        {live?.qaTools&&<Row intent="page" icon="square.grid.2x2" title="Design system" onPress={()=>nav.navigate('Components')}/>}
        {live?.qaTools&&<Row intent="page" icon="hammer" title="Device QA" onPress={()=>nav.navigate('DeviceQA')}/>}
      </SettingsGroup>
      <Copy role="caption" color={colors.secondaryText}>{live?`${localizedFormat('Lava %1$@ (build %2$@)',live.version,live.build)} · ${foss?'FOSS':'Google Play'}${live.sourceRevision?` · ${live.sourceRevision.slice(0,12)}`:''}`:'Lava · UI preview'}</Copy>
    </StoryStack>}/></Screen>;
}
