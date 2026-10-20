plugins { application }
java { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
tasks.withType<JavaCompile>().configureEach { options.release.set(21) }
dependencies {
    implementation(project(":live-client"))
    implementation(project(":live-jvm-signer"))
}
application { mainClass.set("io.github.nglmercer.tiktoklive.examples.EmbeddedMain") }
tasks.named<JavaExec>("run") { standardInput = System.`in` }
