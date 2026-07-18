import org.gradle.api.file.DuplicatesStrategy

plugins {
    kotlin("jvm") version "2.1.10"
    kotlin("plugin.serialization") version "2.1.10"
    application
}

group = "pro.masterdoc"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    val ktor = "3.0.3"
    implementation("io.ktor:ktor-server-core-jvm:$ktor")
    implementation("io.ktor:ktor-server-netty-jvm:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktor")
    implementation("io.ktor:ktor-server-cors-jvm:$ktor")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktor")
    implementation("io.ktor:ktor-server-auth-jvm:$ktor")
    implementation("io.ktor:ktor-server-auth-jwt-jvm:$ktor")
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktor")
    implementation("io.ktor:ktor-client-core-jvm:$ktor")
    implementation("io.ktor:ktor-client-cio-jvm:$ktor")
    implementation("io.ktor:ktor-client-content-negotiation-jvm:$ktor")
    implementation("com.auth0:java-jwt:4.4.0")
    implementation("com.auth0:jwks-rsa:0.22.1")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktor")
    testImplementation("io.ktor:ktor-client-mock-jvm:$ktor")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("pro.masterdoc.gateway.ApplicationKt")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "pro.masterdoc.gateway.ApplicationKt"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(
        configurations.runtimeClasspath.get().map { dependency ->
            if (dependency.isDirectory) dependency else zipTree(dependency)
        },
    )
}

tasks.test {
    useJUnitPlatform()
}
