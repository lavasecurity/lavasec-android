package com.lavasec.app.product

import android.app.Application
import android.content.res.Configuration
import com.lavasec.app.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The base is frozen translated copy; SharedFilterNamePolicy's numeric suffix is ASCII. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class FilterIdentityResourceTest {
    @Test fun `native creation base matches every frozen catalog locale`() {
        val context = RuntimeEnvironment.getApplication()
        val expected = mapOf("en" to "Untitled", "de" to "Unbenannt", "fr" to "Sans titre",
            "ja" to "無題", "zh-CN" to "未命名", "zh-TW" to "未命名", "es" to "Sin título",
            "ko" to "제목 없음", "pt-BR" to "Sem título", "it" to "Senza titolo")
        for ((tag, base) in expected) {
            val configuration = Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(tag))
            }
            assertEquals(tag, base, context.createConfigurationContext(configuration)
                .getString(R.string.filter_identity_untitled))
        }
    }
}
