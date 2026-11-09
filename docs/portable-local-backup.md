# Android portable local backups

Both Android editions expose **Export encrypted local backup** and **Import encrypted
local backup** in Settings. The Android document picker lets the user choose a
file or destination. Passwords and recovery phrases are collected and displayed
only in native secure dialogs, never in React Native props, commands or snapshots.
Portable secret and review dialogs retire and clear their fields when the host pauses
or its native security authority changes. Returning from the document picker requires
fresh native authorization before another secret or configuration review is shown.
No account, backend, Google provider, original application ID or original device
key is required to export, decrypt, review or retain a portable file. Applying its
configuration uses the destination edition’s normal source acquisition and admission
authorities. It may require downloads and remains subject to that edition’s feature
and size limits; decrypting successfully does not bypass Play quotas.

An export includes the filter library and active filter, user allow/block rules,
selected source identifiers and custom source URLs (not downloaded source-list bytes),
ordered DNS choices, retention
preferences and unlocked Lava Guards. Endpoint URLs may contain network credentials;
they remain inside the authenticated encrypted payload. It excludes account/session
and refresh tokens, purchases and entitlement state, device identities, private
keystore/VPN keys, diagnostic events and browsing/activity history. Other device
settings and VPN key material are not migrated. Import preserves the destination
device's current protection on/off intent.

Choose a password or a generated recovery phrase when exporting. A password must
meet the existing backup password policy and be confirmed. A generated phrase
must be saved and typed back before proceeding. Each export is independent; the
cloud backup's existing password, recovery phrase or account state is not reused.
Losing the selected export secret makes that file unrecoverable.

Separate Play and FOSS packages do not share app storage or purchases. To migrate,
export in the source edition and import that file in the destination edition.
Review the before/after changes and acknowledge changed DNS resolution before
applying. Installing FOSS does not cancel a Google Play subscription. This file
format does not promise iOS file interoperability.

## Offline retention and later activation

After decrypting and validating the complete payload, the native before/after review
provides **Restore now**, **Save for later** and **Cancel**. Saving is available
without a source download or commercial entitlement. It retains the exact original
authenticated encrypted envelope in credential-protected `noBackupFilesDir`; it does
not retain a password, recovery phrase, plaintext configuration or document URI.
No library, confirmed artifact, retention preference or achievement ledger is
changed by saving. No downloaded source data is added to the portable schema.

One pending file is retained. Replacing it requires explicit native confirmation
bound to the pending record that was reviewed; a newer concurrent record cannot
be overwritten by stale consent. The file is written atomically with the same
262,144-byte bound as an ordinary import. The pending status means a file is present;
it is validated on native capture and becomes unreadable if that validation fails.
A structurally valid file must still pass authenticated decryption on resume.

Settings exposes the pending file with **Resume pending import** and **Discard
pending import**. Resume requests the export secret again, decrypts the full file,
prepares a fresh review against current authorities and uses the normal restore
transaction. A failed, cancelled or quota-refused restore keeps the pending file.
Uncached selected sources may require network access before activation can commit.
The pending file is deleted only after durable successful application, and only if
it is still the exact retained record. Explicit discard also requires native
confirmation and removes only that exact record. Uninstalling or clearing app data
removes the pending copy, so the user should retain the exported original.

The complete profiles, domain rules and source references remain in the encrypted
file while activation is deferred. Ordinary destination profile and domain limits
continue to apply when activation is attempted; the import never silently trims
configuration or selects a replacement profile to manufacture success. Existing
profile visibility and frozen-profile policies continue to govern retained profiles.

## Format version 1

The uncompressed UTF-8 JSON document has exactly three top-level keys:

- `format`: `lavasec-android-portable-backup`
- `formatVersion`: integer `1`
- `envelope`: the existing version 1 AES-256-GCM backup envelope

The envelope contains exactly `schemaVersion`, `envelopeVersion`, `cipher`,
`payloadCiphertext`, `ciphertextByteSize`, `createdAtEpochSeconds` and `keySlots`.
Each slot contains exactly `kind`, `kdf`, `salt`, `iterations` and `wrappedKey`.
Only `password` and `recoveryPhrase` slots with PBKDF2-HMAC-SHA256 are admitted;
server-assisted, device/keychain and passkey slots are rejected. Exports currently
contain one selected slot. Imports accept up to two distinct portable slots.
Cryptographic operations use `BackupEnvelopeCrypto` without a new cipher or KDF.
The plaintext uses the existing `BackupPayloadJson` configuration schema and its
supported version 1–3 migrations.

