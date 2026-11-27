import com.google.protobuf.gradle.id
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    id("org.springframework.boot") version "4.1.3"
    id("com.google.protobuf") version "0.9.6"
}

group = "dev.mayday"
version = "0.1.0"

repositories { mavenCentral() }

val grpcVersion = "1.84.1"
val grpcKotlinVersion = "1.5.0"
val protobufVersion = "4.36.2"

dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-grpc-server")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.grpc:grpc-protobuf")
    implementation("io.grpc:grpc-inprocess")
    implementation("io.grpc:grpc-kotlin-stub")
    implementation("com.google.protobuf:protobuf-java:$protobufVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json")
    implementation(kotlin("reflect"))
    implementation("com.zaxxer:HikariCP")
    implementation("org.postgresql:postgresql")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("io.opentelemetry:opentelemetry-extension-kotlin")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("com.microsoft.playwright:playwright:1.65.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }

sourceSets { main { proto { srcDir("proto") } } }

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:$protobufVersion" }
    plugins {
        id("grpcjava") { artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion" }
        id("grpckt") { artifact = "io.grpc:protoc-gen-grpc-kotlin:$grpcKotlinVersion:jdk8@jar" }
    }
    generateProtoTasks {
        all().forEach {
            it.plugins { id("grpcjava"); id("grpckt") }
        }
    }
}

springBoot { mainClass.set("mayday.MaydayApplicationKt") }

tasks.processResources {
    from("web/dist") { into("static") }
}

tasks.bootJar { archiveFileName.set("mayday.jar") }
tasks.jar { enabled = false }

tasks.test {
    useJUnitPlatform { excludeTags("e2e") }
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

// Browser use-case tests against the built web interface (web/dist). MAYDAY_E2E_HEADED=true shows the browser.
val e2e by tasks.registering(Test::class) {
    description = "Runs the Playwright use-case tests."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("e2e") }
    systemProperty("mayday.e2e.web-dir", file("web/dist").absolutePath)
    systemProperty("mayday.e2e.artifacts", layout.buildDirectory.dir("e2e").get().asFile.absolutePath)
    outputs.upToDateWhen { false }
    doFirst { delete(layout.buildDirectory.dir("e2e")) }
    testLogging { events("passed", "failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
