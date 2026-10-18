## Context

Metamorph is a Gradle (9.8.1, Kotlin DSL, version catalog, configuration cache enabled) project on Kotlin 2.4.10 with a JDK 25 toolchain. Today only the `app` module is included in `settings.gradle.kts`; `core`, `adapter-kafka`, `architecture-test` and `build-logic` directories exist locally but are not part of the build (they contain only ignored build output). There is no `.editorconfig`, no linting and no formatting enforcement.

detekt and ktlint both report on Kotlin style. The overlap is real: detekt's `style` rule set contains rules such as `MaxLineLength`, `NewLineAtEndOfFile`, `WildcardImport`, `UnusedImport` and `ModifierOrder` that ktlint also implements, and detekt can additionally embed ktlint itself through its ktlint wrapper rule set. Run naively side by side, the same problem is reported twice, and the two tools can disagree on thresholds (for example, line length) so that code formatted by one fails the other.

Registry check (2026-10-18): the stable detekt line (`io.gitlab.arturbosch.detekt` 1.23.8) predates Kotlin 2.4; the 2.0 line (`dev.detekt`, latest `2.0.0-alpha.6`) is built against Kotlin 2.4 and requires Gradle 8.14+. ktlint-gradle's latest is 14.2.0, and the latest ktlint CLI release is 1.8.0.

## Goals / Non-Goals

**Goals:**
- `./gradlew check` runs detekt and ktlint over main and test Kotlin sources and the `*.gradle.kts` scripts, and fails on violations.
- Each style concern is owned by exactly one tool, so no issue is reported twice and formatted code never fails the other tool.
- One developer command (`./gradlew ktlintFormat`) fixes all auto-fixable formatting.
- Configuration lives in the repository root so modules added later inherit it without copying.

**Non-Goals:**
- CI pipeline integration (no CI exists yet).
- IDE setup, git hooks, or SARIF/code-scanning upload.
- Custom detekt rules or ktlint rule sets.
- Introducing a `build-logic` convention plugin now; there is only one module.
- Fixing hypothetical future modules' findings.

## Decisions

### 1. Two standalone tools, split by responsibility; no detekt ktlint wrapper
ktlint owns **formatting** (layout, whitespace, import ordering, trailing commas, modifier order, wildcard/unused imports, final newline, line length). detekt owns **code quality** (complexity, naming-by-semantics, potential bugs, performance, exceptions, coroutines, etc.). The detekt `dev.detekt:detekt-rules-ktlint-wrapper` artifact is deliberately *not* added.

- *Why:* the user asked for both tools. The wrapper would run ktlint a second time inside detekt with a separately configured rule set, which is exactly the duplication to avoid. Standalone ktlint gets the maintained Gradle integration (`ktlintCheck`, `ktlintFormat`, script-file checks, up-to-date checking).
- *Alternative considered:* detekt + ktlint wrapper only (single tool, single config file, `autoCorrect: true`). Simpler, and conflicts are impossible by construction, but there is no standalone ktlint, the wrapper's ktlint version lags the ktlint release, and the wrapper requires its own `ktlint:` config block. Kept as the fallback if the standalone setup proves noisy.

### 2. Plugins: `dev.detekt` 2.0.0-alpha.6 and `org.jlleitschuh.gradle.ktlint` 14.2.0
Both are declared in `gradle/libs.versions.toml` (`[versions]` + `[plugins]`) and applied by alias, matching how the Kotlin plugin is already declared. The ktlint engine version is pinned separately (`ktlint = "1.8.0"`) in the catalog and passed to the `ktlint { version = ... }` extension, so the formatter does not change silently when the Gradle plugin is bumped.

- *Why detekt 2.0 alpha:* it is the only line built and tested against Kotlin 2.4 (the project is on 2.4.10). detekt is tightly coupled to its Kotlin compiler; the 1.23.8 line is built against an older compiler and may mis-parse newer syntax.
- *Alternative considered:* `io.gitlab.arturbosch.detekt` 1.23.8 (stable). Rejected as the default because of the Kotlin gap, but is the fallback if the alpha misbehaves.
- *Alternative considered:* `org.jmailen.kotlinter` instead of ktlint-gradle. Comparable, but ktlint-gradle is the more widely used and supports baselines, `.editorconfig` and script files.

