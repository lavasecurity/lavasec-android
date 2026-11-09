# Material Symbols Rounded

Android renders an actual static weight **350**, optical size 24, grade 0.
Unfilled and filled outlines are separate fonts; application text weights never
synthesize icon strokes. The subset contains 108 glyphs (155 semantic aliases)
and occupies about 48 KiB. Lava's authored Guardian and brand artwork stay separate.

Source: Google Material Design Icons, commit
`49d4db35df873165d6bd6ba09b063c7dafbac2f4`, Apache 2.0.
See `MaterialSymbols-LICENSE.txt` and the packaged Licenses screen.

`material-symbols.json` is the shared mapping for native Canvas and RN inline
text. `scripts/generate-material-symbols.py` regenerates both renderers' tables
and packaged fonts with `fonttools==4.60.1`. It subsets before instancing to bound
memory. `material-symbols-receipt.json` records source and output hashes. Runtime
font loading is entirely local; there is no font request or system-font fallback.

Reference: https://developers.google.com/fonts/docs/material_symbols
