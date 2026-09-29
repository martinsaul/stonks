plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":engine"))
}

application {
    mainClass.set("stonks.cli.MainKt")
    applicationName = "stonks-sim"
    applicationDefaultJvmArgs = listOf("-Xmx4g")
}
