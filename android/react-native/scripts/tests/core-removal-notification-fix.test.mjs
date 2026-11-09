import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFileSync,writeFileSync,mkdtempSync,mkdirSync,rmSync,statSync,cpSync,readdirSync,unlinkSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
import {applyCoreRemovalNotificationFix,lockedOriginalCoreProvider,FILES,PIN} from '../apply-core-removal-notification-fix.mjs';
const rnRoot=resolve(dirname(fileURLToPath(import.meta.url)),'../..');
const installed=resolve(rnRoot,'node_modules/@react-navigation/core');
const script=resolve(rnRoot,'scripts/apply-core-removal-notification-fix.mjs');
const sha=bytes=>createHash('sha256').update(bytes).digest('hex');
function fixture(run){
 const root=mkdtempSync(resolve(tmpdir(),'lavasec-core-removal-'));
 const dependency=resolve(root,'node_modules/@react-navigation/core');
 mkdirSync(dependency,{recursive:true});
 const metadata=readFileSync(resolve(installed,'package.json'));
 assert.equal(sha(metadata),PIN.packageSha256);
 writeFileSync(resolve(dependency,'package.json'),metadata);
 const originals=FILES.map(file=>{
  const bytes=lockedOriginalCoreProvider(file.relativePath,readFileSync(resolve(installed,file.relativePath)));
  assert.equal(sha(bytes),file.originalSha256);
  const path=resolve(dependency,file.relativePath);mkdirSync(dirname(path),{recursive:true});
  writeFileSync(path,bytes,{mode:0o640});return {file,path,bytes};
 });
 try{run({root,dependency,originals,metadata});}finally{rmSync(root,{recursive:true,force:true});}
}
const assertOriginal=f=>{for(const item of f.originals)assert.deepEqual(readFileSync(item.path),item.bytes);};
const assertPatched=f=>{for(const item of f.originals)assert.equal(sha(readFileSync(item.path)),item.file.patchedSha256);};

test('patches both actual runtime module and source with pinned hashes and no stale module source map',()=>fixture(f=>{
 assert.equal(applyCoreRemovalNotificationFix(f.dependency),'patched');assertPatched(f);
 assert.deepEqual(readFileSync(resolve(f.dependency,'package.json')),f.metadata);
 for(const item of f.originals){assert.equal(statSync(item.path).mode&0o777,0o640);assert.deepEqual(readdirSync(dirname(item.path)),[item.file.relativePath.split('/').at(-1)]);}
 assert.doesNotMatch(readFileSync(f.originals[1].path,'utf8'),/sourceMappingURL=/);
}));
test('idempotent repeat leaves both installed native images untouched',()=>fixture(f=>{
 applyCoreRemovalNotificationFix(f.dependency);const stats=f.originals.map(item=>statSync(item.path));
 assert.equal(applyCoreRemovalNotificationFix(f.dependency),'already-patched');assertPatched(f);
 f.originals.forEach((item,index)=>{assert.equal(statSync(item.path).mtimeMs,stats[index].mtimeMs);assert.equal(statSync(item.path).ino,stats[index].ino);});
}));
test('different package version fails before either image changes',()=>fixture(f=>{
 const path=resolve(f.dependency,'package.json');const json=JSON.parse(readFileSync(path));json.version='7.21.14';writeFileSync(path,JSON.stringify(json));
 assert.throws(()=>applyCoreRemovalNotificationFix(f.dependency),/expected @react-navigation\/core 7\.21\.13/);assertOriginal(f);
}));
test('same version with unknown package bytes fails before writes',()=>fixture(f=>{
 const path=resolve(f.dependency,'package.json');writeFileSync(path,Buffer.concat([f.metadata,Buffer.from('\n')]));
 assert.throws(()=>applyCoreRemovalNotificationFix(f.dependency),/package metadata hash changed/);assertOriginal(f);
}));
for(const index of [0,1])test(`unknown ${index===0?'source':'runtime module'} preflight leaves the other image unchanged`,()=>fixture(f=>{
 const item=f.originals[index],other=f.originals[1-index];const changed=Buffer.concat([item.bytes,Buffer.from('\n// unsupported change\n')]);writeFileSync(item.path,changed);
 const stat=statSync(other.path);
 assert.throws(()=>applyCoreRemovalNotificationFix(f.dependency),/provider source hash changed/);
 assert.deepEqual(readFileSync(item.path),changed);assert.deepEqual(readFileSync(other.path),other.bytes);assert.equal(statSync(other.path).ino,stat.ino);
}));
test('missing runtime image cannot partially patch a valid source image',()=>fixture(f=>{
 unlinkSync(f.originals[1].path);
 assert.throws(()=>applyCoreRemovalNotificationFix(f.dependency),/ENOENT/);
 assert.deepEqual(readFileSync(f.originals[0].path),f.originals[0].bytes);
}));
for(const index of [0,1])test(`restart after only ${index===0?'source':'runtime'} atomic rename repairs the remaining exact preimage`,()=>fixture(f=>{
 applyCoreRemovalNotificationFix(f.dependency);const already=f.originals[index],remaining=f.originals[1-index];const stats=statSync(already.path);
 // The durable state after process interruption between atomic file renames.
 writeFileSync(remaining.path,remaining.bytes);
 assert.equal(applyCoreRemovalNotificationFix(f.dependency),'patched');assertPatched(f);
 assert.equal(statSync(already.path).ino,stats.ino);assert.equal(statSync(already.path).mtimeMs,stats.mtimeMs);
}));
test('fixture reconstruction accepts only exact original or exact repaired provider bytes',()=>fixture(f=>{
 for(const item of f.originals)assert.deepEqual(lockedOriginalCoreProvider(item.file.relativePath,item.bytes),item.bytes);
 applyCoreRemovalNotificationFix(f.dependency);
 for(const item of f.originals)assert.deepEqual(lockedOriginalCoreProvider(item.file.relativePath,readFileSync(item.path)),item.bytes);
 assert.throws(()=>lockedOriginalCoreProvider(FILES[0].relativePath,Buffer.from('not a provider')),/fixture provider source hash changed/);
 assert.throws(()=>lockedOriginalCoreProvider('../outside',f.originals[0].bytes),/unknown provider path/);
}));
test('CLI selects only its own mirrored RN package and refuses target arguments',()=>fixture(f=>{
 const target=resolve(f.root,'scripts/apply-core-removal-notification-fix.mjs');mkdirSync(dirname(target),{recursive:true});cpSync(script,target);
 for(const status of ['patched','already-patched']){const result=spawnSync(process.execPath,[target],{encoding:'utf8'});assert.equal(result.status,0,result.stderr);assert.equal(result.stdout.trim(),`Core 7.21.13 removal queue repair: ${status}`);}
 const refused=spawnSync(process.execPath,[target,f.dependency],{encoding:'utf8'});assert.notEqual(refused.status,0);assert.match(refused.stderr,/unexpected CLI arguments/);assertPatched(f);
}));
test('missing dependency fails instead of silently omitting the runtime repair',()=>{
 const root=mkdtempSync(resolve(tmpdir(),'lavasec-core-missing-'));try{assert.throws(()=>applyCoreRemovalNotificationFix(root),/ENOENT/);assert.deepEqual(readdirSync(root),[]);}finally{rmSync(root,{recursive:true,force:true});}
});
