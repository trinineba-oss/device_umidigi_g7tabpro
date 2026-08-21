plugins {
    kotlin("jvm")
    application
}
kotlin { jvmToolchain(17) }
dependencies { implementation(project(":core")) }
application { mainClass.set("dev.g7tabpro.gsipatch.cli.MainKt") }
