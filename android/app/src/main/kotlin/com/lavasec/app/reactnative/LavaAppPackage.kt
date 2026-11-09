package com.lavasec.app.reactnative

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider
import com.lavasec.app.reactnative.components.LavaNativePageManager

/**
 * Registers the scaffolded React Native modules for the Android host.
 *
 * The module is a legacy [com.facebook.react.bridge.NativeModule]; keeping
 * `isTurboModule = false` avoids depending on codegen while the contract is
 * still settling. Moving to a generated TurboModule later is a local change.
 */
class LavaAppPackage : BaseReactPackage() {

    override fun createViewManagers(context: ReactApplicationContext): List<com.facebook.react.uimanager.ViewManager<*, *>> =
        listOf(LavaTextFieldManager(), LavaDecorationManager(), LavaSliderManager(), LavaNativePageManager(),
            LavaShareCardSurfaceManager(), LavaShareQrManager(), LavaBackupCredentialManager(), LavaPasscodeFormManager())

    override fun getModule(
        name: String,
        reactContext: ReactApplicationContext,
    ): NativeModule? = if (name == LavaAppModule.NAME) LavaAppModule(reactContext) else null

    override fun getReactModuleInfoProvider(): ReactModuleInfoProvider =
        ReactModuleInfoProvider {
            mapOf(
                LavaAppModule.NAME to ReactModuleInfo(
                    LavaAppModule.NAME,
                    LavaAppModule::class.java.name,
                    false,
                    false,
                    false,
                    false,
                ),
            )
        }
}
