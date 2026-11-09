import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {mkdtempSync, rmSync, writeFileSync} from 'node:fs';
import {createRequire} from 'node:module';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import test from 'node:test';
import {fileURLToPath} from 'node:url';

// Ported from iOS-internal c9ab2530: exercise Android's actual pinned Metro parser.
const require = createRequire(import.meta.url);
test('palette resolution is limited to Android canonical callers and never wraps its own native imports',()=>{
  const root=fileURLToPath(new URL('../../',import.meta.url));
  const resolve=require('../../metro.config.js').resolver.resolveRequest;
  const context=origin=>({originModulePath:join(root,origin),resolveRequest:(_context,moduleName,platform)=>({moduleName,platform})});
  for(const [moduleName,file] of [['react-native','appearance-native.tsx'],['react-native-svg','appearance-svg.tsx']]) {
    assert.deepEqual(resolve(context('canonical/review/screens.tsx'),moduleName,'android'),{moduleName:join(root,'platform',file),platform:'android'});
    for(const origin of ['canonical/review/screens.tsx','platform/with-palette.tsx','node_modules/react-native/internal.js']) {
      assert.deepEqual(resolve(context(origin),moduleName,'ios'),{moduleName,platform:'ios'});
    }
    for(const origin of ['platform/with-palette.tsx','node_modules/react-native/internal.js','canonical-extra/view.tsx']) {
      assert.deepEqual(resolve(context(origin),moduleName,'android'),{moduleName,platform:'android'});
    }
  }
  assert.deepEqual(resolve(context('canonical/review/screens.tsx'),'react','android'),{moduleName:'react',platform:'android'});
});
test('Metro preserves PNG dimensions for buffer and Android file asset paths', async t => {
  const {getAssetData, getAssetSize} = require('metro/private/Assets');
  const root = mkdtempSync(join(tmpdir(), 'lava-metro-assets-'));
  t.after(() => rmSync(root, {recursive: true, force: true}));
  const imagePath = join(root, 'pixel.png');
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=', 'base64');
  writeFileSync(imagePath, png);
  assert.deepEqual(getAssetSize('png', png, imagePath), {width: 1, height: 1});
  const asset = await getAssetData(imagePath, 'pixel.png', [], 'android', '/assets');
  assert.equal(asset.width, 1);
  assert.equal(asset.height, 1);
  assert.deepEqual(asset.files, [imagePath]);
});

test('Metro rejects a zero-length ICNS entry disguised as PNG without hanging', {timeout: 10000}, () => {
  const result = spawnSync(process.execPath, ['-e', `
    const assert = require('node:assert/strict');
    const {getAssetSize} = require(${JSON.stringify(require.resolve('metro/private/Assets'))});
    const content = Buffer.alloc(16);
    content.write('icns');
    content.writeUInt32BE(content.length, 4);
    content.write('icp4', 8);
    assert.throws(() => getAssetSize('png', content, 'malformed.png'));
  `], {encoding: 'utf8', timeout: 3000});
  assert.ifError(result.error);
  assert.equal(result.status, 0, result.stderr);
});
