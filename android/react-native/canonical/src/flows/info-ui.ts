// Android supplies info-ui.android.ts for the same portable FlowInfo composition.
function unavailable():never {throw new Error('Flow UI requires this platform’s component adapter.');}
export const Copy:typeof import('./info-ui.android').Copy=unavailable;
export const Panel:typeof import('./info-ui.android').Panel=unavailable;
export const InlineSymbolText:typeof import('./info-ui.android').InlineSymbolText=unavailable;
