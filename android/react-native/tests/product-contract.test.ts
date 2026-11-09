import {parseProductSnapshot, parseCommandResult, type DNSChoice, type ProductCommand, type NativeFlow, type SudokuGame} from '../src/productContract';
import {productFixture} from './productFixture';

test('preserves counters larger than JS safe integer', () => {
  const fixture = {...productFixture, activity: {...productFixture.activity, allowed: '9223372036854775807'}};
  expect(parseProductSnapshot(JSON.stringify(fixture)).activity.allowed).toBe('9223372036854775807');
});
test('admits the optional native chained establishment bit and refuses malformed values',()=>{
  for(const value of [undefined,false,true])expect(parseProductSnapshot(JSON.stringify({...productFixture,protection:{...productFixture.protection,chainedEstablishing:value}})).protection.chainedEstablishing).toBe(value);
  for(const value of [null,0,'true'])expect(()=>parseProductSnapshot(JSON.stringify({...productFixture,protection:{...productFixture.protection,chainedEstablishing:value}}))).toThrow(TypeError);
});
test.each([
  {...productFixture, schema: 2},
  {...productFixture, revision: -1},
  {...productFixture, protection: {...productFixture.protection, disabled: 'false'}},
  {...productFixture, protection: {...productFixture.protection, action: 'buy'}},
  {...productFixture, filters: {...productFixture.filters, locked: true}},
  {...productFixture, activity: {...productFixture.activity, locked: true}},
  {...productFixture, activity: {...productFixture.activity, allowed: 12}},
])('rejects incompatible or privacy-violating native snapshots', fixture => {
  expect(() => parseProductSnapshot(JSON.stringify(fixture))).toThrow(TypeError);
});
test('accepts requested/confirmed/onboarding/recovery and rejects malformed values', () => {
  const parsed = parseProductSnapshot(JSON.stringify(productFixture));
  expect(parsed.protection.requested).toBe(false);
  expect(parsed.protection.confirmed).toBe(false);
  expect(parsed.protection.onboarding).toBe('ready');
  expect(parsed.protection.recovery).toBeNull();

  const requested = {
    ...productFixture,
    protection: {
      ...productFixture.protection,
      requested: true, confirmed: false, onboarding: 'preparingDefaultFilter', recovery: 'retryStart',
    },
  };
  expect(parseProductSnapshot(JSON.stringify(requested)).protection).toEqual(requested.protection);
  // A missing lifecycle is the explicit null/unknown contract, not a fabricated boolean.
  const unknown = {
    ...productFixture,
    protection: {...productFixture.protection, requested: null, confirmed: null, onboarding: 'unavailable', recovery: null},
  };
  expect(parseProductSnapshot(JSON.stringify(unknown)).protection.requested).toBeNull();

  for (const protection of [
    {...productFixture.protection, requested: 'yes'},
    {...productFixture.protection, confirmed: 1},
    {...productFixture.protection, onboarding: 'maybe'},
    {...productFixture.protection, recovery: 'retryEverything'},
  ]) {
    expect(() => parseProductSnapshot(JSON.stringify({...productFixture, protection}))).toThrow(TypeError);
  }
});

