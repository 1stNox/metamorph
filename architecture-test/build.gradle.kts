// Test-only module: Konsist rules over the production sources of every module.
// It has no project dependencies on purpose, so new modules are covered without wiring.
plugins {
    id("metamorph.kotlin-jvm")
}

dependencies {
    testImplementation(libs.konsist)
}

tasks.named<Test>("test") {
    // Konsist reads the production sources of every module straight from disk, which Gradle cannot
    // see. Declare them as inputs, otherwise this task is UP-TO-DATE after a change that only
    // touches production code and a violation goes unnoticed. The pattern needs no per-module wiring.
    inputs
        .files(
            fileTree(rootDir) {
                include("**/src/main/**/*.kt")
                exclude("**/build/**", "**/.gradle/**", "**/.git/**", "**/.kotlin/**")
            },
        ).withPropertyName("productionSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
