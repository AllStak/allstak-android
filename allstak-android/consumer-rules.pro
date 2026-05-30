# AllStak Android SDK — consumer ProGuard / R8 rules.
# Applied automatically to any app that depends on this library.

# Keep the public API surface stable for reflection-free callers.
-keep class sa.allstak.android.AllStak { *; }
-keep class sa.allstak.android.AllStakOptions { *; }
-keep class sa.allstak.android.AllStakOptions$* { *; }

# androidx.startup discovers the initializer by class name from the manifest.
-keep class sa.allstak.android.AllStakInitializer { *; }
-keep class * implements androidx.startup.Initializer { *; }

# Wire models are serialized by name — keep field names so the camelCase
# payload contract stays byte-stable under minification.
-keepclassmembers class sa.allstak.android.core.model.** {
    <fields>;
    <init>(...);
}

# Do not warn about optional integrations the host app may not include.
-dontwarn okhttp3.**
-dontwarn timber.log.**
