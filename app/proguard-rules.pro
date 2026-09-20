# Release build rules.

-repackageclasses "wetypeplus"

# libxposed discovers the entry class through META-INF/xposed/java_init.list and
# instantiates it reflectively, so the class name and its no-argument constructor
# must survive shrinking.
-keep,allowobfuscation,allowoptimization public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# The module entry is named in java_init.list by its original FQCN, and
# -adaptresourcefilecontents rewrites that entry to match the obfuscated name.
-adaptresourcefilecontents META-INF/xposed/java_init.list

-dontwarn io.github.libxposed.annotation.**

# Compose / Miuix pull in optional androidx.window classes that are absent on
# most devices; they are only touched through reflection.
-dontwarn androidx.window.extensions.**
-dontwarn androidx.window.sidecar.**

-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void check*(...);
    public static void throw*(...);
}
-assumenosideeffects class java.util.Objects {
    public static ** requireNonNull(...);
}
