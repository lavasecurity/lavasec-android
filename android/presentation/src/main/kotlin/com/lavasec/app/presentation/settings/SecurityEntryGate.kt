package com.lavasec.app.presentation.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.lavasec.app.presentation.ProductDestination
import com.lavasec.app.presentation.ProductNavigation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class SecurityEntryIdentity(
    val navigation: ProductNavigation,
    val externalSequence: Long,
    val graphGeneration: Long?,
    val foregroundEpoch: Long,
)

enum class SecurityEntryAccess { Unknown, Readable, Updating, Recovery }

/**
 * Canonical navigation.authorize(credentials) admits a page, not each render.
 * Choice changes and passcode setup must not eject an already admitted page.
 * Entry/lifecycle/owner changes discard admission; unreadable storage exposes only
 * the existing recovery UI, never a fabricated successful authentication.
 */
@Composable
fun SecurityEntryGate(
    identity: SecurityEntryIdentity,
    foreground: Boolean,
    access: SecurityEntryAccess,
    authorize: suspend () -> Boolean,
    onCancelled: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (identity.navigation.path.lastOrNull() != ProductDestination.Security) {
        content()
        return
    }
    // Conservative credential publication masks the page during its write. Only a
    // lasting classified failure retires admission after the write finishes.
    val admissionAccess = if (access == SecurityEntryAccess.Updating) SecurityEntryAccess.Readable else access
    var admitted by remember(identity, foreground, admissionAccess) { mutableStateOf(false) }
    LaunchedEffect(identity, foreground, access) {
        if (foreground && access == SecurityEntryAccess.Readable && !admitted) {
            val accepted = authorize()
            // A presenter that finishes after cancellation cannot reveal another entry.
            currentCoroutineContext().ensureActive()
            if (accepted) admitted = true else onCancelled()
        }
    }
    if (foreground && ((admitted && access == SecurityEntryAccess.Readable) ||
            access == SecurityEntryAccess.Recovery)) {
        content()
    } else {
        BackHandler(onBack = onCancelled)
        Box(Modifier.fillMaxSize().testTag("security_entry_pending"), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}
