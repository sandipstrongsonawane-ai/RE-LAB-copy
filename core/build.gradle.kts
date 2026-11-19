plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(17) }
dependencies { implementation("org.jsoup:jsoup:1.18.3"); testImplementation("junit:junit:4.13.2") }
tasks.test { useJUnit() }