test('native cancellation is a distinct successful transport result', () => {
  expect(parseCommandResult('{"schema":1,"status":"cancelled","reason":"vpn-consent"}').status).toBe('cancelled');
  expect(() => parseCommandResult('{"schema":1,"status":"success","reason":null}')).toThrow(TypeError);
});
test('canonical command payloads round-trip and stay JSON-only', () => {
  expect(parseCommandResult('{"schema":1,"status":"completed","reason":null,"value":"saved"}').value).toBe('saved');
  expect(parseCommandResult('{"schema":1,"status":"completed","reason":null,"value":"review"}').value).toBe('review');
  expect(parseCommandResult('{"schema":1,"status":"completed","reason":null,"value":"token-1"}').value).toBe('token-1');
  expect(parseCommandResult('{"schema":1,"status":"completed","reason":null,"value":{"isAccepted":false,"reason":"invalid_domain"}}').value)
    .toEqual({isAccepted: false, reason: 'invalid_domain'});
  expect(parseCommandResult('{"schema":1,"status":"completed","reason":null}').value).toBeUndefined();
});
test('accepts the canonical filterEditing family and rejects malformed rows', () => {
  const editing = {canSave: true, reviewCanConfirm: true, validation: null, refreshing: false, lists: [], blocked: [], allowed: []};
  expect(parseProductSnapshot(JSON.stringify({...productFixture, filterEditing: editing})).filterEditing).toEqual(editing);
  expect(() => parseProductSnapshot(JSON.stringify({...productFixture, filterEditing: {...editing, canSave: 'yes'}}))).toThrow(TypeError);
  expect(() => parseProductSnapshot(JSON.stringify({...productFixture, filterEditing: {...editing, lists: [{id: 'a'}]}}))).toThrow(TypeError);
});
test('accepts the canonical libraryEditing family and rejects malformed staged sets', () => {
  const editing = {active: true, hasChanges: true, deletions: ['a', 'b']};
  expect(parseProductSnapshot(JSON.stringify({...productFixture, libraryEditing: editing})).libraryEditing).toEqual(editing);
  expect(parseProductSnapshot(JSON.stringify({...productFixture, libraryEditing: undefined})).libraryEditing).toBeUndefined();
  expect(() => parseProductSnapshot(JSON.stringify({...productFixture, libraryEditing: {...editing, active: 'yes'}}))).toThrow(TypeError);
  expect(() => parseProductSnapshot(JSON.stringify({...productFixture, libraryEditing: {...editing, hasChanges: 1}}))).toThrow(TypeError);
  expect(() => parseProductSnapshot(JSON.stringify({...productFixture, libraryEditing: {...editing, deletions: ['a', 2]}}))).toThrow(TypeError);
});
test('canonical filter/library commands are part of the wire union', () => {
  const commands: ProductCommand[] = [
    {type: 'filter.open', id: 'a'},
    {type: 'filter.save', id: 'a'},
    {type: 'filter.apply', id: 'a', review: 't'},
    {type: 'filter.create', name: 'n'},
    {type: 'filter.rename', id: 'a', name: 'n'},
    {type: 'filter.domain', id: 'a', domain: 'example.com', decision: 'blocked'},
    {type: 'filter.removeList', id: 'a', sourceID: 's'},
    {type: 'filter.restoreDefaults'},
    {type: 'filter.lists', id: 'a', ids: ['x']},
    {type: 'library.edit'},
    {type: 'library.cancel'},
    {type: 'library.toggleDeletion', id: 'a'},
    {type: 'library.form', form: 'create'},
    {type: 'library.form', form: 'create', name: 'New', id: 'source'},
    {type: 'library.form', form: 'rename', id: 'a', name: 'Renamed', emoji: '🔥'},
    {type: 'library.form', form: 'delete', ids: ['a']},
  ];
  expect(commands).toHaveLength(16);
});

test('canonical share commands are part of the wire union', () => {
  const commands: ProductCommand[] = [
    {type: 'share.query', id: 'a'},
    {type: 'share.copy', id: 'a'},
    {type: 'share.card', id: 'a',token:'native-render-token'},
    {type: 'share.present', id: 'a'},
  ];
  expect(commands).toHaveLength(4);
});

test('the canonical import flows are part of the native-flow union', () => {
  const flows: NativeFlow[] = ['import', 'importCode', 'importScan'];
  expect(flows).toHaveLength(3);
});

test('a share.query payload round-trips through the strict result envelope', () => {
  const result = parseCommandResult(
    '{"schema":1,"status":"completed","reason":null,"value":{"code":"LF1-x","url":"https://lavasecurity.app/app/import/#LF1-x","image":null}}',
  );
  expect(result.value).toEqual({
    code: 'LF1-x',
    url: 'https://lavasecurity.app/app/import/#LF1-x',
    image: null,
  });
});

const sudokuGame: SudokuGame = {
  puzzle: {
    givens: '032000601090602030000500000360800000910200080070000096020090070100020900450703800'.split('').map(Number),
    solution: '532987641791642538846531729364819257915276384278354196623198475187425963459763812'.split('').map(Number),
  },
  values: Array(81).fill(0),
  notes: Array.from({length: 81}, () => []),
};

