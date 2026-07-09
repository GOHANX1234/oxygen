# Reflection-based hook installation and hidden-API access rely on exact class/member
# names of both our own virtual-service classes and framework internals. Keep them.
-keep class com.oxygens.core.virtual.** { *; }
-keep class com.oxygens.core.storage.** { *; }
-keep class com.oxygens.core.loader.** { *; }
-keep class com.oxygens.compat.** { *; }
-keep class com.oxygens.nativehook.** { *; }
-keep class com.oxygens.app.stub.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod
