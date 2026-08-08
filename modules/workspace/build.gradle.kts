plugins { kotlin("jvm") }
dependencies {
    api(project(":modules:domain"))
    implementation(project(":modules:terminal"))
    testImplementation(kotlin("test"))
}
tasks.test { useJUnitPlatform() }
