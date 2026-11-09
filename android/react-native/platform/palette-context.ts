import {createContext} from 'react';
import type {PaletteScheme} from './palette';

// The root supplies its existing owner-confirmed scheme; this is no second preference store.
export const AndroidPaletteContext=createContext<PaletteScheme|undefined>(undefined);
