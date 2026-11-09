import {PlatformColor, type ColorValue} from 'react-native';
import {lavaTokens} from './generated/tokens';
import type {LavaColorScheme} from './appearance';
type Description = {light: readonly number[]; dark: readonly number[]} | {system:string} | {alias:string};
const descriptions: Readonly<Record<string, Description>> = lavaTokens.colors;
export const colorForScheme = (name:keyof typeof lavaTokens.colors, scheme:LavaColorScheme):ColorValue => {
  const value=descriptions[name];
  if (!value) throw new Error('Unknown Lava color');
  if ('alias' in value) return colorForScheme(value.alias as keyof typeof lavaTokens.colors,scheme);
  if ('system' in value) return PlatformColor('?attr/textColorPrimary');
  return `rgb(${value[scheme].map(channel=>Math.round(channel*255)).join(',')})`;
};
// Android resolves these resources against the current configuration, including night mode.
export const colors=Object.fromEntries(Object.keys(descriptions).map(name=>[name,PlatformColor(`@color/lava_rn_${name.toLowerCase()}`)])) as Record<keyof typeof lavaTokens.colors,ColorValue>;
