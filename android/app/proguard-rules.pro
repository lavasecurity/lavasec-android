# Release shrinking keeps the React Native registration surface.
#
# The published `react-android` AAR ships its own consumer rules that keep
# `NativeModule` implementers and `@ReactProp` methods, and this app references
# its package/view-manager constructors statically. These rules make the
# JS-facing registration explicit so R8 cannot rename or strip the classes that
# back `NativeLavaApp`, `LavaDecoration` and `LavaSlider`.
# ci/verify_android_bundle.py re-proves the packaged registration in the release
# AAB's shrunk DEX.

# The native module registered as `NativeLavaApp`.
-keep class com.lavasec.app.reactnative.LavaAppModule { *; }

# The package that hands RN the module and the view managers.
-keep class com.lavasec.app.reactnative.LavaAppPackage { *; }

# The platform view managers returned by LavaAppPackage.createViewManagers.
-keep class com.lavasec.app.reactnative.Lava*Manager { *; }

# ViewGroup's constructor can dispatch requestLayout before ScreenContainer's
# non-null Kotlin layoutCallback field has been initialized. Its explicit null
# guard is required at that point. Preserve both the field and method: keeping
# only the method still lets field specialization erase the null check.
# Keep optimization elsewhere.
-keepclassmembers,allowobfuscation class com.swmansion.rnscreens.ScreenContainer {
    private final android.view.Choreographer$FrameCallback layoutCallback;
    public void requestLayout();
}

# RNScreensFragmentFactory recognizes retired screen fragments by their package
# prefix. Obfuscating those names bypasses AutoRemovingFragment and calls the
# deliberately unsupported no-arg constructor during Activity recreation.
-keepnames class com.swmansion.rnscreens.** extends androidx.fragment.app.Fragment
