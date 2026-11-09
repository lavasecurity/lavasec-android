import {useEffect,useRef,useState} from 'react';
import {Alert,TextInput,View} from 'react-native';
import {useRoute,type RouteProp} from '@react-navigation/native';
import type {AppCommand} from '../app/contract';
import {localized} from '../app/presentation';
import {useTextScale} from '../app/text-metrics';
import {LavaActionButton} from '../src';
import {colors} from '../src/colors';
import {Copy,Screen,Section} from '../review/primitives';
import {Group,Info,InputRow,ListRow,toolbarButton,useToolbar} from '../review/scaffold';
import {useReview} from '../review/ReviewContext';
import {useReviewNavigation,type ReviewRoutes} from '../review/navigation';

const report=(error:unknown)=>{const message=(error as Error).message;if(message!=='Authentication cancelled.')Alert.alert('Lava',message);};

type BlocklistFormat='auto'|'plain_domains'|'hosts'|'adblock'|'dnsmasq';

const formats:readonly {value:BlocklistFormat;title:string;detail:string}[]=[
  {value:'auto',title:'Auto-detect',detail:'Lava reads the format from the file. Use this when you are not sure.'},
  {value:'plain_domains',title:'Plain domains',detail:'One domain per line, such as example.com.'},
  {value:'hosts',title:'Hosts file',detail:'Hosts entries, such as 0.0.0.0 example.com.'},
  {value:'adblock',title:'Adblock',detail:'Adblock rules, such as ||example.com^.'},
  {value:'dnsmasq',title:'DNSmasq',detail:'DNSmasq entries, such as address=/example.com/0.0.0.0.'},
];

/**
 * The Android custom-blocklist editor. The picker's "Bring your own list" opens a native editor on
 * iOS; Android has no such native page, so this RN form collects the same name/URL the canonical
 * editor collects plus the parse format, and issues the Android `filter.addCustomList` adaptation
 * against the live draft. The owner remains authoritative for Plus admission, URL validation,
 * duplicate/budget rules, and the download that decides whether the list can be used.
 * The picker token identifies the filter; teardown rides the canonical `customEntry.dismiss`.
 */
export function CustomBlocklistEntryScreen() {
  const route=useRoute<RouteProp<ReviewRoutes,'CustomEntry'>>();
  const nav=useReviewNavigation();const {app}=useReview();
  const token=route.params.id;
  const [name,setName]=useState('');const [url,setUrl]=useState('');
  const [format,setFormat]=useState<BlocklistFormat>('auto');
  const [problem,setProblem]=useState<string|null>(null);const [busy,setBusy]=useState(false);
  const pending=useRef(false);
  // Teardown is local state only and never authenticates, matching the canonical dismiss.
  useEffect(()=>()=>{void app?.command({type:'customEntry.dismiss',id:token}).catch(()=>{});},[app,token]);
  useToolbar({title:'Bring your own list',headerBackVisible:false,
    unstable_headerLeftItems:()=>[toolbarButton('Close','xmark',()=>nav.goBack())]},[]);
  const selected=formats.find(option=>option.value===format)!;
  const save=async()=>{
    if(!app||pending.current)return;
    const address=url.trim();
    if(!address){setProblem('Enter the address of the list you want to add.');return;}
    if(!address.toLowerCase().startsWith('https://')){setProblem('Blocklist addresses must start with https://.');return;}
    pending.current=true;setBusy(true);setProblem(null);
    try{
      const command={type:'filter.addCustomList',token,name:name.trim(),url:address,format} as AppCommand;
      await app.command(command);
      nav.goBack();
    }catch(error){const message=(error as Error).message;if(message!=='Authentication cancelled.')setProblem(message);}
    finally{pending.current=false;setBusy(false);}
  };
  return <Screen>
    <Section title="Custom blocklist"><Info icon="link" title="Your own blocklist" description="Paste the HTTPS address of a hosts, domain, Adblock or dnsmasq list you trust. Lava downloads and filters it like any other blocklist." />
      <Group>
        <InputRow title="Name (optional)"><EditorField testID="custom.blocklist.name" label="Name" placeholder="My list" autoFocus onChange={setName}/></InputRow>
        <InputRow title="Address"><EditorField testID="custom.blocklist.url" label="Address" placeholder="https://example.com/hosts.txt" onChange={setUrl}/></InputRow>
      </Group>
    </Section>
    <Section title="Format">
      <Group>
        {formats.map(option=><ListRow key={option.value} testID={`custom.blocklist.format.${option.value}`} title={option.title}
          selected={option.value===format} disabled={busy} onPress={()=>setFormat(option.value)}/>)}
      </Group>
      <Copy role="caption" color={colors.secondaryText}>{selected.detail}</Copy>
    </Section>
    {problem&&<Info warning title="Cannot add this blocklist" description={problem}/>}
    <Copy role="caption" center color={colors.secondaryText}>Lava adds the list only if this device can download and parse it.</Copy>
    <View style={{padding:16}}><LavaActionButton title={busy?'Adding…':'Add blocklist'} disabled={busy||!url.trim()} onPress={()=>void save()} /></View>
  </Screen>;
}

function EditorField({testID,label,placeholder,autoFocus,onChange}:{testID:string;label:string;placeholder:string;autoFocus?:boolean;onChange:(value:string)=>void}) {
  const scale=useTextScale();
  const [measured,setMeasured]=useState({scale:0,height:0});
  return <TextInput testID={testID} autoFocus={autoFocus} accessibilityLabel={localized(label)} placeholder={localized(placeholder)}
    defaultValue="" autoCapitalize="none" autoCorrect={false} keyboardType="url" onChangeText={onChange}
    onLayout={event=>setMeasured({scale,height:event.nativeEvent.layout.height})}
    style={{minHeight:48,color:colors.primaryText,fontSize:17*scale,height:measured.scale===scale?Math.max(22,measured.height):22*scale}} />;
}
