const { getDefaultConfig, mergeConfig } = require('@react-native/metro-config');
const path=require('node:path');
const canonicalRoot=path.join(__dirname,'canonical')+path.sep;

/** @type {import('@react-native/metro-config').MetroConfig} */
const config = {resolver:{resolveRequest(context,moduleName,platform){
  // Scope the palette adapter to Android app callers. Dependencies and the adapter's
  // own imports resolve the real libraries, preserving native internals and refs.
  const adapters=new Map([['react-native','appearance-native.tsx'],['react-native-svg','appearance-svg.tsx']]);
  const adapter=platform==='android'&&context.originModulePath.startsWith(canonicalRoot)?adapters.get(moduleName):undefined;
  return context.resolveRequest(context,adapter?path.join(__dirname,'platform',adapter):moduleName,platform);
}}};

module.exports = mergeConfig(getDefaultConfig(__dirname), config);