test('accepts the canonical sudoku snapshot family and rejects corrupt boards', () => {
  const parsed = parseProductSnapshot(JSON.stringify({...productFixture, sudoku: sudokuGame}));
  expect(parsed.sudoku).toEqual(sudokuGame);
  // Out-of-range cell, wrong grid length, and a note outside 1...9 are all malformed.
  expect(() => parseProductSnapshot(JSON.stringify({...productFixture, sudoku: {...sudokuGame, values: Array(81).fill(10)}}))).toThrow(TypeError);
  expect(() => parseProductSnapshot(JSON.stringify({...productFixture, sudoku: {...sudokuGame, values: [1, 2, 3]}}))).toThrow(TypeError);
  expect(() => parseProductSnapshot(JSON.stringify({...productFixture, sudoku: {...sudokuGame, notes: [...sudokuGame.notes.slice(0, 80), [0]]}}))).toThrow(TypeError);
  expect(parseProductSnapshot(JSON.stringify({...productFixture, sudoku: undefined})).sudoku).toBeUndefined();
});

test('sudoku commands are part of the wire union', () => {
  const commands: ProductCommand[] = [{type: 'sudoku.new'}, {type: 'sudoku.save', game: sudokuGame}];
  expect(commands).toHaveLength(2);
});

const dnsChoice: DNSChoice = {id: 'device-dns|IP', name: 'Device DNS', primary: '', secondary: '', transport: 'IP', metadata: 'Device DNS'};
const dnsFixture = {
  customDraft: null,
  customDraftToken: '',
  tiers: [dnsChoice],
  tiersContext: 'device-dns|IP;0',
  choices: [dnsChoice, {id: 'mullvad|DoH', name: 'Mullvad', primary: '', secondary: '', transport: 'DoH', metadata: 'https://dns.mullvad.net/dns-query'}],
  editable: true,
  transportDetail: 'IP uses standard DNS.',
  custom: {name: '', primary: '', secondary: '', valid: false, metadata: 'Use your own resolver.', context: 'device-dns|IP;0;;'},
  deviceDetail: 'Device DNS',
  fallbackDetail: 'Encrypted fallback.',
  providers: [{id: 'mullvad', name: 'Mullvad', address: 'https://dns.mullvad.net/dns-query', selected: false}],
  customSelected: false,
  transports: ['IP'],
};

test('accepts the canonical dns snapshot family and rejects malformed rows', () => {
  const parsed = parseProductSnapshot(JSON.stringify({...productFixture, dns: dnsFixture}));
  expect(parsed.dns).toEqual(dnsFixture);
  // A DNS family must carry providers/customSelected/transports and well-formed choices.
  expect(() => parseProductSnapshot(JSON.stringify({...productFixture, dns: {...dnsFixture, providers: [{id: 'mullvad'}]}}))).toThrow(TypeError);
  expect(() => parseProductSnapshot(JSON.stringify({...productFixture, dns: {...dnsFixture, customSelected: 'no'}}))).toThrow(TypeError);
  expect(() => parseProductSnapshot(JSON.stringify({...productFixture, dns: {...dnsFixture, transports: [1]}}))).toThrow(TypeError);
  expect(() => parseProductSnapshot(JSON.stringify({...productFixture, dns: {...dnsFixture, tiers: [{id: 'mullvad|DoH'}]}}))).toThrow(TypeError);
  expect(() => parseProductSnapshot(JSON.stringify({...productFixture, dns: {...dnsFixture, custom: {name: '', primary: '', secondary: '', valid: 'no', metadata: 'm', context: 'c'}}}))).toThrow(TypeError);
  expect(parseProductSnapshot(JSON.stringify({...productFixture, dns: undefined})).dns).toBeUndefined();
});

test('accepts a per-tier isEnabled flag and rejects a non-boolean one', () => {
  const withEnabled = {...dnsChoice, isEnabled: false};
  const parsed = parseProductSnapshot(JSON.stringify({...productFixture, dns: {...dnsFixture, tiers: [dnsChoice, withEnabled]}}));
  expect(parsed.dns?.tiers?.[1]?.isEnabled).toBe(false);
  expect(() => parseProductSnapshot(JSON.stringify({...productFixture, dns: {...dnsFixture, tiers: [{...dnsChoice, isEnabled: 'no'}]}}))).toThrow(TypeError);
});

