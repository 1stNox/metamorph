## 1. Version catalog

- [ ] 1.1 Add `detekt = "2.0.0-alpha.6"`, `ktlint-gradle = "14.2.0"` and `ktlint = "1.8.0"` to `[versions]` in `gradle/libs.versions.toml`
- [ ] 1.2 Add `detekt` (`dev.detekt`) and `ktlint` (`org.jlleitschuh.gradle.ktlint`) entries to `[plugins]`, referencing the versions above
- [ ] 1.3 Confirm the exact detekt 2.0.0-alpha.6 plugin and Kotlin 2.4.10 compatibility by running `./gradlew help` with the plugin applied (fall back to `io.gitlab.arturbosch.detekt` 1.23.8 per design Decision 2 only if it fails)

## 2. Plugin wiring

- [ ] 2.1 Apply `alias(libs.plugins.detekt)` and `alias(libs.plugins.ktlint)` in `app/build.gradle.kts`
- [ ] 2.2 Configure the `ktlint { }` extension: pin the engine to the catalog `ktlint` version, keep `.gradle.kts` script checking enabled, exclude build output
- [ ] 2.3 Configure the `detekt { }` extension: `buildUponDefaultConfig = true`, `config.setFrom(rootProject.file("config/detekt/detekt.yml"))`, `parallel = true`, and no baseline
- [ ] 2.4 Do not add `dev.detekt:detekt-rules-ktlint-wrapper` to `detektPlugins`
- [ ] 2.5 Verify `./gradlew check` depends on detekt and `ktlintCheck`; if the type-resolved `detektMain`/`detektTest` tasks are not wired into `check`, add the dependency explicitly

## 3. Shared formatting configuration

- [ ] 3.1 Create the root `.editorconfig` with `root = true`, `ktlint_code_style = ktlint_official`, `indent_size = 4`, `max_line_length = 120`, `insert_final_newline = true`, `trim_trailing_whitespace = true`, and `charset = utf-8` for `*.{kt,kts}`
- [ ] 3.2 Run `./gradlew detektGenerateConfig` to produce the full default config in the scratchpad (not committed) and list every `style` rule that also exists in ktlint
- [ ] 3.3 Create `config/detekt/detekt.yml` containing only deviations from the defaults: disable the rules duplicating ktlint (`MaxLineLength`, `NewLineAtEndOfFile`, `WildcardImport`, `UnusedImport`, `ModifierOrder`, `NoTabs`, `SpacingBetweenPackageAndImports`, plus any others found in 3.2)
- [ ] 3.4 Run `./gradlew detekt` and confirm detekt rejects no unknown or removed rule IDs (2.0 renamed several, e.g. `UnusedImports` -> `UnusedImport`)

## 4. Fix existing code

- [ ] 4.1 Run `./gradlew ktlintFormat` and review the diff to `App.kt`, `AppTest.kt`, `app/build.gradle.kts`, `settings.gradle.kts`
- [ ] 4.2 Run `./gradlew detekt` and fix remaining findings by hand (no baseline files)
- [ ] 4.3 Run `./gradlew check` and confirm it passes with both tools active

## 5. Verify non-interference and cache behavior

- [ ] 5.1 Add a temporary scratch Kotlin file under `app/src/main/kotlin` with deliberate formatting problems (wildcard import, unused import, missing final newline, over-long line, wrong modifier order)
- [ ] 5.2 Run `./gradlew ktlintCheck` and confirm it reports the problems, and `./gradlew detekt` and confirm it does not report the same ones
- [ ] 5.3 Run `./gradlew ktlintFormat`, then `./gradlew ktlintCheck detekt`, and confirm both pass for the formatting-related aspects
- [ ] 5.4 Delete the scratch file and confirm `git status` shows no leftover
- [ ] 5.5 Run `./gradlew check` twice and confirm the second run reports "Configuration cache entry reused" with no configuration-cache problems from either plugin

## 6. Documentation

- [ ] 6.1 Add a short "Code quality" section to `README.md` listing `./gradlew check`, `./gradlew ktlintFormat` and where the config files live
