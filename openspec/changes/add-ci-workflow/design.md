## Context

Metamorph is a Gradle 9.8.1 (Kotlin DSL, version catalog, configuration cache on) project on Kotlin 2.4.10 with a JDK 25 toolchain. Only the `app` module is included in `settings.gradle.kts`. `./gradlew check` already runs the JUnit 6 tests plus ktlint and detekt (see the `add-detekt-ktlint` change). There is no `.github/` directory, so no CI exists, and no coverage tooling: neither JaCoCo nor Kover is applied.

The user asked for: a workflow triggered by pull requests to `main`, Gradle caching, marketplace actions in preference to scripts, and test coverage written to the PR.

Registry check (2026-10-18), latest releases: `actions/checkout` v7.0.1, `actions/setup-java` v6.1.0, `gradle/actions` v6.4.0, `madrapps/jacoco-report` v1.8.0, `mi-kas/kover-report` v2.7, JaCoCo 0.8.15, Kover 0.9.11. The major tags `v7`, `v6`, `v6` and `v1` exist for the first four.

## Goals / Non-Goals

**Goals:**
- Every PR to `main` runs `./gradlew check` on a clean runner and reports pass/fail as the `check` status check.
- In parallel, every PR also builds the application without running tests or linters (`./gradlew assemble`) and reports the `assemble` status check, so build breakage is visible independently of test and lint failures.
- Gradle dependencies, wrapper and build state are cached between runs.
- Test coverage is posted on the PR as one comment that is updated in place on each push.
- The workflow is composed of marketplace actions; the only `run` step is the Gradle invocation.

**Non-Goals:**
- Making the CI jobs mandatory (requires a branch protection rule or ruleset in repository settings, configured by hand).
- Failing the build on a coverage threshold.
- Running on `push` to `main`, scheduled runs, a JDK/OS matrix, release or publish steps, or uploading build artifacts.
- Coverage for modules that are not yet part of the build (`core`, `adapter-kafka`, `architecture-test`, `build-logic`).

## Decisions

### 1. One workflow, two parallel jobs (`check`, `assemble`), triggered by `pull_request` to `main`
`.github/workflows/ci.yml` has the trigger `on: pull_request: branches: [main]` and two jobs on `ubuntu-latest`:
- `check` runs `./gradlew check` (tests, ktlint, detekt, JaCoCo report) and posts the coverage comment.
- `assemble` runs `./gradlew assemble`: it compiles the main sources and builds the jar, start scripts and `distTar`/`distZip`. It runs no tests, no ktlint and no detekt (confirmed with `./gradlew assemble --dry-run`).

Neither job declares `needs:`, so they start at the same time. A concurrency group keyed on the PR (`${{ github.workflow }}-${{ github.ref }}`) with `cancel-in-progress: true` cancels superseded runs of both jobs when new commits are pushed. Top-level `permissions` are `contents: read`; only `check` adds `pull-requests: write`, since only it posts the comment.

- *Why a separate job:* the request is for a build signal that is independent of tests. As separate jobs, a failing test or lint violation turns `check` red but `assemble` still reports whether the application builds, and the two run side by side so total wall time is that of the slower job. `check` already compiles main, so `assemble` adds the packaging tasks (jar, distributions) and that independence, not extra compilation coverage.
- *Why `assemble` and not `build -x test`:* `build` depends on `check`, so excluding only `test` would still run ktlint and detekt. `assemble` is the lifecycle task for "produce the outputs, verify nothing".
- *Job names:* `check` and `assemble` match the Gradle tasks they run. These are the two status checks to require in branch protection.
- *Alternative considered:* a single job running `./gradlew assemble check` sequentially. Rejected: it is not parallel, and a failure in `assemble` would stop `check` from reporting.
- *Alternative considered:* separate `lint`, `test` and `coverage` jobs. Rejected for now; each would repeat JDK and Gradle setup for little gain on this codebase. Revisit when `check` becomes slow.
- *Alternative considered:* `pull_request_target` so fork PRs can also get comments. Rejected: it runs with a write token in the base repo's context while building untrusted code, which is a well-known injection risk.

### 2. Actions: checkout, setup-java, setup-gradle
- `actions/checkout@v7`
- `actions/setup-java@v6` with `distribution: temurin`, `java-version: 25`
- `gradle/actions/setup-gradle@v6`

Pinned to major version tags, as is conventional for first-party and `gradle/` actions. Both jobs use the same three steps, in the same order.

- *Why JDK 25 via setup-java:* the build uses a JDK 25 toolchain with the foojay resolver. Installing JDK 25 up front makes the toolchain resolve locally instead of downloading a JDK on every run, and the daemon JVM matches.
- *Why `setup-gradle` and not `setup-java`'s `cache: gradle`:* it is Gradle's recommended action. Beyond caching the Gradle user home it validates the wrapper, configures the runner for CI use, and writes a job summary of the build. With `cache-provider: basic` (Decision 3) its caching is equivalent to `setup-java`'s, but switching to the `enhanced` provider later adds cache cleanup and fine-grained entries without changing the workflow shape.
- *Wrapper validation:* `setup-gradle` validates `gradle-wrapper.jar` checksums by default, so no separate `wrapper-validation` action is needed.