test('dns commands are part of the wire union', () => {
  const commands: ProductCommand[] = [
    {type: 'dns.tiers', context: 'device-dns|IP;0', tiers: [{id: 'device-dns|IP', name: 'Device DNS', primary: '', secondary: '', isEnabled: false}]},
    {type: 'dns.toggle', context: 'device-dns|IP;0', index: 1, value: true},
    {type: 'dns.customDraft', choice: dnsChoice},
    {type: 'dns.custom', context: 'device-dns|IP;0;;', name: 'Home', primary: 'https://dns.example/query', secondary: ''},
    {type: 'customEntry.dismiss', id: 'token-1'},
  ];
  expect(commands).toHaveLength(5);
});

test('accepts the canonical backup snapshot family and rejects a false-off enablement', () => {
  const parsed = parseProductSnapshot(JSON.stringify(productFixture));
  expect(parsed.backup.enablement.state).toBe('off');
  expect(parsed.backup.enablement.value).toBe(false);
  // A pending deletion or unavailable verdict must carry null, never a definitive boolean.
  const deletionPending = {
    ...productFixture,
    backup: {
      ...productFixture.backup,
      enablement: {...productFixture.backup.enablement, state: 'deletionPending', value: null},
      deletionPending: true,
    },
  };
  expect(parseProductSnapshot(JSON.stringify(deletionPending)).backup.enablement.value).toBeNull();
  expect(() => parseProductSnapshot(JSON.stringify({
    ...productFixture,
    backup: {...productFixture.backup, enablement: {...productFixture.backup.enablement, state: 'deletionPending', value: false}},
  }))).toThrow(TypeError);
  expect(() => parseProductSnapshot(JSON.stringify({
    ...productFixture,
    backup: {...productFixture.backup, enablement: {...productFixture.backup.enablement, value: null}},
  }))).toThrow(TypeError);
  expect(() => parseProductSnapshot(JSON.stringify({
    ...productFixture,
    backup: {...productFixture.backup, remoteStatus: 'maybe'},
  }))).toThrow(TypeError);
});

test('backup commands are part of the wire union', () => {
  const commands: ProductCommand[] = [
    {type: 'backup.refresh'},
    {type: 'backup.now'},
    {type: 'backup.disable'},
    {type: 'backup.delete'},
  ];
  expect(commands).toHaveLength(4);
});

test('canonical backup native flows are declared so the bridge can answer them honestly', () => {
  const commands: ProductCommand[] = [
    {type: 'native.flow', flow: 'backupSetup'},
    {type: 'native.flow', flow: 'backupRestore'},
  ];
  expect(commands).toHaveLength(2);
});

test('accepts the canonical account family and rejects a fabricated link or missing capability', () => {
  // No owner: the family is omitted entirely, so JS never fabricates an account.
  expect(parseProductSnapshot(JSON.stringify(productFixture)).account).toBeUndefined();

  const account = {
    signedIn: true, status: 'Signed in with Google', detail: '', appleTitle: 'Sign in with Apple',
    googleTitle: 'Signed in with Google', appleConnected: false, googleConnected: true,
    appleBusy: false, googleBusy: false, busy: false, message: '',
    googleAvailable: true, appleAvailable: false,
  };
  expect(parseProductSnapshot(JSON.stringify({...productFixture, account})).account).toEqual(account);

  for (const malformed of [
    {...account, signedIn: 'yes'},
    {...account, googleConnected: 1},
    {...account, status: 4},
    {...account, appleBusy: 'no'},
    {...account, googleAvailable: undefined},
    {...account, appleAvailable: 'false'},
  ]) {
    expect(() => parseProductSnapshot(JSON.stringify({...productFixture, account: malformed}))).toThrow(TypeError);
  }
});

test('account commands are part of the wire union', () => {
  const commands: ProductCommand[] = [
    {type: 'account.google'},
    {type: 'account.apple'},
    {type: 'account.signOut'},
    {type: 'account.delete'},
  ];
  expect(commands).toHaveLength(4);
});

