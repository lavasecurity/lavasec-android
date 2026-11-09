package com.lavasec.app.backup

import android.app.Activity

/** Build-time refusal: FOSS contains no Credential Manager or Google PRF ceremony adapter. */
internal class AndroidBackupPasskeyCeremony(
    activity: Activity,
    relyingParty: BackupPasskeyRelyingParty,
    currentHost: () -> Activity? = { activity },
) : BackupPasskeyCeremony by UnavailableBackupPasskeyCeremony
