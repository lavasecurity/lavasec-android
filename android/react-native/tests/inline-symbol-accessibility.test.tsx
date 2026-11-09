import React from 'react';
import {render} from '@testing-library/react-native';
import {configurePresentation,localized} from '../canonical/app/presentation';
import {InlineSymbolText} from '../canonical/src/flows/InlineSymbolText.android';
import {materialSymbol} from '../canonical/android/material-symbols';

afterEach(()=>configurePresentation());

test.each(['en','fr','ja'])('inline symbols preserve the %s title without speaking the decorative glyph',locale=>{
  configurePresentation({locale,textScales:null});
  const title=localized('Enter a setup code');
  const screen=render(<InlineSymbolText title="Enter a setup code" symbol="doc.on.clipboard" tintColor="green" textColor="black" fontSize={17} fontWeight="semibold"/>);
  expect(screen.getByLabelText(title)).toBeTruthy();
  const glyph=materialSymbol('doc.on.clipboard');
  expect(glyph).toBeDefined();
  expect(screen.getByText(glyph!).props.accessible).toBe(false);
  expect(screen.getByLabelText(title).props.accessibilityLabel).not.toMatch(/[\uE000-\uF8FF]/);
});
