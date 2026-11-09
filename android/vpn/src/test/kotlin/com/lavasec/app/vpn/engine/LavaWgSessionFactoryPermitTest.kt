package com.lavasec.app.vpn.engine

import com.lavasec.app.platform.ChainedEnginePermit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the engine factory's eligibility gate at the signature level.
 *
 * The no-instantiation guarantee is structural only if the ~50 MB session cannot be created
 * without a [ChainedEnginePermit]. This is a reflection pin rather than a behavioural test
 * because the factory's body loads the native engine, which a host JVM cannot do; the property
 * that matters is the shape of the public factory, and that is observable without executing it.
 * `LavaWgSession` has no static initializer that touches the native core, so loading its class
 * is safe here.
 */
class LavaWgSessionFactoryPermitTest {

    @Test
    fun `every LavaWgSession factory requires an eligibility permit first`() {
        val factories = LavaWgSession.Companion::class.java.declaredMethods
            .filter { it.name == "create" }
        assertFalse("expected at least one create factory", factories.isEmpty())
        factories.forEach { factory ->
            val parameters = factory.parameterTypes
            assertTrue(
                "create(${parameters.joinToString { it.simpleName }}) must take a " +
                    "ChainedEnginePermit as its first parameter",
                parameters.isNotEmpty() && parameters[0] == ChainedEnginePermit::class.java,
            )
        }
    }
}
