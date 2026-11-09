const {createHash} = require('node:crypto');
const {readFileSync} = require('node:fs');
const path = require('node:path');

test('packaged runtime notices contain complete license text for the locked presentation', () => {
  const digest = value => createHash('sha256').update(value).digest('hex');
  const bundle = JSON.parse(readFileSync(path.resolve(__dirname,
    '../../app/src/main/assets/licenses/react-native-notices.json'), 'utf8'));
  expect(bundle.lockSha256).toBe(digest(readFileSync(path.resolve(__dirname, '../package-lock.json'))));
  for (const name of ['react', 'react-native', 'Hermes', 'react-native-svg', 'react-native-safe-area-context',
    'react-native-screens', '@react-navigation/native', '@react-navigation/native-stack', '@react-navigation/bottom-tabs']) {
    const notice = bundle.notices.find(value => value.name === name);
    expect(notice).toBeDefined();
    expect(notice.text).toMatch(/Permission is hereby granted/);
    expect(notice.text).toMatch(/Copyright/);
    expect(notice.sha256).toBe(digest(notice.text));
  }
});
