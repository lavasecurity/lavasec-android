import type {LavaUIAdapter} from '../../contracts';
import {LavaText,LavaCard,LavaToggleRow,LavaToggleControl,LavaActionButton,LavaIconButton,LavaIconButtonGroup,LavaSelectionAccessory} from '../../../components.android';
import {LavaChoice} from '../../../choice.android';

/** Static platform binding: native Android leaves with shared request/acknowledgement. */
export const androidUIAdapter={
  Text:LavaText,Card:LavaCard,ToggleRow:LavaToggleRow,ToggleControl:LavaToggleControl,
  ActionButton:LavaActionButton,IconButton:LavaIconButton,IconButtonGroup:LavaIconButtonGroup,
  SelectionAccessory:LavaSelectionAccessory,Choice:LavaChoice,
} satisfies LavaUIAdapter;
