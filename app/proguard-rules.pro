# R8 Full-Mode Aggressive Optimization & Shrinking
-repackageclasses 'o'
-allowaccessmodification
-optimizationpasses 5

# Strip verbose/debug/info logs from release bytecode
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Room Database reflective instantiation (Room's consumer rules cover DAOs/Entities)
-keep class * extends androidx.room.RoomDatabase {
    <init>();
}
-dontwarn javax.naming.**
-dontwarn java.lang.invoke.StringConcatFactory

# Media3 / Coil 3 / OkHttp / Coroutines ship their own precise consumer ProGuard rules.
# Suppress optional compile-only platform & TLS backend warnings without disabling shrinking.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
