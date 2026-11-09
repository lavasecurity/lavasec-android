# The native DoQ cdylib binds exact JNI symbols; retain their owning class/methods after R8.
-keep class com.lavasec.app.vpn.NativeDoqCore { *; }
