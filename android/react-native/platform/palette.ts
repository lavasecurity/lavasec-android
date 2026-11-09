import {StyleSheet} from 'react-native';
import {lavaTokens} from '../canonical/src/generated/tokens';

export type PaletteScheme = 'light' | 'dark';
type Description = {light:readonly number[];dark:readonly number[]}|{alias:string}|{system:string};
const descriptions:Readonly<Record<string,Description>>=lavaTokens.colors;
const resourceNames=new Map(Object.keys(descriptions).map(name=>[`@color/lava_rn_${name.toLowerCase()}`,name]));

function rgb(name:string,scheme:PaletteScheme):string|undefined {
  const description=descriptions[name];
  if(!description||'system' in description)return undefined;
  if('alias' in description)return rgb(description.alias,scheme);
  const [r,g,b,a]=description[scheme];
  const channels=[r!,g!,b!].map(value=>Math.round(value*255)).join(',');
  return a===undefined?`rgb(${channels})`:`rgba(${channels},${a})`;
}

/** Resolve only Lava's single-resource palette. Other native colors retain RN semantics. */
export function paletteColor(value:unknown,scheme:PaletteScheme):unknown {
  if(!value||typeof value!=='object'||!('resource_paths' in value))return value;
  const paths=value.resource_paths;
  if(!Array.isArray(paths)||paths.length!==1)return value;
  const name=resourceNames.get(paths[0]);
  return name===undefined?value:rgb(name,scheme)??value;
}

/** Flatten in RN order, retaining animated values and unrelated style fields verbatim. */
export function paletteStyle(value:unknown,scheme:PaletteScheme):unknown {
  if(!value)return value;
  const flat=StyleSheet.flatten(value as Parameters<typeof StyleSheet.flatten>[0]);
  if(!flat||typeof flat!=='object')return value;
  let changed=false;
  const next=Object.fromEntries(Object.entries(flat).map(([key,before])=>{
    const after=paletteColor(before,scheme);
    changed ||= before!==after;
    return [key,after];
  }));
  return changed?next:value;
}

export function paletteProps(props:Readonly<Record<string,unknown>>,scheme:PaletteScheme):Record<string,unknown> {
  const next:Record<string,unknown>={...props};
  for(const [key,value] of Object.entries(props)) {
    if(key==='style'&&typeof value==='function')next[key]=(state:unknown)=>paletteStyle(value(state),scheme);
    else if(key==='style'||key.endsWith('Style')&&typeof value==='object')next[key]=paletteStyle(value,scheme);
    else if(key==='colors'&&Array.isArray(value))next[key]=value.map(color=>paletteColor(color,scheme));
    else if(key==='trackColor'&&value&&typeof value==='object')next[key]=Object.fromEntries(Object.entries(value).map(([state,color])=>[state,paletteColor(color,scheme)]));
    else next[key]=paletteColor(value,scheme);
  }
  return next;
}
