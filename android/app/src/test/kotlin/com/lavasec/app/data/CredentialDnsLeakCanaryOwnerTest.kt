package com.lavasec.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.lavasec.app.vpn.qa.DnsLeakCanary
import com.lavasec.app.vpn.qa.DnsLeakCanaryArm
import com.lavasec.app.vpn.qa.DnsLeakCanaryMutation
import com.lavasec.app.vpn.qa.DnsLeakCanaryReceipt
import com.lavasec.app.vpn.qa.DnsLeakCanarySend
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CredentialDnsLeakCanaryOwnerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun preferences() = context.getSharedPreferences("leak-canary-test-${System.nanoTime()}", Context.MODE_PRIVATE)
    private fun owner(prefs: SharedPreferences, enabled: Boolean = true) =
        CredentialDnsLeakCanaryOwner(prefs, enabled) { "a01b02c03d" }

    @Test fun `arm is atomic durable and stays armed after an actual syscall receipt`() {
        val prefs = preferences()
        val subject = owner(prefs)
        assertTrue(subject.arm(" 192.168.1.53 ") { true } is DnsLeakCanaryMutation.Applied)
        val arm = requireNotNull(subject.captureArm())
        assertEquals(arm, owner(prefs).snapshot.value.arm)
        val accepted = DnsLeakCanarySend.Accepted(receipt(arm))
        assertEquals(accepted, subject.sendIfCurrent(arm, { true }) { accepted })
        assertEquals(accepted.receipt, subject.snapshot.value.lastReceipt)
        assertEquals(arm, owner(prefs).captureArm()) // Sending never disarms the next whole launch.
        assertNull(owner(prefs).snapshot.value.lastReceipt) // No fabricated persisted pass/result.
        assertTrue(subject.disarm { true } is DnsLeakCanaryMutation.Applied)
        assertNull(owner(prefs).captureArm())
        var sends = 0
        subject.sendIfCurrent(arm, { true }) { sends++; accepted }
        assertEquals(0, sends)
    }

    @Test fun `release stale malformed and partial retained arms never produce a send`() {
        val prefs = preferences()
        val release = owner(prefs, enabled = false)
        assertEquals(DnsLeakCanaryMutation.Unavailable, release.arm("192.168.1.53") { true })
        assertEquals(DnsLeakCanaryMutation.Unavailable, release.disarm { true })
        assertNull(release.captureArm())
        val subject = owner(prefs)
        assertEquals(DnsLeakCanaryMutation.Stale, subject.arm("192.168.1.53") { false })
        assertEquals(DnsLeakCanaryMutation.InvalidAddress, subject.arm("dns.example") { true })
        assertTrue(prefs.all.isEmpty())
        prefs.edit().putString(CredentialDnsLeakCanaryOwner.KEY_NONCE, "a01b02c03d").commit()
        assertNull(owner(prefs).captureArm())
        prefs.edit().putString(CredentialDnsLeakCanaryOwner.KEY_RESOLVER, "dns.example").commit()
        assertNull(owner(prefs).captureArm())
    }

    @Test fun `failed commit cannot send from the SharedPreferences unconfirmed memory map and explicit retry recovers`() {
        val real = preferences()
        var fail = true
        val failing = object : SharedPreferences by real {
            override fun edit(): SharedPreferences.Editor {
                val delegate = real.edit()
                return object : SharedPreferences.Editor by delegate {
                    // Preserve the failure wrapper through Android's fluent Editor API.
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        delegate.putString(key, value)
                        return this
                    }
                    override fun remove(key: String?): SharedPreferences.Editor {
                        delegate.remove(key)
                        return this
                    }
                    override fun commit(): Boolean {
                        val success = delegate.commit() // Model even the dangerous memory-visible case.
                        return success && !fail
                    }
                }
            }
        }
        val subject = owner(failing)
        assertEquals(DnsLeakCanaryMutation.Unavailable, subject.arm("192.168.1.53") { true })
        assertFalse(subject.snapshot.value.available)
        assertNull(subject.captureArm())
        fail = false
        assertTrue(subject.arm("192.168.1.54") { true } is DnsLeakCanaryMutation.Applied)
        assertEquals("192.168.1.54", subject.captureArm()?.resolverIP)
        fail = true
        assertEquals(DnsLeakCanaryMutation.Unavailable, subject.disarm { true })
        assertNull(subject.captureArm())
    }

    @Test fun `winning disarm and rearm refuse old launch even with a still current runtime`() {
        val subject = owner(preferences())
        subject.arm("192.168.1.53") { true }
        val old = requireNotNull(subject.captureArm())
        subject.arm("192.168.1.54") { true }
        var sends = 0
        assertEquals(DnsLeakCanarySend.NoDatagramSent, subject.sendIfCurrent(old, { true }) {
            sends++; DnsLeakCanarySend.Accepted(receipt(old))
        })
        assertEquals(0, sends)
        val current = requireNotNull(subject.captureArm())
        subject.sendIfCurrent(current, { false }) { sends++; DnsLeakCanarySend.NoDatagramSent }
        assertEquals(0, sends)
        assertEquals(DnsLeakCanaryMutation.Stale, subject.disarm { false })
        assertEquals(current, subject.captureArm())
    }

    @Test fun `disarm serializes after a winning syscall and clears its actual receipt`() {
        val subject = owner(preferences())
        subject.arm("192.168.1.53") { true }
        val arm = requireNotNull(subject.captureArm())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val result = AtomicReference<DnsLeakCanaryMutation>()
        val sending = Thread {
            subject.sendIfCurrent(arm, { true }) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                DnsLeakCanarySend.Accepted(receipt(arm))
            }
        }
        sending.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val disarming = Thread { result.set(subject.disarm { true }); finished.countDown() }
        disarming.start()
        assertFalse("Disarm must wait for a winning final syscall", finished.await(100, TimeUnit.MILLISECONDS))
        release.countDown()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        sending.join(5_000); disarming.join(5_000)
        assertTrue(result.get() is DnsLeakCanaryMutation.Applied)
        assertNull(subject.snapshot.value.arm)
        assertNull(subject.snapshot.value.lastReceipt)
    }

    private fun receipt(arm: DnsLeakCanaryArm) = DnsLeakCanaryReceipt(
        arm, 7, 0, 101, DnsLeakCanary.query(arm.nonce).size, 12, "192.168.1.2", 40000,
    )
}
