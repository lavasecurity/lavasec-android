const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {pathToFileURL} = require('node:url');
const {execFileSync} = require('node:child_process');
let fixture;

function createFixture() {
  if (fixture) return fixture;
  const rnRoot = path.resolve(__dirname, '..');
  const installed = path.join(rnRoot, 'node_modules/@react-navigation/core');
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'lavasec-core-provider-'));
  const dependency = path.join(root, 'core-dependency');
  fs.mkdirSync(dependency, {recursive:true});
  // Bare React imports resolve to the same installed singleton; navigation
  // contexts below are forwarding modules, never cloned createContext calls.
  fs.symlinkSync(path.join(rnRoot, 'node_modules'), path.join(root, 'node_modules'), 'dir');
  const adapter = pathToFileURL(path.join(rnRoot, 'scripts/apply-core-removal-notification-fix.mjs')).href;
  const prepare = `
    import fs from 'node:fs';import path from 'node:path';
    const dependency=process.argv[1],installed=process.argv[2];
    const {FILES,lockedOriginalCoreProvider,applyCoreRemovalNotificationFix}=await import(process.argv[3]);
    fs.copyFileSync(path.join(installed,'package.json'),path.join(dependency,'package.json'));
    for(const file of FILES){
      const target=path.join(dependency,file.relativePath);fs.mkdirSync(path.dirname(target),{recursive:true});
      const original=lockedOriginalCoreProvider(file.relativePath,fs.readFileSync(path.join(installed,file.relativePath)));
      fs.writeFileSync(target,original);
      if(file.relativePath==='lib/module/PreventRemoveProvider.js')fs.writeFileSync(path.join(dependency,'lib/module/PreventRemoveProvider.original.js'),original);
    }
    applyCoreRemovalNotificationFix(dependency);
  `;
  try {
    execFileSync(process.execPath, ['--input-type=module', '-e', prepare, dependency, installed, adapter], {stdio:'pipe'});
    const moduleDir = path.join(dependency, 'lib/module');
    for (const name of ['NavigationHelpersContext', 'NavigationProvider', 'PreventRemoveContext']) {
      const actual = path.join(installed, 'lib/module', name + '.js');
      fs.writeFileSync(path.join(moduleDir, name + '.js'), `export * from ${JSON.stringify(actual)};\n`);
    }
    fixture = {root, dependency, providerModule:path.join(moduleDir,'PreventRemoveProvider.js'), originalModule:path.join(moduleDir,'PreventRemoveProvider.original.js'), counters:{requests:0,originalRequests:0}, spy:null};
    return fixture;
  } catch (error) {
    fs.rmSync(root, {recursive:true,force:true});throw error;
  }
}

function loadAdapterProvider(jestRuntime) {
  const item = createFixture();
  if (!item.spy) {
    const React = require('react');
    const original = React.useState;
    const wrappers = new WeakMap();
    // Count calls to the real provider's dispatch without replacing its queue,
    // updater, initializer, value or setter identity. This pins the change that
    // returning prev INSIDE the original updater could not test.
    item.spy = jestRuntime.spyOn(React, 'useState').mockImplementation(initial => {
      const pair = original(initial);
      const stack=new Error().stack||'';
      const counter=stack.includes(item.originalModule)?'originalRequests':stack.includes(item.providerModule)?'requests':null;
      if(!counter)return pair;
      let wrapped = wrappers.get(pair[1]);
      if (!wrapped) {
        const dispatch = pair[1];
        wrapped = value => {item.counters[counter]++;return dispatch(value);};
        wrappers.set(dispatch, wrapped);
      }
      return [pair[0], wrapped];
    });
  }
  return jestRuntime.requireActual(item.providerModule);
}
function loadOriginalProvider(jestRuntime) {
  loadAdapterProvider(jestRuntime);
  return jestRuntime.requireActual(createFixture().originalModule);
}
function getFixture() { return createFixture(); }
function cleanup() {
  if (!fixture) return;
  fixture.spy?.mockRestore();
  fs.rmSync(fixture.root, {recursive:true,force:true});
  fixture = undefined;
}
module.exports = {loadAdapterProvider, loadOriginalProvider, getFixture, cleanup};