test('filter.refresh is part of the wire union', () => {
  const commands: ProductCommand[] = [{type: 'filter.refresh', id: 'active'}];
  expect(commands).toHaveLength(1);
});

test('accepts the canonical catalog snapshot family and rejects a fabricated provenance', () => {
  const parsed = parseProductSnapshot(JSON.stringify(productFixture));
  expect(parsed.catalog?.installed).toBe('bundled');
  expect(parsed.catalog?.generatedAt).toBe(0);

  const installed = {
    ...productFixture,
    catalog: {...productFixture.catalog!, installed: 'lastKnownGood', version: 'catalog-9', ageSeconds: 120, lastStatus: 'updated'},
  };
  expect(parseProductSnapshot(JSON.stringify(installed)).catalog).toEqual(installed.catalog);

  expect(() => parseProductSnapshot(JSON.stringify({
    ...productFixture, catalog: {...productFixture.catalog!, installed: 'trusted'},
  }))).toThrow(TypeError);
  expect(() => parseProductSnapshot(JSON.stringify({
    ...productFixture, catalog: {...productFixture.catalog!, lastStatus: 'maybe'},
  }))).toThrow(TypeError);
  expect(() => parseProductSnapshot(JSON.stringify({
    ...productFixture, catalog: {...productFixture.catalog!, ageSeconds: -1},
  }))).toThrow(TypeError);
  expect(() => parseProductSnapshot(JSON.stringify({
    ...productFixture, catalog: {...productFixture.catalog!, sourceCount: 1.5},
  }))).toThrow(TypeError);
  // A snapshot without a sync owner omits the family entirely.
  expect(parseProductSnapshot(JSON.stringify({...productFixture, catalog: undefined})).catalog).toBeUndefined();
});

test('accepts the canonical connection family and rejects a fabricated shape', () => {
  const connection = {
    filter: {id: 'filter1', name: 'My filter', count: '84'},
    dns: {
      usesWireGuard: false, editable: true,
      primary: {name: 'Device DNS', detail: '', transport: ''},
      fallback: {name: 'Mullvad', detail: 'https://dns.mullvad.net/dns-query', transport: 'DoH'},
    },
    vpn: {eligible: false, enabled: false, fallbackEnabled: false, configured: false, statusLabel: 'Not configured'},
  };
  expect(parseProductSnapshot(JSON.stringify({...productFixture, connection})).connection).toEqual(connection);
  // No owner: the family is omitted entirely rather than fabricated.
  expect(parseProductSnapshot(JSON.stringify({...productFixture, connection: undefined})).connection).toBeUndefined();
  expect(() => parseProductSnapshot(JSON.stringify({
    ...productFixture, connection: {...connection, dns: {...connection.dns, primary: {name: 1, detail: '', transport: ''}}},
  }))).toThrow(TypeError);
  expect(() => parseProductSnapshot(JSON.stringify({
    ...productFixture, connection: {...connection, dns: {...connection.dns, fallback: {name: 'x'}}},
  }))).toThrow(TypeError);
  expect(() => parseProductSnapshot(JSON.stringify({
    ...productFixture, connection: {...connection, vpn: {...connection.vpn, eligible: 'yes'}},
  }))).toThrow(TypeError);
  expect(() => parseProductSnapshot(JSON.stringify({
    ...productFixture, connection: {...connection, vpn: {...connection.vpn, fallbackEnabled: 'maybe'}},
  }))).toThrow(TypeError);
});

test('accepts the canonical presentation and settingsSummary families', () => {
  const parsed = parseProductSnapshot(JSON.stringify(productFixture));
  expect(parsed.presentation).toEqual({locale: 'en', textScales: null});
  expect(parsed.settingsSummary).toEqual({dns: 'Mullvad DNS', privacy: 'All local logs on', security: 'Passcode off'});

  // The text scale must be a finite number when system matching is off.
  const scaled = {...productFixture, presentation: {locale: 'de', textScales: {body: 1.12}}};
  expect(parseProductSnapshot(JSON.stringify(scaled)).presentation?.textScales).toEqual({body: 1.12});

  // A field with no live owner is omitted, never fabricated.
  const partial = {...productFixture, settingsSummary: {security: 'Passcode on'}};
  expect(parseProductSnapshot(JSON.stringify(partial)).settingsSummary).toEqual({security: 'Passcode on'});

  expect(() => parseProductSnapshot(JSON.stringify({...productFixture, presentation: {locale: 1, textScales: null}}))).toThrow(TypeError);
  expect(() => parseProductSnapshot(JSON.stringify({...productFixture, presentation: {locale: 'en', textScales: {body: 'big'}}}))).toThrow(TypeError);
  expect(() => parseProductSnapshot(JSON.stringify({...productFixture, settingsSummary: {dns: 4}}))).toThrow(TypeError);
  expect(parseProductSnapshot(JSON.stringify({...productFixture, presentation: undefined})).presentation).toBeUndefined();
});