### 3. Ownership is enforced by configuration, not convention
- **`.editorconfig` (repo root)** is the single source of truth for formatting: `ktlint_code_style = ktlint_official`, `indent_size = 4`, `max_line_length = 120`, `insert_final_newline = true`, `trim_trailing_whitespace = true`. ktlint reads it natively. IDEs pick it up too.
- **`config/detekt/detekt.yml`** is loaded with `buildUponDefaultConfig = true`, so it only contains deviations from detekt's defaults. It disables every detekt rule that duplicates a ktlint rule. The candidate list is: `MaxLineLength`, `NewLineAtEndOfFile`, `WildcardImport`, `UnusedImport`, `ModifierOrder`, `NoTabs`, `SpacingBetweenPackageAndImports`, and any `style` rule found at implementation time that the ktlint `ktlint_official` rule set also covers. The exact IDs are confirmed against the config produced by `detektGenerateConfig` (rule IDs changed between 1.x and 2.0).
- Rules where both tools have an opinion but about different aspects (e.g. detekt `FunctionNaming` for semantic naming vs ktlint `function-naming`) are left on in both only if their configurations agree; otherwise detekt's is disabled.

### 4. Prove non-interference with a round-trip check
The acceptance criterion for "not violating each other's rules" is a round trip: after `ktlintFormat`, `ktlintCheck` and `detekt` must both pass on the same sources. During implementation this is verified once by deliberately mis-formatting a scratch Kotlin file, formatting it, and running both tools. No permanent test is added; the check is run manually and recorded in the task list.

### 5. Wiring and scope
- Both plugins attach to `check` through their own default wiring. If detekt's default wiring does not include the type-resolved `detektMain`/`detektTest` tasks, `check` is made to depend on them.
- Sources: `src/main/kotlin`, `src/test/kotlin`, and `*.gradle.kts` (ktlint). Build output directories are excluded.
- Config files are referenced via `rootProject.file(...)` so the same `app/build.gradle.kts` snippet works unchanged when it is later moved into a convention plugin or applied to more modules.
- Plugins are applied in `app/build.gradle.kts` for now. Extracting to a `build-logic` convention plugin is deferred until a second module is actually added to `settings.gradle.kts`.

### 6. Existing code: fix, do not baseline
The only Kotlin files are `App.kt` and `AppTest.kt` (Gradle `init` sample code). Findings are fixed directly. No baseline files are created; a baseline would hide problems that cost nothing to fix on a two-file codebase.

## Risks / Trade-offs

- **[detekt 2.0 is an alpha]** → Breaking changes between alphas are possible. Pin the exact version in the catalog; fall back to 1.23.8 with ktlint unchanged if it fails to run on Kotlin 2.4.10.
- **[ktlint 1.8.0 may not parse the newest Kotlin 2.4 syntax]** → Only matters if source uses brand-new syntax. Pin explicitly so an upgrade is a deliberate catalog edit.
- **[A duplicated rule is missed in the detekt disable-list]** → Mitigated by the round-trip check in Decision 4 and by generating the full default config and reviewing it against the ktlint rule list during implementation.
- **[Config drift: someone edits `.editorconfig` line length but detekt keeps its own]** → The detekt line-length rule is disabled outright, so `.editorconfig` is the only place the limit exists.
- **[`check` gets slower]** → Accepted. Type-resolved detekt and ktlint both use Gradle up-to-date checks and the configuration cache, so unchanged sources are skipped.
- **[Configuration cache incompatibility in a plugin]** → `org.gradle.configuration-cache=true` is on. Both plugins are expected to support it; verified by running `./gradlew check` twice and confirming the second run reuses the cache.

## Migration Plan

1. Add catalog entries and apply the plugins.
2. Add `.editorconfig` and `config/detekt/detekt.yml`.
3. Run `./gradlew ktlintFormat`, then `./gradlew check`; fix remaining findings by hand.
4. Run the round-trip check from Decision 4.

Rollback is a revert of the change: removing the plugin aliases and the two config files restores the previous build; there is no data or runtime impact.

## Open Questions

- The request said "there are plugins for that" for keeping the tools consistent. This design reads that as "use the Gradle plugins for each tool and configure them to be complementary" (Decision 1). If the intent was instead to use detekt's ktlint wrapper as the only ktlint engine, Decision 1's fallback applies and `tasks.md` section 2 changes accordingly.
