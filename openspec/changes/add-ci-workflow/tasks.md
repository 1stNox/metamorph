## 1. JaCoCo coverage in the build

- [x] 1.1 Add `jacoco = "0.8.15"` to `[versions]` in `gradle/libs.versions.toml`
- [x] 1.2 Apply the `jacoco` plugin in `app/build.gradle.kts` and set `jacoco { toolVersion = libs.versions.jacoco.get() }`
- [x] 1.3 Configure `jacocoTestReport` to write the XML report (`reports.xml.required = true`) and make the `test` task `finalizedBy` it
- [x] 1.4 Run `./gradlew check` and confirm it passes and `app/build/reports/jacoco/test/jacocoTestReport.xml` exists with non-empty coverage for `dev.reichling` (if JaCoCo cannot instrument JDK 25 classes, fall back to Kover per design Decision 4)
- [x] 1.5 Run `./gradlew check` a second time and confirm the configuration cache is reused with no new problems from the JaCoCo plugin
- [x] 1.6 Run `./gradlew assemble` and confirm it succeeds, builds the jar and `distZip`/`distTar` under `app/build`, and runs no test, ktlint or detekt task (`./gradlew assemble --dry-run` lists only compile, jar, start-script and dist tasks)

## 2. CI workflow

- [x] 2.1 Create `.github/workflows/ci.yml` with `on: pull_request: branches: [main]`, a concurrency group per PR with `cancel-in-progress: true`, and top-level `permissions: contents: read`
- [x] 2.2 Add the `check` job on `ubuntu-latest` with job-level `permissions: contents: read` and `pull-requests: write`, and steps `actions/checkout@v7`, `actions/setup-java@v6` (`distribution: temurin`, `java-version: 25`), and `gradle/actions/setup-gradle@v6` with `cache-provider: basic` and `cache-read-only: false`
- [x] 2.3 Add the `./gradlew check` run step to the `check` job
- [x] 2.4 Add the `madrapps/jacoco-report@v1` step to the `check` job with `paths: app/build/reports/jacoco/test/jacocoTestReport.xml`, `token: ${{ secrets.GITHUB_TOKEN }}`, `title: Test coverage`, `update-comment: true`, `min-coverage-overall: 0`, `min-coverage-changed-files: 0`, and `if: ${{ !cancelled() && github.event.pull_request.head.repo.full_name == github.repository }}`
- [x] 2.5 Add the `assemble` job on `ubuntu-latest` with no `needs:` and no extra permissions (inherits `contents: read`), and the same `actions/checkout@v7` and `actions/setup-java@v6` steps
- [x] 2.6 Add `gradle/actions/setup-gradle@v6` to the `assemble` job with `cache-provider: basic` and `cache-read-only: true`, then the `./gradlew assemble` run step
- [x] 2.7 Confirm every input name used above exists in each action's `action.yml` at the pinned major version, and that each job has exactly one `run` step
- [x] 2.8 Validate the workflow YAML locally (for example `actionlint` if installed, otherwise a YAML parse) and fix any errors

## 3. Documentation

- [x] 3.1 Add a short "Continuous integration" section to `README.md`: what triggers the workflow, the two parallel jobs (`check` runs `./gradlew check`, `assemble` runs `./gradlew assemble`), where the coverage report lands locally, and a note that `check` and `assemble` must both be set as required status checks in the repository settings for them to block merges

## 4. Verify on GitHub

- [ ] 4.1 Open a PR against `main` containing this change and confirm `check` and `assemble` start at the same time and both pass
- [ ] 4.2 Confirm the "Test coverage" comment appears with overall and changed-file coverage
- [ ] 4.3 Push a second commit to the PR and confirm the same comment is updated (not duplicated), `check` restores and saves the Gradle cache, and `assemble` restores it without saving (the setup-gradle job summaries show this)
- [ ] 4.4 Push a temporary commit that breaks a unit test and confirm `check` fails while `assemble` still passes; then revert it
- [ ] 4.5 In repository settings, add `check` and `assemble` as required status checks on `main` (manual, outside the diff)
