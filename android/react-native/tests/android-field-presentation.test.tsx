import {fireEvent,render,screen} from '@testing-library/react-native';
import {BufferedInput} from '../canonical/review/BufferedInput';
import {AndroidPresentationContext,withAndroidPresentation,type AndroidPresentation} from '../canonical/review/presentation-owner.android';

let mockFocused=true;
const mockNavigation={isFocused:()=>mockFocused,addListener:()=>()=>{}};
jest.mock('@react-navigation/native',()=>({useIsFocused:()=>mockFocused,useRoute:()=>({key:'private-filter-visit'}),useNavigation:()=>mockNavigation}));
jest.mock('../canonical/review/ReviewContext',()=>({useReview:()=>({}),useOptionalReview:()=>undefined}));

const changed=jest.fn(),submitted=jest.fn();
const FieldRoute=withAndroidPresentation(()=> <BufferedInput testID="ordinary-field" value="retained draft" accessibilityLabel="Filter name" onChangeText={changed} onSubmitEditing={submitted}/>);
const privateRoute:AndroidPresentation={serial:1,routeKey:'private-filter-visit',surface:'filterEditing'};
beforeEach(()=>{mockFocused=true;changed.mockClear();submitted.mockClear();});

test('an independent public modal disables retained private fields before its native readiness frame',()=>{
  const view=render(<AndroidPresentationContext.Provider value={privateRoute}><FieldRoute/></AndroidPresentationContext.Provider>);
  expect(screen.getByTestId('ordinary-field').props.editable).toBe(true);
  fireEvent(screen.getByTestId('ordinary-field'),'change',{nativeEvent:{text:'before modal'}});
  expect(changed).toHaveBeenLastCalledWith('before modal');
  const publicModal:AndroidPresentation={serial:2,routeKey:'onboarding:visit',ownerID:'onboarding:visit',surface:'none'};
  // The nested private navigator remains focused underneath the independent
  // modal. Current presentation ownership still fences its retained field.
  view.rerender(<AndroidPresentationContext.Provider value={publicModal}><FieldRoute/></AndroidPresentationContext.Provider>);
  expect(screen.getByTestId('ordinary-field').props.editable).toBe(false);
  fireEvent(screen.getByTestId('ordinary-field'),'change',{nativeEvent:{text:'stale native event'}});
  fireEvent(screen.getByTestId('ordinary-field'),'submit',{nativeEvent:{text:'stale native event'}});
  expect(changed).toHaveBeenCalledTimes(1);expect(submitted).not.toHaveBeenCalled();
  view.rerender(<AndroidPresentationContext.Provider value={{...privateRoute,serial:3}}><FieldRoute/></AndroidPresentationContext.Provider>);
  expect(screen.getByTestId('ordinary-field').props.editable).toBe(true);
  expect(screen.getByTestId('ordinary-field').props.value).toBe('retained draft');
});

test('ordinary native fields also lose editing when their own route blurs',()=>{
  const view=render(<AndroidPresentationContext.Provider value={privateRoute}><FieldRoute/></AndroidPresentationContext.Provider>);
  mockFocused=false;view.rerender(<AndroidPresentationContext.Provider value={privateRoute}><FieldRoute/></AndroidPresentationContext.Provider>);
  expect(screen.getByTestId('ordinary-field').props.editable).toBe(false);
});


test('a measured current route keeps native fields read-only until its exact publication is accepted',()=>{
  const view=render(<AndroidPresentationContext.Provider value={{...privateRoute,accepted:false}}><FieldRoute/></AndroidPresentationContext.Provider>);
  expect(screen.getByTestId('ordinary-field').props.editable).toBe(false);
  fireEvent(screen.getByTestId('ordinary-field'),'change',{nativeEvent:{text:'before receipt'}});
  fireEvent(screen.getByTestId('ordinary-field'),'submit',{nativeEvent:{text:'before receipt'}});
  expect(changed).not.toHaveBeenCalled();expect(submitted).not.toHaveBeenCalled();
  view.rerender(<AndroidPresentationContext.Provider value={{...privateRoute,accepted:true}}><FieldRoute/></AndroidPresentationContext.Provider>);
  expect(screen.getByTestId('ordinary-field').props.editable).toBe(true);
  fireEvent(screen.getByTestId('ordinary-field'),'change',{nativeEvent:{text:'after receipt'}});
  expect(changed).toHaveBeenCalledWith('after receipt');
});
