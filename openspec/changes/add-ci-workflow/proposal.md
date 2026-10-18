## Why

Nothing verifies a pull request before it reaches `main`: tests, ktlint and detekt only run if the author remembers to run `./gradlew check` locally, and reviewers have no view of how well the change is covered by tests. A CI workflow on every PR to `main` makes the existing checks mandatory and surfaces coverage in the PR itself.

## What Changes

- Add a GitHub Actions workflow (`.github/workflows/ci.yml`) triggered by `pull_request` events targeting `main`. It has two jobs that run in parallel, each checking out the code and setting up JDK 25:
  - `check` runs `./gradlew check` (tests, ktlint, detekt) and fails if any of them fail.
  - `assemble` runs `./gradlew assemble` to build the application (jar and distribution archives) without running tests or linters, so a PR with failing tests still shows whether the application builds.
- Cache Gradle with the official `gradle/actions/setup-gradle` action (dependency, wrapper and build-cache handling) instead of hand-written `actions/cache` steps. Only the `check` job writes the cache; `assemble` restores it read-only.
- Add the JaCoCo Gradle plugin (built into Gradle, no third-party plugin) to the `app` module so `./gradlew check` produces an XML coverage report after the tests run.
- Post the coverage result to the PR as a single, updated-in-place comment using the `madrapps/jacoco-report` marketplace action, reading the JaCoCo XML report. The comment shows overall coverage and coverage of the files the PR changes.
- Prefer marketplace actions throughout (`actions/checkout`, `actions/setup-java`, `gradle/actions/setup-gradle`, `madrapps/jacoco-report`); the workflow contains no custom shell scripts beyond the `./gradlew` invocation.
- Pin the JaCoCo engine version in `gradle/libs.versions.toml`; the plugin itself ships with Gradle, so it needs no plugin version.

## Capabilities

### New Capabilities
- `continuous-integration`: A GitHub Actions workflow that validates every pull request to `main` by running the full Gradle `check` and, in parallel, building the application without tests, with Gradle caching, and reports test coverage on the pull request.

### Modified Capabilities
<!-- None: openspec/specs/ is empty, so there are no existing requirements to change. -->

## Impact

- **New files**: `.github/workflows/ci.yml`.
- **Build files**: `app/build.gradle.kts` (apply the `jacoco` plugin, configure the XML report, run it after `test`), `gradle/libs.versions.toml` (JaCoCo engine version).
- **Repository settings (manual, outside the diff)**: to make the workflow block merges, `main` needs a branch protection rule or ruleset requiring the `check` and `assemble` status checks. The workflow alone cannot enforce that.
- **Permissions**: the workflow needs `contents: read` everywhere and `pull-requests: write` on the `check` job only (for the coverage comment). Pull requests from forks get a read-only token, so the coverage comment is skipped for them while both jobs still run.
- **CI cost**: two jobs means two runners per PR run and a second JDK and Gradle setup; `assemble` is small, so it finishes well before `check`.
- **Developer workflow**: `./gradlew check` now also writes `app/build/reports/jacoco/test/jacocoTestReport.xml` and an HTML report locally; the run gets slightly slower.
- **Out of scope**: coverage thresholds that fail the build, publishing releases or artifacts, running on `push` to `main`, and a build matrix.
