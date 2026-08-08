plugins { kotlin("jvm") }
dependencies { api(project(":modules:domain")); testImplementation(kotlin("test")) }
tasks.test { useJUnitPlatform() }
