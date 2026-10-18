## ADDED Requirements

### Requirement: detekt analyzes Kotlin sources
The build SHALL apply the detekt Gradle plugin to the `app` module and run it over the main and test Kotlin source sets, using the repository's `config/detekt/detekt.yml` on top of detekt's default configuration.

#### Scenario: detekt runs as part of check
- **WHEN** a developer runs `./gradlew check`
- **THEN** the detekt analysis runs over `src/main/kotlin` and `src/test/kotlin`

#### Scenario: detekt violation fails the build
- **WHEN** a Kotlin source file contains a violation of an active detekt rule
- **THEN** `./gradlew check` fails and the finding is reported with file and line

#### Scenario: repository config is layered on defaults
- **WHEN** detekt runs
- **THEN** rules not mentioned in `config/detekt/detekt.yml` use detekt's default activation and thresholds

### Requirement: ktlint checks and formats Kotlin sources
The build SHALL apply the ktlint Gradle plugin to the `app` module with the ktlint engine version pinned in the version catalog, check Kotlin sources and `*.gradle.kts` scripts, and provide an auto-format task.

#### Scenario: ktlint runs as part of check
- **WHEN** a developer runs `./gradlew check`
- **THEN** `ktlintCheck` runs over the main and test source sets and the Gradle Kotlin scripts

#### Scenario: formatting violation fails the build
- **WHEN** a Kotlin file is mis-formatted according to the repository's `.editorconfig`
- **THEN** `./gradlew check` fails and the violation is reported with file and line

#### Scenario: auto-format fixes violations
- **WHEN** a developer runs `./gradlew ktlintFormat` on mis-formatted sources
- **THEN** the files are rewritten to conform and a subsequent `./gradlew ktlintCheck` passes

### Requirement: Single source of truth for formatting
Formatting settings (code style, indentation, maximum line length, final newline, trailing whitespace) SHALL be defined once, in the root `.editorconfig`, and SHALL NOT be duplicated in the detekt configuration.

#### Scenario: line length is configured in one place
- **WHEN** the maximum line length is changed in `.editorconfig`
- **THEN** ktlint enforces the new limit and no detekt configuration change is required

#### Scenario: formatting rules are not active in detekt
- **WHEN** detekt runs
- **THEN** the detekt ktlint wrapper rule set is not on the classpath and detekt rules that duplicate ktlint rules are disabled in `config/detekt/detekt.yml`

### Requirement: detekt and ktlint do not conflict
The two tools SHALL be configured so that no violation is reported by both tools and so that code produced by `ktlintFormat` does not violate any active detekt rule that ktlint could have caused.

#### Scenario: formatted code passes detekt
- **WHEN** mis-formatted Kotlin code is processed by `./gradlew ktlintFormat`
- **THEN** both `./gradlew ktlintCheck` and `./gradlew detekt` pass for the formatting-related aspects of that code

#### Scenario: formatting problems are reported by ktlint only
- **WHEN** a file has a formatting problem such as a missing final newline, a wildcard import, a wrong modifier order or an over-long line
- **THEN** the problem is reported by ktlint only and not by detekt

#### Scenario: naming and unused imports are reported by detekt only
- **WHEN** a file has an unused import or a naming problem
- **THEN** the problem is reported by detekt only and not by ktlint

### Requirement: Tool versions are managed in the version catalog
The detekt plugin version, the ktlint Gradle plugin version, and the ktlint engine version SHALL be declared in `gradle/libs.versions.toml` and consumed by alias.

#### Scenario: versions come from the catalog
- **WHEN** a developer inspects `app/build.gradle.kts`
- **THEN** both plugins are applied via `alias(libs.plugins....)` and no version literal for detekt or ktlint appears in the build script

### Requirement: Existing code passes the checks without baselines
The repository's Kotlin sources and build scripts SHALL pass `./gradlew check` with detekt and ktlint active, and no detekt or ktlint baseline file SHALL be committed to hide existing findings.

#### Scenario: clean build on main
- **WHEN** a developer runs `./gradlew check` on a fresh checkout of the branch containing this change
- **THEN** the build succeeds, including the detekt and ktlint tasks

#### Scenario: build remains compatible with the configuration cache
- **WHEN** `./gradlew check` is run twice in a row with `org.gradle.configuration-cache=true`
- **THEN** the second run reuses the configuration cache and reports no configuration-cache problems from the detekt or ktlint plugins
