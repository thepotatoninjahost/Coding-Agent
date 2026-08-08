plugins { kotlin("jvm") }
dependencies {
    api(project(":modules:domain"))
    api(project(":modules:policy"))
    api(project(":modules:workspace"))
    api(project(":modules:knowledge"))
    testImplementation(kotlin("test"))
}
tasks.test { useJUnitPlatform() }
