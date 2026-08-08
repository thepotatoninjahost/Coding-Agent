plugins { kotlin("jvm") }
dependencies {
    api(project(":modules:domain"))
    api(project(":modules:intake"))
    api(project(":modules:workspace"))
    api(project(":modules:research"))
    api(project(":modules:model"))
    api(project(":modules:terminal"))
    api(project(":modules:persistence"))
    api(project(":modules:knowledge"))
    api(project(":modules:policy"))
    implementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
}
tasks.test { useJUnitPlatform() }
