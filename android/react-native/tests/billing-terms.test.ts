import {billingTerms} from '../canonical/review/billing-terms';
test('Android paywall identifies Google Play billing and terms without Apple restrictions',()=>{
  const terms=billingTerms('android',true);
  expect(terms.disclosure).toContain('Google Play');
  expect(terms.disclosure).toContain('cancel');
  expect(JSON.stringify(terms)).not.toMatch(/Apple|App Store|itunes|12-month|24 hours/);
  expect(terms.url).toBe('https://play.google.com/intl/en_us/about/play-terms/');
});
test('iOS retains its existing billing agreement',()=>{
  expect(billingTerms('ios').disclosure).toContain('Apple Account');
  expect(billingTerms('ios',true).disclosure).toContain('12-month commitment');
});

test('every supported locale includes the Android billing disclosure and terms label',()=>{
  const {translations}=require('../canonical/app/translations') as typeof import('../canonical/app/translations');
  const terms=billingTerms('android');
  for(const [locale,table] of Object.entries(translations)) {
    expect(table[terms.title]).toBeTruthy();
    expect(table[terms.disclosure]).toBeTruthy();
    if(locale!=='en')expect(table[terms.disclosure]).not.toBe(terms.disclosure);
  }
});
