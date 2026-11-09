#!/usr/bin/env node
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFileSync,readdirSync,writeFileSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

// Native and runtime JS packages in the shipped shared presentation. RN's other dependencies
// are build tooling, so do not label the CLI/codegen/compiler dependency tree as shipped code.
const rnRuntime=['@babel/runtime','@react-native/asset-utils','@react-native/js-polyfills',
  '@react-native/normalize-colors','@react-native/virtualized-lists','base64-js','flow-enums-runtime',
  'invariant','memoize-one','metro-runtime','nullthrows','promise','regenerator-runtime','scheduler',
  'stacktrace-parser','whatwg-fetch'];
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const assetRoot=path.resolve(root,'../app/src/main/assets/licenses');
const lockBytes=readFileSync(path.join(root,'package-lock.json'));
const lock=JSON.parse(lockBytes);const manifest=JSON.parse(readFileSync(path.join(root,'package.json')));
const records=new Map();
const digest=value=>createHash('sha256').update(value).digest('hex');
function resolveDependency(name,parent='') {
  for(let cursor=parent;;cursor=path.posix.dirname(cursor)) {
    const candidate=path.posix.join(cursor==='.'?'':cursor,'node_modules',name);
    if(lock.packages[candidate])return candidate;
    if(!cursor||cursor==='.')break;
  }
  throw new Error(`Unpinned runtime dependency ${name} from ${parent}`);
}
function sourceURL(pkg) {
  const repo=typeof pkg.repository==='string'?pkg.repository:pkg.repository?.url;
  return (repo??pkg.homepage??'').replace(/^git\+/,'').replace(/^git:\/\//,'https://')
    .replace(/^ssh:\/\/git@github.com\//,'https://github.com/').replace(/\.git$/,'');
}
function add(name,parent='') {
  const relative=resolveDependency(name,parent);
  if(records.has(relative))return;
  const directory=path.join(root,relative);
  const pkg=JSON.parse(readFileSync(path.join(directory,'package.json')));
  assert.equal(pkg.version,lock.packages[relative].version,`Installed ${name} differs from lockfile`);
  const licenseFile=readdirSync(directory).sort().find(file=>/^(license|licence|copying)(\.(txt|md|markdown))?$/i.test(file));
  // RN's scoped runtime packages refer to the root monorepo MIT license in their source headers;
  // npm omits that sibling file. Use the same-version installed RN distribution's full text.
  const rnSibling=!licenseFile&&name.startsWith('@react-native/')&&pkg.license==='MIT'&&
    pkg.version===lock.packages['node_modules/react-native'].version;
  // standard-navigation's npm files list omits its upstream LICENSE. The pinned public Git
  // blob below is packaged verbatim; a version change requires re-reviewing this fallback.
  const standardNavigation=!licenseFile&&name==='standard-navigation'&&pkg.version==='0.0.8'&&pkg.license==='MIT';
  const metroRuntime=!licenseFile&&name==='metro-runtime'&&pkg.version==='0.87.1'&&pkg.license==='MIT';
  const boolbase=!licenseFile&&name==='boolbase'&&pkg.version==='1.0.0'&&pkg.license==='ISC';
  assert.ok(licenseFile||rnSibling||standardNavigation||metroRuntime||boolbase,`Installed runtime ${name} has no full license file`);
  const licenseSource=boolbase?'../app/src/main/assets/licenses/BOOLBASE-LICENSE':
    standardNavigation?'../app/src/main/assets/licenses/STANDARD-NAVIGATION-LICENSE':
    rnSibling||metroRuntime?'node_modules/react-native/LICENSE':path.posix.join(relative,licenseFile);
  const text=readFileSync(path.join(root,licenseSource),'utf8').trimEnd()+'\n';
  if(standardNavigation)assert.equal(createHash('sha1').update(`blob ${Buffer.byteLength(text)}\0`).update(text).digest('hex'),
    '887f80b0b9ca1ec0fb56a435eaf25ca1a009a0f7','Vendored standard-navigation license changed');
  if(metroRuntime)assert.equal(createHash('sha1').update(`blob ${Buffer.byteLength(text)}\0`).update(text).digest('hex'),
    'b93be90515ccd0b9daedaa589e42bf5929693f1f','Upstream Metro runtime root MIT license differs');
  if(boolbase)assert.equal(createHash('sha1').update(`blob ${Buffer.byteLength(text)}\0`).update(text).digest('hex'),
    'a689aae96e8957e4ab7a3a7449fe5271a6516e4b','Vendored boolbase license changed');
  assert.ok(text.length>60,`Incomplete installed license for ${name}`);
  records.set(relative,{id:relative,name:pkg.name,version:pkg.version,license:pkg.license??'See license text',
    sourceUrl:sourceURL(pkg),licenseSource,...(rnSibling?{licenseBasis:'Same-version React Native monorepo root MIT license referenced by scoped runtime source headers.'}:{}),
    ...(standardNavigation?{licenseBasis:'Pinned upstream LICENSE Git blob 887f80b0b9ca1ec0fb56a435eaf25ca1a009a0f7; npm 0.0.8 omits this file.'}:{}),sha256:digest(text),text});
  if(metroRuntime)records.get(relative).licenseBasis='Upstream facebook/metro LICENSE Git blob b93be90515ccd0b9daedaa589e42bf5929693f1f; npm 0.87.1 omits its root license, identical to the installed RN MIT text.';
  if(boolbase)records.get(relative).licenseBasis='Pinned upstream fb55/boolbase LICENSE Git blob a689aae96e8957e4ab7a3a7449fe5271a6516e4b; npm 1.0.0 omits this file.';
  if(name==='react-native')return;
  for(const dependency of Object.keys(pkg.dependencies??{}).sort())add(dependency,relative);
}
for(const name of [...new Set([...Object.keys(manifest.dependencies),...rnRuntime])].sort())add(name);

// Hermes' installed RN podspec identifies the MIT license in its distribution root. Match the
// pinned native VM version against the installed compiler, and package that full upstream text.
// No compiler executable or tooling license is presented as runtime JavaScript.
const rn=records.get('node_modules/react-native');
const hermesVersion=/^HERMES_VERSION_NAME=(.+)$/m.exec(readFileSync(path.join(root,
  'node_modules/react-native/sdks/hermes-engine/version.properties'),'utf8'))?.[1];
assert.ok(hermesVersion,'Installed React Native must pin Hermes');
assert.equal(JSON.parse(readFileSync(path.join(root,'node_modules/hermes-compiler/package.json'))).version,hermesVersion);
assert.equal(lock.packages['node_modules/hermes-compiler'].version,hermesVersion);
assert.match(readFileSync(path.join(root,'node_modules/react-native/sdks/hermes-engine/hermes-engine.podspec'),'utf8'),
  /LICENSE file in the root directory/);
records.set('native/hermes-android',{id:'native/hermes-android',name:'Hermes',version:hermesVersion,license:'MIT',
  sourceUrl:'https://github.com/facebook/hermes',licenseSource:rn.licenseSource,
  licenseBasis:'Hermes installed podspec references the MIT license in the React Native distribution root.',
  sha256:rn.sha256,text:rn.text});
const notices=[...records.values()].sort((a,b)=>a.id.localeCompare(b.id));
const outputs={
  'react-native-notices.json':JSON.stringify({schema:1,lockSha256:digest(lockBytes),notices},null,2)+'\n',
  'REACT-NATIVE-NOTICES.txt':'React Native Android presentation dependency notices\n'+
    'Full upstream license text from the installed, lockfile-pinned runtime dependencies.\n\n'+
    notices.map(notice=>`${notice.name}@${notice.version}\n${'='.repeat(72)}\n${notice.text}`).join('\n\n'),
};
assert.ok(process.argv.includes('--check')||process.argv.includes('--write'),'Use --check or --write');
for(const [name,expected] of Object.entries(outputs)) {
  const output=path.join(assetRoot,name);
  if(process.argv.includes('--write'))writeFileSync(output,expected);
  else assert.equal(readFileSync(output,'utf8'),expected,`${name} is stale; run node scripts/react-native-notices.mjs --write`);
}
process.stdout.write(`Verified ${notices.length} full runtime dependency notices.\n`);
