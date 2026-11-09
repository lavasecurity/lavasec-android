/** Build-owned capabilities. Missing facts retain the established Play presentation. */
export type AndroidEdition = {
  id:'play'|'foss';
  accounts:boolean;
  cloudBackup:boolean;
  purchases:boolean;
  localFeaturesUnlocked:boolean;
  portableBackup:boolean;
  nativeSupport:boolean;
};

export function androidEditionRouteAllowed(route:string, edition?:AndroidEdition):boolean {
  return route!=='Upgrade'||edition?.purchases!==false;
}

/** Reject incomplete or contradictory native capability data before it can choose a screen. */
export function validateAndroidEdition(value:unknown):asserts value is AndroidEdition {
  if(typeof value!=='object'||value===null||Array.isArray(value))throw new TypeError('Invalid Android edition');
  const edition=value as Record<string,unknown>;
  if(edition.id!=='play'&&edition.id!=='foss')throw new TypeError('Invalid Android edition');
  const foss=edition.id==='foss';
  const expected={accounts:!foss,cloudBackup:!foss,purchases:!foss,localFeaturesUnlocked:foss,portableBackup:true,nativeSupport:!foss};
  if(Object.keys(edition).some(key=>key!=='id'&&!(key in expected))||
     Object.entries(expected).some(([key,enabled])=>edition[key]!==enabled))throw new TypeError('Invalid Android edition capabilities');
}
