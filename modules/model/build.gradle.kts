plugins { kotlin("jvm") }
dependencies {
    api(project(":modules:domain"))
    api(project(":modules:policy"))
    api(project(":modules:live"))
    implementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
}
tasks.test { useJUnitPlatform() }
