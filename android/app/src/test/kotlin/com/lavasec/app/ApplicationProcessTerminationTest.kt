package com.lavasec.app

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ApplicationProcessTerminationTest {
    @Test fun `production process cleanup is idempotent and its inherited fixture waits for completion`() {
        val application = ApplicationProvider.getApplicationContext<DrainingLavasecTestApplication>()
        val completion = application.beginProcessTermination()
        assertSame(completion, application.beginProcessTermination())
        awaitProcessTerminationForTest(completion)
        assertTrue(completion.isCompleted)
        assertFalse(completion.isCancelled)
        assertSame(completion, application.beginProcessTermination())
        application.onTerminate()
    }
}
