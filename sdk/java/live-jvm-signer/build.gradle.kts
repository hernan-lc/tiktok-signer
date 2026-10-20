val graalVersion = "25.3.4.1"
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
tasks.withType<JavaCompile>().configureEach { options.release.set(21) }
dependencies {
    api(project(":live-core"))
    implementation(project(":live-presence"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(project(":live-client"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    implementation("org.graalvm.polyglot:polyglot:$graalVersion")
    runtimeOnly("org.graalvm.polyglot:js-community:$graalVersion")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.6")
}
tasks.processResources {
    from(rootProject.file("../../crates/ttl-sign-embedded/bootstrap.js")) {
        into("io/github/nglmercer/tiktoklive")
    }
}
tasks.register("runtimeFootprint") {
    doLast {
        val files = configurations.runtimeClasspath.get().files.filter { it.isFile }
        println("Runtime artifacts: ${files.size}; bytes: ${files.sumOf { it.length() }}")
    }
}
