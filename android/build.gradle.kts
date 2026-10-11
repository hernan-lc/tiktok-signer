// AGP 9 has built-in Kotlin; the Compose compiler must match its Kotlin version.
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}

if (JavaVersion.current() != JavaVersion.VERSION_21) {
    throw GradleException(
        "Android builds and Robolectric tests require JDK 21; " +
            "current JVM is ${JavaVersion.current()}."
    )
}
