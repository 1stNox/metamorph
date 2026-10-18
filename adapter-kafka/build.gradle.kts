plugins {
    id("metamorph.kotlin-jvm")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kafka.clients)
}

// Integration tests run against a real broker in a container, so they are a separate suite:
// `./gradlew test` stays free of Docker, `./gradlew integrationTest` (and `check`) runs them.
testing {
    suites {
        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter(libs.versions.junit.jupiter)

            dependencies {
                implementation(project())
                implementation(project(":core"))
                implementation(libs.kafka.clients)
                implementation(libs.testcontainers.kafka)
                runtimeOnly(libs.slf4j.simple)
            }

            targets.configureEach {
                testTask.configure { shouldRunAfter(tasks.named("test")) }
            }
        }
    }
}

// Let the integration tests see `internal` declarations of the main source set.
kotlin {
    val compilations = target.compilations
    compilations.named("integrationTest") {
        associateWith(compilations.getByName("main"))
    }
}

tasks.named("check") {
    dependsOn(testing.suites.named("integrationTest"))
}