test('accepts the canonical security snapshot family and rejects fabrications', () => {
  const security = {
    unavailable: false, hasAuthenticationMethod: true, updatingSurface: false, recoveryRequired: false,
    failure: null, protectedSurfaces: ['appUnlock', 'appSettings'], authenticatedSurfaces: ['appSettings'],
    showBiometrics: false, canEnableBiometrics: false, biometricLockout: false,
    biometricTitle: 'Biometrics', status: '', biometrics: false, lockTimeoutMinutes: 5,
  };
  const parsed = parseProductSnapshot(JSON.stringify({...productFixture, security}));
  expect(parsed.security).toEqual(security);
  // Only `unavailable` is required; the rest is a partial projection, never a fabricated value.
  expect(parseProductSnapshot(JSON.stringify({...productFixture, security: {unavailable: true}})).security).toEqual({unavailable: true});
  expect(parseProductSnapshot(JSON.stringify({...productFixture, security: undefined})).security).toBeUndefined();
  expect(() => parseProductSnapshot(JSON.stringify({...productFixture, security: {...security, unavailable: 'no'}}))).toThrow(TypeError);
  expect(() => parseProductSnapshot(JSON.stringify({...productFixture, security: {...security, failure: 'meltdown'}}))).toThrow(TypeError);
  expect(() => parseProductSnapshot(JSON.stringify({...productFixture, security: {...security, protectedSurfaces: ['adminAll']}}))).toThrow(TypeError);
  expect(() => parseProductSnapshot(JSON.stringify({...productFixture, security: {...security, authenticatedSurfaces: ['appUnlock', 2]}}))).toThrow(TypeError);
  expect(() => parseProductSnapshot(JSON.stringify({...productFixture, security: {...security, biometricTitle: 7}}))).toThrow(TypeError);
  expect(() => parseProductSnapshot(JSON.stringify({...productFixture, security: {...security, lockTimeoutMinutes: -1}}))).toThrow(TypeError);
});

test('the canonical settings.set command is part of the wire union', () => {
  const commands: ProductCommand[] = [
    {type: 'settings.set', key: 'appearance', value: 'dark'},
    {type: 'settings.set', key: 'matchTextSize', value: true},
    {type: 'settings.set', key: 'textSize', value: 6},
  ];
  expect(commands).toHaveLength(3);
});

test('retention settings require native boolean facts',()=>{
  const logging={keepCounts:true,keepHistory:false,keepNetworkActivity:true,keepLavaGuardProgress:true};
  expect(parseProductSnapshot(JSON.stringify({...productFixture,logging})).logging).toEqual(logging);
  expect(()=>parseProductSnapshot(JSON.stringify({...productFixture,logging:{...logging,keepLavaGuardProgress:'true'}}))).toThrow();
});

const newFilterFixture = {id:'new', name:'Untitled 1', emoji:'🌿', lists:['template'], empty:false, blockedDomainCount:1, allowedExceptionCount:0};
test('accepts an owned creation separately from the saved library and refuses malformed or private creation facts', () => {
  expect(parseProductSnapshot(JSON.stringify({...productFixture,newFilter:newFilterFixture})).newFilter).toEqual(newFilterFixture);
  for (const newFilter of [
    {...newFilterFixture,id:productFixture.filters.rows[0]!.id},
    {...newFilterFixture,emoji:42}, {...newFilterFixture,lists:['ok',1]},
    {...newFilterFixture,blockedDomainCount:null}, {...newFilterFixture,blockedDomainCount:-1},
    {...newFilterFixture,allowedExceptionCount:Number.MAX_SAFE_INTEGER+1},
  ]) expect(() => parseProductSnapshot(JSON.stringify({...productFixture,newFilter}))).toThrow(TypeError);
  for (const protectedState of [
    {...productFixture,available:false}, {...productFixture,foreground:false},
    {...productFixture,filters:{...productFixture.filters,locked:true,rows:[]}},
  ]) expect(() => parseProductSnapshot(JSON.stringify({...protectedState,newFilter:newFilterFixture}))).toThrow(TypeError);
});

