import React from 'react';
import {fireEvent, screen, waitFor} from '@testing-library/react-native';

// Plan 309 B05/F15: the shared ordered-tier DNS editor must render the full retained ladder,
// including a disabled row, and toggle any index — not just the old Android fallback slot.
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({setOptions: jest.fn(), navigate: jest.fn(), goBack: jest.fn(), getState: () => undefined}),
  useIsFocused: () => true,
  useRoute: () => ({params: undefined, key: 'k'}),
  usePreventRemove: jest.fn(),
}));
jest.mock('react-native-safe-area-context', () => ({SafeAreaInsetsContext:require('react').createContext({top:59,bottom:34,left:0,right:0}),
  SafeAreaProvider: ({children}: {children: React.ReactNode}) => children,
  useSafeAreaFrame: () => ({x: 0, y: 0, width: 390, height: 844}),
  useSafeAreaInsets: () => ({top: 47, left: 0, right: 0, bottom: 34}),
}));

import {installLifecycleMock, mountJourney, type Wire} from './interruptionHarness';
import {productFixture} from './productFixture';
import {DNSScreen} from '../canonical/review/DNSScreen';
import {configurePresentation,localized} from '../canonical/app/presentation';
import {translations} from '../canonical/app/translations';
import {selection} from '../canonical/review/dns-editor';

const device = {id: 'device-dns|IP', name: 'Device DNS', primary: '', secondary: '', transport: 'IP', metadata: 'Device DNS'};
const mullvad = {id: 'mullvad|DoH', name: 'Mullvad', primary: '', secondary: '', transport: 'DoH', metadata: 'https://dns.mullvad.net/dns-query'};

function ladder(tiers: NonNullable<Wire['dns']>['tiers'], context: string): Wire {
  return {
    ...productFixture,
    dns: {
      providers: [],
      customSelected: false,
      transports: ['IP', 'DoH'],
      editable: true,
      tiers,
      tiersContext: context,
    },
  } as Wire;
}

let lifecycle: ReturnType<typeof installLifecycleMock>;
beforeEach(() => {
  lifecycle = installLifecycleMock();
});
afterEach(() => jest.restoreAllMocks());

test('renders a disabled retained tier in place and forwards the toggle for that index', async () => {
  const wire = ladder([device, {...mullvad, isEnabled: false}], 'device-dns|IP|1;mullvad|DoH|0');
  const harness = mountJourney({lifecycle, wire, render: () => <DNSScreen />});
  await waitFor(() => expect(screen.getByText('Mullvad')).toBeTruthy());
  expect(screen.getByText('Device DNS')).toBeTruthy();

  const retained = screen.getByTestId('dns.tier-toggle.1');
  expect(retained.props.on).toBe(false);
  fireEvent(retained, 'valueChange', true);
  await waitFor(() =>
    expect(harness.owner.last('dns.toggle')).toMatchObject({
      type: 'dns.toggle',
      context: 'device-dns|IP|1;mullvad|DoH|0',
      index: 1,
      value: true,
    }),
  );
  harness.unmount();
});

test('toggles a non-fallback index instead of hardcoding the fallback slot', async () => {
  const wire = ladder([device, mullvad], 'device-dns|IP|1;mullvad|DoH|1');
  const harness = mountJourney({lifecycle, wire, render: () => <DNSScreen />});
  await waitFor(() => expect(screen.getByText('Device DNS')).toBeTruthy());

  fireEvent(screen.getByTestId('dns.tier-toggle.0'), 'valueChange', false);
  await waitFor(() =>
    expect(harness.owner.last('dns.toggle')).toMatchObject({index: 0, value: false}),
  );
  harness.unmount();
});

test('never disables the last active tier from the ladder', async () => {
  const wire = ladder([device], 'device-dns|IP|1');
  mountJourney({lifecycle, wire, render: () => <DNSScreen />});
  await waitFor(() => expect(screen.getByText('Device DNS')).toBeTruthy());

  expect(screen.getByTestId('dns.tier-toggle.0').props.enabled).toBe(false);
});


test('DNS rows and switch labels keep authored catalog-key identities without changing selection values',async()=>{
  configurePresentation({locale:'de',textScales:null});
  const names=['Cancel','Save','Device DNS','Custom DNS'];
  try {
    for(const name of names){
      const custom={id:'custom-dns|DoH',name,nameIsDefault:false,primary:'https://dns.example/query',secondary:'',transport:'DoH',metadata:''};
      const wire={...ladder([custom,{...mullvad,isEnabled:false}],`raw:${name}`),presentation:{locale:'de',textScales:null}};
      const harness=mountJourney({lifecycle,wire,render:()=> <DNSScreen/>});
      await waitFor(()=>expect(screen.getByText(name)).toBeTruthy());
      expect(screen.getByTestId('dns.tier-toggle.0').props.accessibilityLabel).toBe(name);
      expect(selection(custom).name).toBe(name);
      harness.unmount();
    }
  } finally {configurePresentation({locale:'en',textScales:null});}
});

test.each([['de','de'],['pt','pt-BR'],['pt-PT','pt-BR'],['zh-Hans-HK','zh-Hans']])('default DNS row labels use packet locale %s while unnamed editable values stay empty',async(locale,catalog)=>{
  configurePresentation({locale:'fr',textScales:null});
  const custom={id:'custom-dns|DoH',name:'',nameIsDefault:true,primary:'https://dns.example/query',secondary:'',transport:'DoH',metadata:''};
  const wire={...ladder([{...device,nameIsDefault:true},custom],'defaults'),presentation:{locale,textScales:null}};
  const table=translations[catalog]!;
  const harness=mountJourney({lifecycle,wire,render:()=> <DNSScreen/>});
  try {
    await waitFor(()=>expect(screen.getByText(table['Device DNS']!)).toBeTruthy());
    expect(screen.getByText(table['Custom DNS']!)).toBeTruthy();
    expect(screen.getByTestId('dns.tier-toggle.0').props.accessibilityLabel).toBe(table['Device DNS']);
    expect(screen.getByTestId('dns.tier-toggle.1').props.accessibilityLabel).toBe(table['Custom DNS']);
    expect(selection(custom)).toMatchObject({name:'',primary:'https://dns.example/query'});
    expect(harness.runtime()?.dns.tiers?.[1]?.name).toBe('');
    expect(harness.count('dns.tiers')).toBe(0);
  } finally {harness.unmount();configurePresentation({locale:'en',textScales:null});}
});


test('material DNS ladder saves keep generated default names empty and authored or legacy names exact',()=>{
  const generated=[{...device,nameIsDefault:true},{...mullvad,nameIsDefault:true,isEnabled:false}];
  const reordered=[...generated].reverse().map(selection);
  expect(reordered).toEqual([
    {id:'mullvad|DoH',name:'',primary:'',secondary:'',isEnabled:false},
    {id:'device-dns|IP',name:'',primary:'',secondary:''},
  ]);
  for(const name of ['Device DNS','Custom DNS','Cancel']){
    for(const metadata of [{nameIsDefault:false},{}]){
      const choice={id:'custom-dns|DoH',name,primary:'https://dns.example/query',secondary:'https://backup.example/query',transport:'DoH',metadata:'',...metadata};
      expect(selection(choice)).toEqual({id:choice.id,name,primary:choice.primary,secondary:choice.secondary});
    }
  }
  expect(selection({...generated[0]!,nameIsDefault:false})).toMatchObject({name:'Device DNS'});
});
