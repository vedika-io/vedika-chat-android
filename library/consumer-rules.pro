# Consumer ProGuard rules for the Vedika chat Android SDK.
#
# kotlinx-serialization keeps most of its metadata automatically; the
# rules below are the minimum to keep the JSON envelope types visible.

-keep,includedescriptorclasses class io.vedika.chat.**$$serializer { *; }
-keepclassmembers class io.vedika.chat.** {
    *** Companion;
}
-keepclasseswithmembers class io.vedika.chat.** {
    kotlinx.serialization.KSerializer serializer(...);
}
