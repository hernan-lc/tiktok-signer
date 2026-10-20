pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
rootProject.name = "tiktok-live-java"
include("live-jvm-signer", "live-core", "live-client", "live-proto", "live-presence", "live-native")
listOf("embedded-java", "basic-live", "presence", "multi-presence", "native-signer", "minecraft-style").forEach {
    include(":examples:$it")
}