const foregroundBase={id:'visit-1',kind:'vpnConfiguration',name:'',emoji:'',dismissAttempt:0,canCreate:false,templates:[]};
const vpnEditor={name:'Home',nameResetRevision:0,dirty:true,hasContent:true,concealed:true,reading:false,canEdit:true,canSave:true,error:''};
test('accepts only public shared VPN editor metadata and rejects confidential buffer projections',()=>{
  const fixture={...productFixture,foregroundFlow:{...foregroundBase,vpnEditor}};
  expect(parseProductSnapshot(JSON.stringify(fixture)).foregroundFlow?.vpnEditor).toEqual(vpnEditor);
  for(const confidential of [{text:'[Interface]'}, {configuration:'PrivateKey=secret'}, {privateKey:'secret'}]){
    expect(()=>parseProductSnapshot(JSON.stringify({...fixture,foregroundFlow:{...foregroundBase,vpnEditor:{...vpnEditor,...confidential}}}))).toThrow('Confidential editor projection');
  }
});
test('validates shared feedback review and receipt state before the page reads it',()=>{
  const feedback={topic:'featureIssue',site:'',details:'An issue',email:'',diagnostics:false,step:1,furthest:1,revision:'2',review:'',normalizedSite:'',normalizedDetails:'An issue',normalizedEmail:'',count:8,canContinue:true,dirty:true,busy:false,prepared:false,sent:false,error:'',receipt:'',copied:false,topics:[{id:'featureIssue',title:'Feature'}]};
  const fixture={...productFixture,foregroundFlow:{...foregroundBase,kind:'feedback',feedback}};
  expect(parseProductSnapshot(JSON.stringify(fixture)).foregroundFlow?.feedback).toEqual(feedback);
  for(const malformed of [{prepared:'yes'},{receipt:42},{topics:[{id:'featureIssue'}]},{step:-1}]){
    expect(()=>parseProductSnapshot(JSON.stringify({...fixture,foregroundFlow:{...fixture.foregroundFlow,feedback:{...feedback,...malformed}}}))).toThrow(TypeError);
  }
});


test.each([['GuardTab','Guard'],['GuardTab','Filters'],['GuardTab','Activity'],['SettingsTab','Settings']])('accepts only a completed native entry to %s/%s', (tab,screen)=>{
  const navigation={serial:1,tab,screen};
  expect(parseProductSnapshot(JSON.stringify({...productFixture,navigation})).navigation).toEqual(navigation);
});
test.each([
  {serial:0,tab:'GuardTab',screen:'Guard'}, {serial:1.5,tab:'GuardTab',screen:'Guard'},
  {serial:1,tab:'SettingsTab',screen:'Activity'}, {serial:1,tab:'GuardTab',screen:'Security'},
  {serial:1,tab:'GuardTab',screen:'Guard',uri:'lavasecurity://guard'},
])('rejects malformed or wider native redirect metadata', navigation=>{
  expect(()=>parseProductSnapshot(JSON.stringify({...productFixture,navigation}))).toThrow(TypeError);
});
test.each([
  {kind:'Guard array',screen:['Guard']}, {kind:'Filters array',screen:['Filters']},
  {kind:'Activity array',screen:['Activity']}, {kind:'Settings array',screen:['Settings']},
  {kind:'object',screen:{name:'Guard'}}, {kind:'number',screen:42},
  {kind:'boolean',screen:true}, {kind:'null',screen:null},
])('rejects a native redirect screen with a non-string type: $kind', ({screen})=>{
  for(const tab of ['GuardTab','SettingsTab']) {
    const navigation={serial:1,tab,screen};
    expect(()=>parseProductSnapshot(JSON.stringify({...productFixture,navigation}))).toThrow('Invalid native navigation');
  }
});
test.each([{generation:null},{onboardingRequired:true}])('refuses a redirect before the actual native graph and setup completion', boundary=>{
  expect(()=>parseProductSnapshot(JSON.stringify({...productFixture,...boundary,navigation:{serial:1,tab:'GuardTab',screen:'Guard'}}))).toThrow(TypeError);
});