The stream is capped at 262,144 bytes before JSON decode, and plaintext at 180,000
bytes. These conservative portable limits bound parser and mobile-memory work;
valid large libraries that fit normal local storage may exceed them. Export refuses
the whole oversized configuration with a dedicated size reason before opening a
save destination. It never truncates the library or changes active configuration
to fit. The JSON parser bounds nesting to 32 and rejects duplicate keys; the outer
file schema additionally rejects unexpected fields, invalid UTF-8, fractional
version numbers and unsupported format versions. PBKDF2 work is restricted to
210,000–1,000,000 iterations for the selected slot. There is no decompression.
Source URLs, rules, DNS targets and configuration are validated through the existing
payload mapper before export and again while preparing an import.

Authentication and payload validation precede any settings write. Import prepares
an immutable review through `BackupRestoreOwner`; applying it rechecks the current
library, configuration generation, achievement ledger, host and security authority.
The same captured authority reaches the library coordinator's final write, and
compensation uses the prior generation and exact forward-state comparison without
requiring a replacement foreground grant. Retention persistence rechecks the same
native witness inside the DataStore transform after any mutex/queue wait; a refused
write cannot authorize deletion of logs or progress. A write committed immediately before
authority retirement remains reported committed so it can be compensated honestly.
The existing `ProductionBackupRestorePublisher` commits through the live library
and logging authorities, with rollback on interrupted publication. Portable restore
does not adopt a cloud envelope or create device key custody. On Play, publication
and compensation hold the same custody exclusion as automatic/manual cloud uploads,
so an already scheduled upload cannot capture an intermediate or rolled-back library.
Wrong secrets,
corruption, future schemas, stale reviews and cancelled flows do not apply settings.
A rollback failure has its own refusal reason and is never described as a successful
or unchanged restore.

A document provider may leave an incomplete encrypted file after an interrupted
export; the import length/parser/authentication checks reject it. Export reports
completion only after the provider's output stream closes successfully. The app
requests only the URI grant supplied by the user-selected document operation and
does not request broad storage access.

## Qualification

`PortableBackupFileTest` covers password and recovery round trips independent of
account/edition, wrong-secret and tamper refusal, malformed/oversized/deep/duplicate
JSON, strict schema versions and rejection of server/device/passkey or excessive
KDF slots. `NativePortableBackupPromptTest` covers native secret-field bounds,
password confirmation/policy, screenshot prevention, autofill/state exclusion,
dismissal, pause, foreground authorization revocation and cancellation cleanup.
The restore review scrolls and preserves its summary while requesting DNS acknowledgement.
Import-password validation asks only for the existing secret, independently of the
new export password policy. `NativePortableBackupAuthorizationTest` checks that a
dismissed security prompt returns cancelled for each portable action without file
or restore-owner access, while a denied prompt remains an unauthorized refusal.
The Save for later choice is independent of the acknowledgment required for applying
DNS changes. `AndroidPendingPortableBackupStoreTest` covers exact ciphertext retention,
shared status across recreated stores, atomic interrupted replacement, stale replacement
and deletion witnesses, explicit uncertainty after a diagnostic readback failure, refusal of readable
post-write mismatches,
unreadable/oversized entries, wrong-secret and tamper retention,
stream cancellation, and zero-progress read refusal on APIs 28 and 36.
Filter-owner tests cover final-write authorization retirement and committed-result
reporting for compensation. Existing restore-owner/publisher tests cover
stale reviews and compensated publication.

Device release qualification must still exercise Play-to-FOSS, FOSS-to-Play and
FOSS-to-FOSS imports with both apps installed, offline save followed by later activation,
uncached-source download failures, Play limit refusals, pending resume/discard, document-provider
errors and interruptions, app background/recreation and security reauthorization.
These runtime checks belong to the release evidence; unit checks do not establish
device or provider behavior.
