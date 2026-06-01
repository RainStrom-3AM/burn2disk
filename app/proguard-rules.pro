# libaums uses reflection-free APIs; keep its public surface to be safe.
-keep class me.jahnen.libaums.** { *; }
-dontwarn me.jahnen.libaums.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Hilt generated code
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel { *; }
