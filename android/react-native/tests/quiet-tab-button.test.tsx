import React from 'react';
import {View} from 'react-native';
import {render,fireEvent} from '@testing-library/react-native';
import {QuietTabButton} from '../canonical/review/QuietTabButton.android';

test('glyph-only navigation retains the current navigation library ARIA name, role and selected state',()=>{
  const press=jest.fn();
  const screen=render(<QuietTabButton role="tab" aria-label="Einstellungen" aria-selected={false} onPress={press}>
    <View accessible={false}/>
  </QuietTabButton>);
  fireEvent.press(screen.getByRole('tab',{name:'Einstellungen',selected:false}));
  expect(press).toHaveBeenCalledTimes(1);
  screen.rerender(<QuietTabButton role="tab" aria-label="Einstellungen" aria-selected onPress={press}>
    <View accessible={false}/>
  </QuietTabButton>);
  expect(screen.getByRole('tab',{name:'Einstellungen',selected:true})).toBeTruthy();
});

test('legacy accessibility props and disabled navigation remain supported',()=>{
  const press=jest.fn();
  const screen=render(<QuietTabButton accessibilityLabel="Guard" accessibilityState={{selected:true}} disabled onPress={press}>
    <View accessible={false}/>
  </QuietTabButton>);
  const tab=screen.getByRole('tab',{name:'Guard',selected:true,disabled:true});
  fireEvent.press(tab);
  expect(press).not.toHaveBeenCalled();
});
