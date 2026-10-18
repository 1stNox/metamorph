dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // The ktlint and detekt Gradle plugins are published here.
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
