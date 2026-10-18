// Shared Kotlin/JVM setup for every Metamorph module.

plugins {
    kotlin("jvm")
    jacoco
    id("dev.detekt")
    id("org.jlleitschuh.gradle.ktlint")
}

repositories {
    mavenCentral()
}

// Precompiled script plugins have no type-safe `libs` accessors, so look the catalog up by name.
val libs = the<VersionCatalogsExtension>().named("libs")

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()

    finalizedBy(tasks.named("jacocoTestReport"))
}

jacoco {
    toolVersion = libs.findVersion("jacoco").get().requiredVersion
}

tasks.named<JacocoReport>("jacocoTestReport") {
    reports {
        xml.required = true
    }
}

ktlint {
    version = libs.findVersion("ktlint").get().requiredVersion

    filter {
        exclude { it.file.path.contains("/build/") }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    parallel = true
}

tasks.named("check") {
    dependsOn("detektMain", "detektTest")
}
