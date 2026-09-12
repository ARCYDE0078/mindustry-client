-dontobfuscate

-keep class mindustry.** { *; }
-keep class arc.** { *; }
-keep class net.jpountz.** { *; }
-keep class rhino.** { *; }
-keep class com.android.dex.** { *; }
-keep class com.android.dx.** { *; }
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod

-dontwarn javax.naming.**

#agzam4.Awt/GifIO reference java.desktop/javax.imageio classes that don't exist on Android -
#both are already runtime-guarded (Package.getPackage(...) == null, plus a !Vars.mobile check
#in ModSettingsDialog for Awt), the classes are just unreachable on this platform, not missing
#by mistake.
-dontwarn java.awt.**
-dontwarn javax.imageio.**

#ClientUtils.restartGame() relaunches the desktop JVM via java.lang.management.ManagementFactory,
#already wrapped in try/catch(Exception)/catch(NoClassDefFoundError) - meaningless on Android but
#not missing by mistake.
-dontwarn java.lang.management.**

#Commands.kt's "kt" scripting console lazily loads ScriptEngineHolder (javax.script), guarded by a
#try/catch(Throwable) at the call site that falls back to a "not supported" message - this desktop
#REPL feature has no Android equivalent, the classes are just unreachable on this platform.
-dontwarn javax.script.**

#-printusage out.txt