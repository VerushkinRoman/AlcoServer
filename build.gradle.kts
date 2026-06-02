plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    alias(libs.plugins.shadow)
    application
}

group = "ru.alcoserver"
version = "1.0.0"

application {
    mainClass.set("ru.alcoserver.ApplicationKt")
    applicationDefaultJvmArgs = listOf("-Xmx512m")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.logging)

    implementation(libs.kotlinx.serialization.json)

    implementation(libs.google.play.integrity)

    implementation(libs.firebase.admin)

    implementation(libs.logback.classic)

    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.kotlinx.datetime)
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
