package com.lavasec.app.reactnative.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lavasec.app.CredentialDnsLeakCanaryBinding
import com.lavasec.app.data.CredentialDnsLeakCanaryOwner
import com.lavasec.app.vpn.qa.DnsLeakCanarySnapshot
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DeviceQaCanaryAdmissionTest {
    @Test fun `same process owner in replacement graph refuses retired visit caller and binding before final write`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("canary-admission-${System.nanoTime()}", Context.MODE_PRIVATE)
        val owner = CredentialDnsLeakCanaryOwner(prefs, true) { "a01b02c03d" }
        val captured = CredentialDnsLeakCanaryBinding(7, owner)
        var current: CredentialDnsLeakCanaryBinding? = captured
        val caller = Job()
        fun apply(action: DeviceQaAction, expected: Long = 7, visit: Boolean = true) =
            performDeviceQaCanaryMutation(action, captured, expected, { current }) { caller.isActive && visit }
        val arm = DeviceQaAction.ArmCanary("192.0.2.53")
        assertEquals(DeviceQaOutcome.REFUSED, apply(arm, expected = 8).outcome)
        assertEquals(DeviceQaOutcome.REFUSED, apply(arm, visit = false).outcome)
        current = CredentialDnsLeakCanaryBinding(8, owner)
        assertEquals(DeviceQaOutcome.REFUSED, apply(arm).outcome)
        current = CredentialDnsLeakCanaryBinding(7, owner) // equal content still is a different attachment
        assertEquals(DeviceQaOutcome.REFUSED, apply(arm).outcome)
        current = captured
        assertEquals(DeviceQaOutcome.APPLIED, apply(arm).outcome)
        val saved = owner.snapshot.value
        current = null
        assertEquals(DeviceQaOutcome.REFUSED, apply(DeviceQaAction.DisarmCanary).outcome)
        assertEquals(saved, owner.snapshot.value)
        current = captured; caller.cancel()
        assertEquals(DeviceQaOutcome.REFUSED, apply(DeviceQaAction.DisarmCanary).outcome)
        assertEquals(saved, owner.snapshot.value)
        assertEquals(saved.arm, CredentialDnsLeakCanaryOwner(prefs, true).snapshot.value.arm)
        assertNull(saved.lastReceipt)
    }

    @Test fun `numeric validation refusal and release absence use finite copy without fabricated send evidence`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("canary-copy-${System.nanoTime()}", Context.MODE_PRIVATE)
        val owner = CredentialDnsLeakCanaryOwner(prefs, true) { "a01b02c03d" }
        val binding = CredentialDnsLeakCanaryBinding(4, owner)
        val invalid = performDeviceQaCanaryMutation(DeviceQaAction.ArmCanary("resolver.example"), binding, 4, { binding }) { true }
        assertEquals(DeviceQaResultMessage.INVALID_ADDRESS, invalid.message())
        assertEquals(DnsLeakCanarySnapshot(), owner.snapshot.value); assertTrue(prefs.all.isEmpty())
        val release = CredentialDnsLeakCanaryBinding(4, CredentialDnsLeakCanaryOwner(prefs, false))
        assertEquals(DeviceQaResultMessage.CANARY_UNAVAILABLE,
            performDeviceQaCanaryMutation(DeviceQaAction.ArmCanary("192.0.2.53"), release, 4, { release }) { true }.message())
        assertTrue(prefs.all.isEmpty())
        assertEquals(DeviceQaOutcome.APPLIED,
            performDeviceQaCanaryMutation(DeviceQaAction.ArmCanary("2001:db8::53"), binding, 4, { binding }) { true }.outcome)
        assertNull(owner.snapshot.value.lastReceipt)
        assertEquals(DeviceQaOutcome.APPLIED,
            performDeviceQaCanaryMutation(DeviceQaAction.DisarmCanary, binding, 4, { binding }) { true }.outcome)
        assertNull(owner.snapshot.value.arm); assertNull(owner.snapshot.value.lastReceipt)
    }
}
