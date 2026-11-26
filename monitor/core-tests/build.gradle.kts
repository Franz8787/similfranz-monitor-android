plugins { id("org.jetbrains.kotlin.jvm"); application }
kotlin { jvmToolchain(17) }
dependencies { implementation(project(":core-state")); implementation(project(":core-engine")); implementation(project(":core-api")) }
application { mainClass.set("it.simulafranz.coretests.RunTestsKt") }