test('DNS default-name provenance is optional for legacy wire and strictly boolean when present',()=>{
  const part={name:'Device DNS',detail:'',transport:''};
  const connection={dns:{primary:part},vpn:{eligible:false,enabled:false}};
  for(const provenance of [undefined,true,false]){
    const primary={...part,...(provenance===undefined?{}:{nameIsDefault:provenance})};
    expect(parseProductSnapshot(JSON.stringify({...productFixture,connection:{...connection,dns:{primary}}})).connection?.dns.primary).toEqual(primary);
  }
  const choice={id:'custom-dns|DoH',name:'Custom DNS',primary:'https://dns.example/query',secondary:'',transport:'DoH',metadata:''};
  for(const provenance of [undefined,true,false]){
    const tier={...choice,...(provenance===undefined?{}:{nameIsDefault:provenance})};
    const dns={providers:[],customSelected:true,transports:['DoH'],tiers:[tier],choices:[tier]};
    expect(parseProductSnapshot(JSON.stringify({...productFixture,dns})).dns?.tiers?.[0]).toEqual(tier);
  }
  for(const nameIsDefault of ['true',1,null]){
    expect(()=>parseProductSnapshot(JSON.stringify({...productFixture,connection:{...connection,dns:{primary:{...part,nameIsDefault}}}}))).toThrow(TypeError);
    expect(()=>parseProductSnapshot(JSON.stringify({...productFixture,connection:{...connection,dns:{primary:part,fallback:{...part,nameIsDefault}}}}))).toThrow(TypeError);
    for(const field of ['tiers','choices','customDraft']){
      const bad={...choice,nameIsDefault};
      const dns={providers:[],customSelected:true,transports:['DoH'],[field]:field==='customDraft'?bad:[bad]};
      expect(()=>parseProductSnapshot(JSON.stringify({...productFixture,dns}))).toThrow(TypeError);
    }
  }
});

const playEdition={id:'play',accounts:true,cloudBackup:true,purchases:true,localFeaturesUnlocked:false,portableBackup:true,nativeSupport:true};
const fossEdition={id:'foss',accounts:false,cloudBackup:false,purchases:false,localFeaturesUnlocked:true,portableBackup:true,nativeSupport:false};
test('native edition capabilities are validated before choosing account and purchase screens',()=>{
  for(const edition of [playEdition,fossEdition])expect(parseProductSnapshot(JSON.stringify({...productFixture,edition})).edition).toEqual(edition);
  expect(parseProductSnapshot(JSON.stringify(productFixture)).edition).toBeUndefined();
  const missing={...fossEdition} as Record<string,unknown>;delete missing.accounts;
  for(const edition of [null,[],false,'foss',missing,{...fossEdition,id:'unknown'},{...fossEdition,accounts:true},
    {...fossEdition,purchases:true},{...fossEdition,nativeSupport:true},{...fossEdition,portableBackup:false},
    {...playEdition,localFeaturesUnlocked:true},{...fossEdition,cloudBackup:'false'},{...fossEdition,backendUrl:'https://example.com'}]) {
    expect(()=>parseProductSnapshot(JSON.stringify({...productFixture,edition}))).toThrow(TypeError);
  }
});

test('pending portable custody accepts only a nonsecret closed status',()=>{
  for(const pending of ['none','available','unreadable']){
    expect(parseProductSnapshot(JSON.stringify({...productFixture,portableBackup:{pending}})).portableBackup).toEqual({pending});
  }
  for(const portableBackup of [null,[],{},false,{pending:['available']},{pending:'applied'},{pending:'available',uri:'content://private/file'},{pending:'available',password:'secret'}]){
    expect(()=>parseProductSnapshot(JSON.stringify({...productFixture,portableBackup}))).toThrow(TypeError);
  }
});
