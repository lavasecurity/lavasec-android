import React, {useCallback, useEffect, useState} from 'react';
import {BackHandler, Pressable, ScrollView, StyleSheet, Text, View} from 'react-native';
import {LavaActionButton, LavaCard, LavaText} from './src/components';
import {ChainedUpstreamScreen} from './src/ChainedUpstreamScreen';
import {androidTopInset} from './src/windowInsets';
import {useProduct} from './src/useProduct';
import type {GuardAction, NativeFlow} from './src/productContract';

type Tab = 'guard' | 'filters' | 'activity' | 'settings';
const tabs: Tab[] = ['guard', 'filters', 'activity', 'settings'];
const tabLabels: Record<Tab, string> = {guard: 'Guard', filters: 'Filters', activity: 'Activity', settings: 'Settings'};
const actions: Record<GuardAction, string> = {turnOn: 'Turn on protection', turnOff: 'Turn off protection', resume: 'Resume protection', reconnect: 'Reconnect', settings: 'Open system settings'};
const lifecycleCopy: Record<string, string> = {
  inactive: 'Protection is off', starting: 'Starting protection', awaiting_network: 'Waiting for a network',
  protected: 'Protection is on', reconnecting: 'Reconnecting', paused: 'Protection is paused',
  stopping: 'Stopping protection', attention: 'Protection needs attention', unavailable: 'Protection is unavailable',
};
const connectivityCopy: Record<string, string> = {
  healthy: 'DNS protection is working.', encrypted_fallback: 'Using the encrypted fallback resolver.',
  device_dns_fallback: 'Using device DNS as fallback.', recovering: 'Recovering the connection.',
  dns_slow: 'DNS is responding slowly.', network_unavailable: 'No network connection.',
  needs_reconnect: 'Reconnect to restore DNS protection.', unavailable: 'No confirmed connectivity assessment yet.',
};
const settings: {title: string; flow: NativeFlow}[] = [
  {title: 'DNS resolver', flow: 'resolver'}, {title: 'Account and backup', flow: 'account'},
  {title: 'Lava plus', flow: 'plus'}, {title: 'Privacy and data', flow: 'privacy'},
  {title: 'App unlock', flow: 'security'}, {title: 'Appearance and notifications', flow: 'customization'},
  {title: 'Network Activity', flow: 'networkActivity'}, {title: 'Diagnostics', flow: 'diagnostics'},
  {title: 'Report a problem', flow: 'feedback'}, {title: 'Legal notices', flow: 'legal'},
];

