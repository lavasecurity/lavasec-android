// Android supplies ui.android.ts using the same semantic component contract.
// A missing platform mapping must fail visibly instead of importing iOS native specs.
function unavailable():never {throw new Error('Flow UI requires this platform’s component adapter.');}
export const Copy:typeof import('./ui.android').Copy=unavailable;
export const Section:typeof import('./ui.android').Section=unavailable;
export const Choice:typeof import('./ui.android').Choice=unavailable;
export const Symbol:typeof import('./ui.android').Symbol=unavailable;
export const RowAccessory:typeof import('./ui.android').RowAccessory=unavailable;
export const Group:typeof import('./ui.android').Group=unavailable;
export const Info:typeof import('./ui.android').Info=unavailable;
export const ListRow:typeof import('./ui.android').ListRow=unavailable;
export const Panel:typeof import('./ui.android').Panel=unavailable;
export const Search:typeof import('./ui.android').Search=unavailable;
export const Toggle:typeof import('./ui.android').Toggle=unavailable;
export const FlowSheet:typeof import('./ui.android').FlowSheet=unavailable;
export const FormDivider:typeof import('./ui.android').FormDivider=unavailable;
export const FormField:typeof import('./ui.android').FormField=unavailable;
export const FormPanel:typeof import('./ui.android').FormPanel=unavailable;
export const FormParagraph:typeof import('./ui.android').FormParagraph=unavailable;
export const useCrossFadePreference:typeof import('./ui.android').useCrossFadePreference=unavailable;
export const SystemButton:typeof import('./ui.android').SystemButton=unavailable;
export const BufferedInput:typeof import('./ui.android').BufferedInput=unavailable;
export const InlineSymbolText:typeof import('./ui.android').InlineSymbolText=unavailable;
