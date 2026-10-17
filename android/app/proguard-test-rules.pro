# ErrorProne compiler annotations from AndroidX test dependencies refer to javac-only types.
# The instrumentation APK never executes those annotation values.
-dontwarn javax.lang.model.element.Modifier

# Instrumentation discovers JUnit tests through reflection, including in minified QA builds.
-keep class com.nartya.app.AndroidSmokeTest { *; }
