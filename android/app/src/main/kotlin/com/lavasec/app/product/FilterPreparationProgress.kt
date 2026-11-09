package com.lavasec.app.product

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Job

/** Native operation context, propagated through IO work without a global progress listener. */
internal class FilterPreparationProgress(
    val isCurrent: () -> Boolean,
    private val report: (Phase) -> Unit,
    val bindWorker: (Job) -> Unit = {},
    val publicationGuard: com.lavasec.app.platform.NativeFilterPublicationGuard? = null,
) : AbstractCoroutineContextElement(Key) {
    enum class Phase(val progress: Double, val title: String) {
        DOWNLOADING(0.0, "Downloading from the sources"),
        COMPILING(0.25, "Building the filter"),
        SAVING(0.5, "Saving the filter"),
        VERIFIED(0.75, "Saving the filter"),
    }

    fun advance(phase: Phase) = report(phase)

    companion object Key : CoroutineContext.Key<FilterPreparationProgress>
}