### 3. Caching: `setup-gradle` with the `basic` provider, written only by `check`
`check` job:
```yaml
- uses: gradle/actions/setup-gradle@v6
  with:
    cache-provider: basic
    cache-read-only: false
```
`assemble` job:
```yaml
- uses: gradle/actions/setup-gradle@v6
  with:
    cache-provider: basic
    cache-read-only: true
```

- *`cache-provider: basic`:* v6 defaults to `enhanced`, which is built on proprietary `gradle-actions-caching` (free for public repositories, "free preview" for private ones). `basic` is MIT-licensed, free for all repositories, and uses the same strategy as `actions/setup-java` with `cache: gradle`: it saves `~/.gradle/caches` and `~/.gradle/wrapper` under one key. It trades some cache efficiency (no fine-grained entries, deduplication or cleanup) for a licensing situation that cannot change under the project. Switching to `enhanced` later is a one-line change.
- *`cache-read-only: false` on `check`:* `setup-gradle` defaults to read-only everywhere except the default branch. A workflow that only triggers on pull requests never runs on the default branch, so with the default no cache would ever be written and caching would be a no-op. Setting it to `false` lets PR runs write their own cache.
- *`cache-read-only: true` on `assemble`:* the `basic` cache key is `setup-java-{OS}-{arch}-gradle-{hash of the build files}`. It does not include the job name, and cache entries are immutable, so two parallel jobs that both write would race for the same key and the first to finish would win. `assemble` is the faster job with fewer dependencies (no test, ktlint or detekt artifacts), so it would usually win and leave `check` restoring a cache that lacks its own dependencies on every later run. Making `assemble` read-only means only `check` writes the full cache and `assemble` restores a superset of what it needs. The cost: `assemble` has a cold cache on the first run of a PR and whenever the build files change, which is cheap because its dependency set is small.
- *Consequence of GitHub's cache scoping:* a cache written by a PR run is only visible to that PR (and its later pushes); other PRs can only restore caches written on `main`. The first run of every new PR is therefore cold. Seeding a shared cache needs a workflow on `push` to `main`, which is a follow-up outside the requested trigger (see Open Questions).
- *Configuration cache:* `org.gradle.configuration-cache=true` is on, but its entries live in the project's `.gradle` directory, which the `basic` provider does not save. In CI the configuration cache is therefore populated per run and not reused across runs. This costs a little configuration time and is the same on both jobs; it is not a correctness issue.

### 4. Coverage tool: the JaCoCo Gradle plugin
Apply `jacoco` (a core Gradle plugin) in `app/build.gradle.kts`, set `jacoco { toolVersion = libs.versions.jacoco.get() }` with the version in `gradle/libs.versions.toml` (`0.8.15`; JDK 25 class files need JaCoCo 0.8.14 or newer), have `test` finalized by `jacocoTestReport`, and enable the XML report (`reports.xml.required = true`). Because the `check` lifecycle task runs `test`, `./gradlew check` writes `app/build/reports/jacoco/test/jacocoTestReport.xml`.

- *Why JaCoCo:* no third-party Gradle plugin, the XML format is the one the PR-comment actions understand best, and it is what most Gradle-on-GitHub examples use. It instruments JVM bytecode, which is fine for Kotlin.
- *Alternative considered:* Kover (`org.jetbrains.kotlinx.kover` 0.9.11), the Kotlin-native tool, with `mi-kas/kover-report`. It understands Kotlin constructs (inline functions, synthetic code) better and can also emit JaCoCo-format XML. Rejected as the default because it adds a third-party Gradle plugin that must track the Kotlin version, and its default engine is not JaCoCo, so JDK 25 support would need its own verification. Reconsider if JaCoCo's Kotlin line-attribution noise becomes a problem.
- *Wiring `finalizedBy` rather than `check`-dependency:* the report is generated even when tests fail, which is also when coverage is least trustworthy but still useful to read. The comment step is gated separately (Decision 5).
- The report is configured in `app/build.gradle.kts` for now; it moves into a `build-logic` convention plugin when a second module exists, in line with the decision in `add-detekt-ktlint`.

### 5. PR comment: `madrapps/jacoco-report`
```yaml
- uses: madrapps/jacoco-report@v1
  if: ${{ !cancelled() && github.event.pull_request.head.repo.full_name == github.repository }}
  with:
    paths: app/build/reports/jacoco/test/jacocoTestReport.xml
    token: ${{ secrets.GITHUB_TOKEN }}
    title: Test coverage
    update-comment: true
    min-coverage-overall: 0
    min-coverage-changed-files: 0
```

