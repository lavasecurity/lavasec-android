// Binds the iOS #807 rule on Android: share codes/QR payloads carry no JS read-cache
// retention exception, while an explicitly authorized unprotected activity read may
// still cache. Fails if the share.query special-case is reintroduced in read-cache.ts.
import {mayCacheRead} from '../canonical/app/read-cache';
import type {AppSnapshot} from '../canonical/app/contract';

const authorized = {
  security: {unavailable: false},
  session: {protectedActions: {'View Activities': false}},
} as unknown as AppSnapshot;

test('share.query is never JS-cached even for an authorized snapshot', () => {
  expect(mayCacheRead({type: 'share.query', id: 'f1'} as never, authorized)).toBe(false);
});

test('an authorized unprotected activity read may still cache', () => {
  expect(mayCacheRead({type: 'activity.query'} as never, authorized)).toBe(true);
});

test('a protected activity read is not cached', () => {
  const protectedSnapshot = {
    security: {unavailable: false},
    session: {protectedActions: {'View Activities': true}},
  } as unknown as AppSnapshot;
  expect(mayCacheRead({type: 'activity.query'} as never, protectedSnapshot)).toBe(false);
});

test('activity export capabilities never enter the JS query cache',()=>{
  expect(mayCacheRead({type:'activity.card.query',start:1,end:2,hourly:false,chart:'total',period:'today'},authorized)).toBe(false);
});
