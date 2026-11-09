package com.lavasec.app

import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.State
import com.lavasec.app.presentation.ProductDestination
import com.lavasec.app.presentation.ProductNavigation
import com.lavasec.app.presentation.activity.ActivityQueryCallbacks
import com.lavasec.app.presentation.activity.DomainNoticeCallbacks
import com.lavasec.app.presentation.activity.DomainReviewCallbacks
import com.lavasec.app.presentation.activity.DomainRowActionCallbacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class MainActivityDeepLinkTest {
    @Test
    fun `recreation marker consumes only an explicit configuration change`() {
        val retainedMarker = ActivityRecreationMarker()

        assertFalse(retainedMarker.consumeConfigurationChange())
        retainedMarker.markConfigurationChange()
        assertTrue(retainedMarker.consumeConfigurationChange())
        assertFalse(retainedMarker.consumeConfigurationChange())
        assertFalse(ActivityRecreationMarker().consumeConfigurationChange())
    }

    @Test
    fun `manifest keeps MainActivity singleTop so warm links reach onNewIntent`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        val activityInfo = activity.packageManager.getActivityInfo(activity.componentName, 0)

        assertEquals(ActivityInfo.LAUNCH_SINGLE_TOP, activityInfo.launchMode)

        controller.close()
    }

    @Test
    fun `cold ACTION_VIEW delivers only a successfully parsed navigation`() {
        val supported = Robolectric.buildActivity(
            MainActivity::class.java,
            view("https://lavasecurity.app/app/settings"),
        ).setup()
        val invalid = Robolectric.buildActivity(
            MainActivity::class.java,
            view("https://lavasecurity.app/app/settings/extra"),
        ).setup()
        val wrongAction = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(Intent.ACTION_SEND, Uri.parse("${com.lavasec.app.edition.AppEdition.current.deepLinkScheme}://settings")),
        ).setup()

        assertEquals(ProductNavigation.settings, request(supported.get()).navigation)
        assertEquals(1L, request(supported.get()).sequence)
        assertEquals(ProductNavigation.guard, request(invalid.get()).navigation)
        assertEquals(0L, request(invalid.get()).sequence)
        assertEquals(ProductNavigation.guard, request(wrongAction.get()).navigation)
        assertEquals(0L, request(wrongAction.get()).sequence)

        supported.close()
        invalid.close()
        wrongAction.close()
    }

    @Test
    fun `valid exported entry stages an opaque native ticket and finishes the covered Compose host`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, view("${com.lavasec.app.edition.AppEdition.current.deepLinkScheme}://activity")).setup()
        val source = controller.get()
        val launched = checkNotNull(shadowOf(source).nextStartedActivity)
        assertEquals(com.lavasec.app.reactnative.LavaReactActivity::class.java.name, launched.component?.className)
        assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TOP, launched.flags)
        assertEquals(0, launched.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val targetInfo = source.packageManager.getActivityInfo(checkNotNull(launched.component), 0)
        assertEquals(ActivityInfo.LAUNCH_MULTIPLE, targetInfo.launchMode)
        assertNull("No external URI may enter React launch properties", launched.data)
        assertEquals(setOf(com.lavasec.app.reactnative.LavaReactActivity.DEEP_LINK_ENTRY_EXTRA), launched.extras?.keySet())
        val token = checkNotNull(launched.getStringExtra(com.lavasec.app.reactnative.LavaReactActivity.DEEP_LINK_ENTRY_EXTRA))
        assertEquals(token, java.util.UUID.fromString(token).toString())
        assertTrue(source.isFinishing)
        assertEquals(activityOverview(), request(source).navigation)
        controller.close()
    }

    @Test
    fun `notification entry crosses the edition validator before opening React`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val notification = com.lavasec.app.notification.NotificationActionIntents(context)
            .intent(com.lavasec.app.notification.NotificationAction.OPEN_SETTINGS)
        val controller = Robolectric.buildActivity(MainActivity::class.java, checkNotNull(notification)).setup()
        val source = controller.get()
        val launched = checkNotNull(shadowOf(source).nextStartedActivity)
        assertEquals(com.lavasec.app.reactnative.LavaReactActivity::class.java.name, launched.component?.className)
        assertNull(launched.data)
        assertTrue(launched.hasExtra(com.lavasec.app.reactnative.LavaReactActivity.DEEP_LINK_ENTRY_EXTRA))
        assertEquals(ProductNavigation.settings, request(source).navigation)
        assertTrue(source.isFinishing)
        controller.close()
    }

    @Test
    fun `invalid and non-view entries cannot create a native React destination ticket`() {
        listOf(view("${com.lavasec.app.edition.AppEdition.current.deepLinkScheme}://settings?credential=private"), view("https://lavasecurity.app/app/settings/extra"),
            Intent(Intent.ACTION_SEND, Uri.parse("${com.lavasec.app.edition.AppEdition.current.deepLinkScheme}://settings"))).forEach { intent ->
            val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
            assertNull(shadowOf(controller.get()).nextStartedActivity)
            assertEquals(0L, request(controller.get()).sequence)
            controller.close()
        }
    }

    @Test
    fun `cold Activity links open only the Activity overview`() {
        listOf(
            "${com.lavasec.app.edition.AppEdition.current.deepLinkScheme}://activity",
            "https://lavasecurity.app/app/activity",
        ).forEach { uri ->
            val controller = Robolectric.buildActivity(
                MainActivity::class.java,
                view(uri),
            ).setup()

            assertEquals(activityOverview(), request(controller.get()).navigation)
            assertEquals(1L, request(controller.get()).sequence)

            controller.close()
        }
    }

    @Test
    fun `warm ACTION_VIEW delivers every valid intent including identical repeats`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val link = view("${com.lavasec.app.edition.AppEdition.current.deepLinkScheme}://filters")

        controller.newIntent(link)
        assertEquals(
            ProductNavigation.guard.open(ProductDestination.Filters),
            request(activity).navigation,
        )
        assertEquals(1L, request(activity).sequence)

        controller.newIntent(link)
        assertEquals(
            ProductNavigation.guard.open(ProductDestination.Filters),
            request(activity).navigation,
        )
        assertEquals(2L, request(activity).sequence)

        controller.newIntent(view("${com.lavasec.app.edition.AppEdition.current.deepLinkScheme}://unsupported"))
        assertEquals(
            ProductNavigation.guard.open(ProductDestination.Filters),
            request(activity).navigation,
        )
        assertEquals(2L, request(activity).sequence)

        controller.close()
    }

    @Test
    fun `warm Activity links replace either grandchild with the overview`() {
        val cases = listOf(
            topDomains() to "${com.lavasec.app.edition.AppEdition.current.deepLinkScheme}://activity",
            topDomains() to "https://lavasecurity.app/app/activity",
            domainHistory() to "${com.lavasec.app.edition.AppEdition.current.deepLinkScheme}://activity",
            domainHistory() to "https://lavasecurity.app/app/activity",
        )

        cases.forEach { (initial, uri) ->
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            setNavigation(controller.get(), initial)

            controller.newIntent(view(uri))

            assertEquals(activityOverview(), request(controller.get()).navigation)
            assertEquals(1L, request(controller.get()).sequence)
            controller.close()
        }
    }

    @Test
    fun `invalid Activity grandchild links preserve the exact path and sequence`() {
        val invalidUris = listOf(
            "${com.lavasec.app.edition.AppEdition.current.deepLinkScheme}://top-domains",
            "${com.lavasec.app.edition.AppEdition.current.deepLinkScheme}://domain-history",
            "${com.lavasec.app.edition.AppEdition.current.deepLinkScheme}://activity/top-domains",
            "${com.lavasec.app.edition.AppEdition.current.deepLinkScheme}://activity/domain-history",
            "https://lavasecurity.app/app/top-domains",
            "https://lavasecurity.app/app/domain-history",
            "https://lavasecurity.app/app/activity/top-domains",
            "https://lavasecurity.app/app/activity/domain-history",
        )

        invalidUris.forEachIndexed { index, uri ->
            val expected = if (index % 2 == 0) topDomains() else domainHistory()
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            setNavigation(controller.get(), expected)

            controller.newIntent(view(uri))

            assertEquals(expected, request(controller.get()).navigation)
            assertEquals(0L, request(controller.get()).sequence)
            controller.close()
        }
    }

    @Test
    fun `configuration recreation restores user navigation instead of retained launch intent`() {
        val controller = Robolectric.buildActivity(
            MainActivity::class.java,
            view("${com.lavasec.app.edition.AppEdition.current.deepLinkScheme}://settings"),
        ).setup()
        setNavigation(
            controller.get(),
            ProductNavigation.guard.open(ProductDestination.Activity),
        )

        controller.recreate()

        assertEquals(
            ProductNavigation.guard.open(ProductDestination.Activity),
            request(controller.get()).navigation,
        )

        controller.close()
    }

    @Test
    fun `configuration recreation preserves each Activity grandchild without replaying launch link`() {
        listOf(topDomains(), domainHistory()).forEach { expected ->
            val controller = Robolectric.buildActivity(
                MainActivity::class.java,
                view("${com.lavasec.app.edition.AppEdition.current.deepLinkScheme}://settings"),
            ).setup()
            setNavigation(controller.get(), expected)

            controller.recreate()

            assertEquals(expected, request(controller.get()).navigation)
            assertEquals(0L, request(controller.get()).sequence)
            controller.close()
        }
    }

    @Test
    fun `both Activity grandchildren survive process restore exactly`() {
        listOf(topDomains(), domainHistory()).forEach { expected ->
            val recreated = Robolectric.buildActivity(MainActivity::class.java)
                .create(savedNavigationState(expected))
                .start()
                .resume()
                .visible()

            assertEquals(expected, request(recreated.get()).navigation)
            assertEquals(0L, request(recreated.get()).sequence)
            recreated.close()
        }
    }

    @Test
    fun `fresh Activity link after restore replaces either grandchild with overview`() {
        val cases = listOf(
            topDomains() to "${com.lavasec.app.edition.AppEdition.current.deepLinkScheme}://activity",
            domainHistory() to "https://lavasecurity.app/app/activity",
        )

        cases.forEach { (restored, uri) ->
            val recreated = Robolectric.buildActivity(
                MainActivity::class.java,
                view(uri),
            )
                .create(savedNavigationState(restored))
                .start()
                .resume()
                .visible()

            assertEquals(activityOverview(), request(recreated.get()).navigation)
            assertEquals(1L, request(recreated.get()).sequence)
            recreated.close()
        }
    }

    @Test
    fun `invalid grandchild link after restore preserves exact grandchild and sequence`() {
        val cases = listOf(
            topDomains() to "${com.lavasec.app.edition.AppEdition.current.deepLinkScheme}://activity/top-domains",
            domainHistory() to "https://lavasecurity.app/app/activity/domain-history",
        )

        cases.forEach { (expected, uri) ->
            val recreated = Robolectric.buildActivity(
                MainActivity::class.java,
                view(uri),
            )
                .create(savedNavigationState(expected))
                .start()
                .resume()
                .visible()

            assertEquals(expected, request(recreated.get()).navigation)
            assertEquals(0L, request(recreated.get()).sequence)
            recreated.close()
        }
    }

    @Test
    fun `process recreation applies a fresh ACTION_VIEW after restoring stale navigation`() {
        val savedState = savedNavigationState(
            ProductNavigation.guard.open(ProductDestination.Activity),
        )

        val recreated = Robolectric.buildActivity(
            MainActivity::class.java,
            view("${com.lavasec.app.edition.AppEdition.current.deepLinkScheme}://settings"),
        )
            .create(savedState)
            .start()
            .resume()
            .visible()

        assertEquals(ProductNavigation.settings, request(recreated.get()).navigation)
        assertEquals(1L, request(recreated.get()).sequence)

        recreated.close()
    }

    @Test
    fun `process recreation from history does not replay its original ACTION_VIEW`() {
        val restored = ProductNavigation.guard.open(ProductDestination.Activity)
        val historyIntent = view("${com.lavasec.app.edition.AppEdition.current.deepLinkScheme}://settings").addFlags(
            Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY,
        )

        val recreated = Robolectric.buildActivity(MainActivity::class.java, historyIntent)
            .create(savedNavigationState(restored))
            .start()
            .resume()
            .visible()

        assertEquals(restored, request(recreated.get()).navigation)
        assertEquals(0L, request(recreated.get()).sequence)

        recreated.close()
    }

    @Test
    fun `process recreation keeps restored navigation for invalid and non-view intents`() {
        val restored = ProductNavigation.guard.open(ProductDestination.Activity)
        val intents = listOf(
            view("${com.lavasec.app.edition.AppEdition.current.deepLinkScheme}://unsupported"),
            Intent(Intent.ACTION_SEND, Uri.parse("${com.lavasec.app.edition.AppEdition.current.deepLinkScheme}://settings")),
        )

        intents.forEach { launchIntent ->
            val recreated = Robolectric.buildActivity(MainActivity::class.java, launchIntent)
                .create(savedNavigationState(restored))
                .start()
                .resume()
                .visible()

            assertEquals(restored, request(recreated.get()).navigation)
            assertEquals(0L, request(recreated.get()).sequence)

            recreated.close()
        }
    }

    @Test
    fun `process recreation fails closed when saved navigation path is malformed`() {
        val launchIntent = view("${com.lavasec.app.edition.AppEdition.current.deepLinkScheme}://settings").addFlags(
            Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY,
        )
        listOf(
            arrayListOf("Guard", "Activity", "Filters"),
            arrayListOf("Guard", "Activity", "TopDomains", "DomainHistory"),
        ).forEach { malformed ->
            val savedState = Bundle().apply {
                putStringArrayList("product_navigation_path", malformed)
            }
            val recreated = Robolectric.buildActivity(MainActivity::class.java, launchIntent)
                .create(savedState)
                .start()
                .resume()
                .visible()

            assertEquals(ProductNavigation.guard, request(recreated.get()).navigation)
            assertEquals(0L, request(recreated.get()).sequence)
            recreated.close()
        }
    }

    @Test
    fun `activity owns no static protection or Activity snapshot placeholders`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()

        val callbackTypes = setOf(
            ActivityQueryCallbacks::class.java,
            DomainRowActionCallbacks::class.java,
            DomainReviewCallbacks::class.java,
            DomainNoticeCallbacks::class.java,
        )
        assertTrue(
            MainActivity::class.java.declaredFields.none { field -> field.type in callbackTypes },
        )
        assertTrue(
            MainActivity::class.java.declaredFields.none { field ->
                field.name in setOf("guardState", "protectionAction", "activityState")
            },
        )
        assertTrue(
            MainActivity::class.java.declaredFields.none { field ->
                field.name.startsWith("productViewModel") || field.type == MainProductViewModel::class.java
            },
        )
        val activity = controller.get()
        val sharedProduct = (activity.application as LavasecApplication).foregroundProduct
        val getter = MainActivity::class.java.getDeclaredMethod("getProductViewModel").apply {
            isAccessible = true
        }
        assertSame(sharedProduct, getter.invoke(activity))
        assertSame(sharedProduct, getter.invoke(activity))

        controller.close()
    }

    @Test
    fun `activity preserves the authoritative version identity verbatim`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()

        assertEquals(BuildConfig.VERSION_NAME, field<String>(controller.get(), "versionName"))

        controller.close()
    }

    @Test
    fun `Help opens the exact external support URL`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        MainActivity::class.java.getDeclaredMethod("openHelp").apply {
            isAccessible = true
            invoke(activity)
        }

        val launched = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, launched.action)
        assertEquals("https://lavasecurity.app/support/", launched.dataString)

        controller.close()
    }

    private fun view(uri: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri))

    private fun activityOverview(): ProductNavigation =
        ProductNavigation.guard.open(ProductDestination.Activity)

    private fun topDomains(): ProductNavigation =
        activityOverview().open(ProductDestination.TopDomains)

    private fun domainHistory(): ProductNavigation =
        activityOverview().open(ProductDestination.DomainHistory)

    private fun request(activity: MainActivity): NavigationRequestSnapshot {
        val state = field<State<*>>(activity, "navigationRequest")
        val value = requireNotNull(state.value)
        return NavigationRequestSnapshot(
            navigation = value.javaClass.getDeclaredMethod("getNavigation").invoke(value)
                as ProductNavigation,
            sequence = value.javaClass.getDeclaredMethod("getSequence").invoke(value) as Long,
        )
    }

    private fun setNavigation(activity: MainActivity, navigation: ProductNavigation) {
        MainActivity::class.java
            .getDeclaredMethod("navigate", ProductNavigation::class.java)
            .apply { isAccessible = true }
            .invoke(activity, navigation)
    }

    private fun savedNavigationState(navigation: ProductNavigation): Bundle {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        setNavigation(controller.get(), navigation)
        val savedState = Bundle()
        controller.saveInstanceState(savedState)
        controller.pause().stop().destroy()
        return savedState
    }

    private inline fun <reified T> field(activity: MainActivity, name: String): T {
        val declared = MainActivity::class.java.declaredFields.singleOrNull { it.name == name }
        checkNotNull(declared) { "MainActivity must own $name" }
        declared.isAccessible = true
        return declared.get(activity) as T
    }

    private data class NavigationRequestSnapshot(
        val navigation: ProductNavigation,
        val sequence: Long,
    )
}
