plugins { kotlin("jvm") }
dependencies {
    api(project(":modules:domain"))
    implementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
}
tasks.test { useJUnitPlatform() }
