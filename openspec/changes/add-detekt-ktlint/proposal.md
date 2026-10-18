## Why

The project has no static analysis or formatting enforcement, so style and code-smell issues can only be caught in review. Adding ktlint (formatting) and detekt (code smells, complexity) now, while the codebase is a single small module, keeps the baseline clean from the start. The two tools overlap in the style area, so they must be configured together or they will flag, or "fix" into, each other's violations.

## What Changes

- Add the detekt Gradle plugin (`dev.detekt`) for static analysis of Kotlin sources (main and test source sets).
- Add the ktlint Gradle plugin (`org.jlleitschuh.gradle.ktlint`) for format checking and auto-formatting of Kotlin sources and `*.gradle.kts` scripts.
- Make ktlint the single owner of formatting and detekt the owner of code-quality rules: a shared `.editorconfig` drives ktlint, and detekt rules that duplicate ktlint (line length, trailing whitespace, final newline, wildcard imports, modifier order, etc.) are disabled in the detekt config, while naming and unused imports belong to detekt and are disabled on the ktlint side.
- Do not add detekt's ktlint wrapper rule set (`detekt-rules-ktlint-wrapper`), since it would run ktlint a second time under detekt.
- Wire both tools into `./gradlew check` so violations fail the build; `./gradlew ktlintFormat` provides auto-fix.
- Manage plugin versions through the existing version catalog (`gradle/libs.versions.toml`).
- Fix any findings in the existing sample code (`App.kt`, `AppTest.kt`) so the build stays green.

## Capabilities

### New Capabilities
- `code-quality-checks`: Static analysis and formatting enforcement for Kotlin sources: detekt and ktlint run as part of `check`, own non-overlapping rule areas, and share one formatting configuration.

### Modified Capabilities
<!-- None: openspec/specs/ is empty, so there are no existing requirements to change. -->

## Impact

- **Build files**: `gradle/libs.versions.toml` (new plugin entries), `app/build.gradle.kts` (apply plugins). Config files live at the repo root and are referenced via `rootProject`, so future modules can reuse them (a `build-logic` convention plugin is deferred until a second module exists).
- **New config files**: `.editorconfig` (ktlint style), `config/detekt/detekt.yml` (detekt rules with ktlint-overlapping rules disabled).
- **Source code**: small formatting/lint fixes in existing Kotlin files if any are flagged.
- **Dependencies/tooling**: detekt `2.0.0-alpha.6` (alpha, but the only line built for Kotlin 2.4, which this project uses) and ktlint-gradle `14.2.0`. Detekt requires Gradle 8.14+ and the build already uses Gradle 9.8.1.
- **Developer workflow**: `./gradlew check` becomes stricter and slower; contributors can run `./gradlew ktlintFormat` to fix formatting.