export default function App(): React.JSX.Element {
  const [tab, setTab] = useState<Tab>('guard');
  const [chained, setChained] = useState(false);
  const {snapshot, error, busy, refresh, command, endTurn} = useProduct();
  const selectTab = useCallback((next: Tab) => {
    if (next !== tab) { void endTurn(); setTab(next); }
  }, [tab, endTurn]);
  const closeChained = useCallback(() => { void endTurn(); setChained(false); }, [endTurn]);
  useEffect(() => {
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      if (chained) { closeChained(); return true; }
      if (tab !== 'guard') { selectTab('guard'); return true; }
      return false;
    });
    return () => subscription.remove();
  }, [tab, chained, closeChained, selectTab]);
  const open = (flow: NativeFlow) => { void command({type: 'native.flow', flow}); };
  const flowButton = (title: string, flow: NativeFlow, role: 'primary' | 'secondary' = 'secondary') => (
    <LavaActionButton key={flow} title={title} role={role} disabled={busy} onPress={() => open(flow)} testID={`flow.${flow}`} />
  );
  const ready = snapshot?.available === true;
  return (
    <View style={[styles.container, {paddingTop: androidTopInset()}]} testID="app.container">
      {chained && ready ? <ChainedUpstreamScreen onNavigateBack={closeChained} /> : <>
        <ScrollView contentContainerStyle={styles.content} testID={`${tab}.screen`}>
          <LavaText role="caption" tone="secondary">LAVASEC</LavaText>
          <LavaText role="title">{tabLabels[tab]}</LavaText>
          {error != null && <LavaCard><LavaText tone="danger" testID="product.error">{error}</LavaText><LavaActionButton title="Try again" onPress={() => { void refresh(); }} /></LavaCard>}
          {snapshot == null ? <LavaText tone="secondary">Loading your protection…</LavaText> : !ready ? <LavaCard>
            <LavaText role="headline">{snapshot.onboardingRequired ? 'Set up your protection' : 'Opening Lava'}</LavaText>
            <LavaText tone="secondary">{snapshot.onboardingRequired ? 'Choose your protection and allow Lava to create a local VPN connection.' : 'Waiting for the device’s protected storage and your saved configuration.'}</LavaText>
            {snapshot.onboardingRequired && flowButton('Continue setup', 'onboarding', 'primary')}
          </LavaCard> : <>
            {tab === 'guard' && <>
              <LavaCard>
                <LavaText role="headline" testID="guard.status">{lifecycleCopy[snapshot.protection.lifecycle] ?? 'Protection needs attention'}</LavaText>
                <LavaText tone="secondary">{connectivityCopy[snapshot.protection.connectivity] ?? 'Checking the connection.'}</LavaText>
                {snapshot.protection.message != null && <LavaText tone="warning">{snapshot.protection.message === 'vpn_permission_revoked' ? 'VPN permission was revoked. Allow Lava to reconnect.' : 'Android manages this VPN connection. Use system settings to change it.'}</LavaText>}
                {snapshot.protection.error != null && <LavaText tone="danger">{snapshot.protection.error}</LavaText>}
                {snapshot.protection.action != null && <LavaActionButton title={actions[snapshot.protection.action]} testID="guard.primary" disabled={busy || snapshot.protection.disabled} onPress={() => {
                  const action = snapshot.protection.action;
                  if (action != null) void command({type: 'protection.toggle', action});
                }} />}
                {snapshot.protection.canPause && <View style={styles.pause}>{([5, 10, 15] as const).map(minutes => <LavaActionButton key={minutes} title={`Pause ${minutes} min`} role="secondary" disabled={busy} onPress={() => { void command({type: 'protection.pause', minutes}); }} />)}</View>}
              </LavaCard>
              <LavaCard><LavaText role="headline">What’s filtering?</LavaText>
                <LavaText tone="secondary">{snapshot.filters.locked ? 'Unlock Filters to view your configuration.' : snapshot.filters.rows.find(row => row.active)?.name ?? 'No confirmed filter is available.'}</LavaText>
                <LavaActionButton title="View filters" role="secondary" onPress={() => selectTab('filters')} />
              </LavaCard>
            </>}
            {tab === 'filters' && <>
              <LavaText tone="secondary">Choose what Lava blocks and the domains you allow.</LavaText>
              {snapshot.filters.locked ? <LavaCard><LavaText>Filters are protected by app unlock.</LavaText>{<LavaActionButton title="Unlock Filters" disabled={busy} onPress={() => { void command({type: 'navigation.authorize', surface: 'filterEditing'}); }} />}</LavaCard> : <>
                {snapshot.filters.rows.map(row => <LavaCard key={row.id}><LavaText role="headline">{row.name}</LavaText><LavaText tone="secondary">{`${row.active ? `In effect${row.ruleCount == null ? '' : ` · ${row.ruleCount.toLocaleString()} rules`}` : 'Not in effect'}${row.frozen ? ' · Read only' : ''}`}</LavaText></LavaCard>)}
                {!snapshot.filters.available && <LavaText>Filter configuration is unavailable.</LavaText>}
                {flowButton('Manage filters', 'filters', 'primary')}
              </>}
            </>}
            {tab === 'activity' && <>
              <LavaText tone="secondary">DNS activity recorded on this device.</LavaText>
              <LavaCard>{snapshot.activity.locked ? <LavaText>Activity is protected by app unlock.</LavaText> : snapshot.activity.status === 'ready' ? <>
                <LavaText tone="secondary">{`${snapshot.activity.startDate ?? ''} — ${snapshot.activity.endDate ?? ''}`}</LavaText>
                <LavaText role="headline">{snapshot.activity.blocked == null ? 'Counts are not being retained' : `${snapshot.activity.blocked} blocked`}</LavaText>
                {snapshot.activity.allowed != null && <LavaText>{`${snapshot.activity.allowed} allowed`}</LavaText>}
              </> : <LavaText>{snapshot.activity.status === 'loading' ? 'Loading activity…' : snapshot.activity.status === 'failed' ? 'Activity could not be loaded.' : 'Activity is not available yet.'}</LavaText>}
                {snapshot.activity.locked ? <LavaActionButton title="Unlock Activity" disabled={busy} onPress={() => { void command({type: 'navigation.authorize', surface: 'activityViewing'}); }} /> : flowButton('Explore activity', 'activity', 'primary')}
                {!snapshot.activity.locked && <LavaActionButton title="Refresh activity" role="secondary" disabled={busy} onPress={() => { void command({type: 'activity.refresh'}); }} />}
              </LavaCard>
            </>}
            {tab === 'settings' && <>
              <LavaCard><LavaText role="headline">Protection and preferences</LavaText>
                {settings.map(item => flowButton(item.title, item.flow))}
                <LavaActionButton title="Chained upstream" role="secondary" testID="nav.chainedUpstream" onPress={() => setChained(true)} />
              </LavaCard>
              <LavaText role="caption" tone="secondary">{`Lavasec ${snapshot.version}`}</LavaText>
            </>}
          </>}
        </ScrollView>
        <View style={styles.tabs} accessibilityRole="tablist">{tabs.map(item => <Pressable key={item} testID={`nav.${item}`} accessibilityRole="tab" accessibilityState={{selected: item === tab}} onPress={() => selectTab(item)} style={styles.tab}>
          <Text style={[styles.tabLabel, item === tab && styles.selected]}>{tabLabels[item]}</Text>
        </Pressable>)}</View>
      </>}
    </View>
  );
}
const styles = StyleSheet.create({
  container: {backgroundColor: '#0b0b0c', flex: 1},
  content: {gap: 20, padding: 24, paddingBottom: 32},
  tabs: {flexDirection: 'row', borderTopWidth: StyleSheet.hairlineWidth, borderColor: '#27272a', paddingBottom: 16},
  tab: {flex: 1, minHeight: 52, alignItems: 'center', justifyContent: 'center'},
  tabLabel: {fontSize: 13, color: '#a1a1aa'},
  selected: {color: '#f97316', fontWeight: '700'},
  pause: {gap: 8},
});
