# Room entities and DAOs are generated; keep them.
-keep class app.obdforge.data.** { *; }
# Core module uses reflection-free code, but keep enum names used in persistence.
-keepclassmembers enum * { *; }
-dontwarn org.slf4j.**
