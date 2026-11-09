# Android binding surface

`adapter.ts` implements `LavaUIAdapter` with the existing Android native leaves.
`src/index.android.ts` installs the binding without a runtime fallback registry.
Choice and toggle controls use shared request/acknowledgement behavior. Native
Android controls own measurement and accessibility. Inline text and Canvas
symbols use the same packaged, licensed weight-350 Material Symbols subset.

`src/flows/ui.android.ts` and `info-ui.android.ts` bind the shared foreground
composition to Android controls, colors, text scaling and window/IME ownership.
The live import route uses these bindings and the native review/publication owner.
Other shared flow modules require their explicit native owner adapters before
routing; importing their source alone does not establish product support.

See `docs/android-ios-2.2.0-receipt.md` for source identities, live adopters,
validation and outstanding platform evidence.