- *Why this action:* it posts overall coverage and per-changed-file coverage from a JaCoCo XML file, supports updating a single comment (`update-comment: true`, which requires `title`), and runs on `node24`. It adds no step logic of our own.
- *No thresholds:* `min-coverage-*` default to 80, which only affects the pass/fail emoji and not the build, but 80 would show red on a two-file sample project. Set to `0` so the comment is informational until the team picks real numbers. The job does not fail on coverage (a non-goal).
- *`continue-on-error` stays at the action's default of `true`:* a failed comment must not fail the PR check.
- *Job placement:* this step lives only in the `check` job, which produces the report. The `assemble` job has no report and no comment step.
- *Fork PRs:* a `pull_request` run from a fork receives a read-only `GITHUB_TOKEN`, so the comment step could not post. The `if:` condition skips it for forks; both jobs still run. The job summary (`comment-type: both`) is not used: `setup-gradle` already writes a build summary and the comment is the requested output.
- *Why `!cancelled()` rather than `success()`:* the report is also produced when tests fail, and seeing coverage next to a red build is useful. If the report file is missing (build failed before tests), the action logs a warning and does not fail (default `continue-on-error`).

### 6. Commands run: `./gradlew check` and `./gradlew assemble`
Each job has exactly one `run` step. `check` runs `./gradlew check`, reusing the lifecycle task that already includes tests, `ktlintCheck` and `detektMain`/`detektTest`. `assemble` runs `./gradlew assemble`, which produces the application jar and `distTar`/`distZip` without running verification. No `--no-daemon`, since `setup-gradle` configures the runner for CI use. `check` is used rather than `build` in the first job because `build` would also assemble distributions, which the separate `assemble` job already covers.

The assembled outputs are not uploaded as artifacts (a non-goal); the job only proves the application builds.

## Risks / Trade-offs

- **[First run of every PR has a cold Gradle cache]** → Accepted given the requested trigger. Mitigation is the optional `push: main` follow-up that seeds the shared cache.
- **[`basic` cache is less efficient than `enhanced`]** → Accepted for MIT licensing; one-line change if CI time becomes a problem.
- **[Parallel jobs race for the same `basic` cache key]** → Only `check` writes the cache; `assemble` is read-only (Decision 3). If a future job needs to write, give it its own key or move to the `enhanced` provider, whose keys are per job.
- **[`assemble` is cold on the first run and whenever build files change]** → Accepted; its dependency set is small and it finishes well before `check`.
- **[Two jobs use two runners and repeat JDK and Gradle setup]** → Accepted; it is the cost of the requested parallelism. `check` already compiles main, so `assemble` adds the packaging tasks and independence from test/lint failures rather than extra compile coverage.
- **[JaCoCo on JDK 25 / Kotlin 2.4.10]** → JaCoCo 0.8.15 supports Java 25 class files. Verified during implementation by running `./gradlew check` and inspecting the XML report; fall back to Kover if instrumentation fails.
- **[`madrapps/jacoco-report` is a third-party action running with `pull-requests: write`]** → Pinned to a major tag from a widely used project, token scoped to the `check` job only. Optionally pin to a commit SHA later. The workflow-level default permission is `contents: read` only, which `assemble` inherits.
- **[Fork PRs get no coverage comment]** → Documented; both jobs still run and gate the PR. Avoiding `pull_request_target` is deliberate (Decision 1).
- **[Workflow cannot be tested locally]** → It is first exercised by the PR that adds it. Mitigate by validating the YAML syntax locally and checking the action inputs against each action's `action.yml` (done while writing this design), then reviewing the first PR run.
- **[Required status checks are not configured by this change]** → Without a branch protection rule on `main`, a red CI run does not block merging. Called out in the proposal as a manual step. Both `check` and `assemble` must be listed, or a failure in the unlisted one will not block.

## Migration Plan

1. Add the JaCoCo version to the catalog and configure the plugin in `app/build.gradle.kts`.
2. Verify locally: `./gradlew check` succeeds and writes `app/build/reports/jacoco/test/jacocoTestReport.xml`, and `./gradlew assemble` succeeds without running tests.
3. Add `.github/workflows/ci.yml` with the `check` and `assemble` jobs.
4. Open a PR against `main`; confirm both jobs run in parallel and pass, only `check` saves the cache, and the coverage comment appears and is updated (not duplicated) on a second push.
5. In repository settings, add `check` and `assemble` as required status checks on `main` (manual).

Rollback: delete `.github/workflows/ci.yml` and revert the `app/build.gradle.kts` and catalog edits. No runtime impact.

## Open Questions

- Should a `push: [main]` trigger be added so merged builds seed a shared Gradle cache that every PR can restore? It would make first runs on new PRs warm. Out of scope here because the request names only the PR trigger.
- Is the repository public or private? It only matters if the project later switches the cache provider to `enhanced`.
