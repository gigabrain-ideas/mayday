import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    application
}

group = "dev.mayday"
version = "0.1.0"

repositories { mavenCentral() }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.postgresql:postgresql:42.7.7")
    implementation("com.anthropic:anthropic-java:3.3.0")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }

application {
    mainClass.set("mayday.MainKt")
    applicationDefaultJvmArgs = listOf("-XX:+UseZGC", "-XX:MaxRAMPercentage=75")
}

tasks.processResources {
    from("web/dist") { into("web") }
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
