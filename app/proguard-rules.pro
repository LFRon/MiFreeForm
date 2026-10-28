-keep class com.mja.reyamf.** { *; }
-keep class com.android.dx.** { *; }
# Keep ByteBuddy utility dispatcher annotations
-keep @interface net.bytebuddy.utility.dispatcher.JavaDispatcher$Proxied

# Keep all dispatchers (you might as well keep all utility dispatcher classes)
-keep class net.bytebuddy.utility.dispatcher.** { *; }

# Keep TypeDescription dispatcher
-keep class net.bytebuddy.description.type.TypeDescription$ForLoadedType$Dispatcher { *; }

-keepclassmembers class com.android.dx.dex.cf.CfTranslator { public static *** translate(...); }
-dontwarn edu.umd.cs.findbugs.annotations.SuppressFBWarnings
-dontwarn javax.annotation.Nonnull
# libxposed loads the entry by name from META-INF/xposed/java_init.list.
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
-adaptresourcefilecontents META-INF/xposed/java_init.list
-dontwarn io.github.libxposed.annotation.**
