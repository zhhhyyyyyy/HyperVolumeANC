## Xposed entry point: referenced by name from META-INF/xposed/java_init.list.
-keep class io.github.hypervolumeanc.hook.HookEntry { *; }

## Hook helpers are loaded through the module class loader, keep them intact.
-keep class io.github.hypervolumeanc.hook.** { *; }
-keepattributes *Annotation*, Exceptions, InnerClasses, Signature, SourceFile, LineNumberTable
-dontwarn io.github.libxposed.**

## Release builds stay silent: strip every android.util.Log call.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
    public static *** println(...);
}
