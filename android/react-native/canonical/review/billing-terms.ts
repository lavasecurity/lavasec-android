export function billingTerms(platform:string,yearlyPaidMonthly=false) {
  if(platform==='android')return {
    title:'Google Play Terms',
    url:'https://play.google.com/intl/en_us/about/play-terms/',
    disclosure:'Monthly and yearly plans auto-renew. Payment is charged through Google Play at purchase and at the start of each billing period unless you cancel. Manage or cancel your subscription in Google Play. After cancellation, access continues until the end of the paid period.',
  };
  return {
    title:'Terms of Use',
    url:'https://www.apple.com/legal/internet-services/itunes/dev/stdeula/',
    disclosure:yearlyPaidMonthly
      ? 'Monthly and yearly plans auto-renew. Yearly paid monthly is billed monthly on a 12-month commitment; after that, cancelling affects the next renewal per App Store terms. Payment is charged to your Apple Account at purchase and renews unless turned off at least 24 hours before the period ends. Manage or cancel in Apple Account settings.'
      : 'Monthly and yearly plans auto-renew. Payment is charged to your Apple Account at purchase and renews unless turned off at least 24 hours before the period ends. Manage or cancel in Apple Account settings.',
  };
}
