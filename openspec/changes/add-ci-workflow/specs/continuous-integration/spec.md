## ADDED Requirements

### Requirement: Workflow runs on pull requests to main
The repository SHALL contain a GitHub Actions workflow at `.github/workflows/ci.yml` that runs on `pull_request` events targeting the `main` branch, and SHALL NOT be triggered by other branches or event types. When new commits are pushed to a pull request, a still-running workflow for the same pull request SHALL be cancelled.

#### Scenario: PR opened against main
- **WHEN** a pull request targeting `main` is opened, reopened, or receives new commits
- **THEN** the CI workflow runs and reports a `check` and an `assemble` status check on the pull request

#### Scenario: PR targeting another branch
- **WHEN** a pull request targeting a branch other than `main` is opened
- **THEN** the CI workflow does not run

#### Scenario: superseded run is cancelled
- **WHEN** a new commit is pushed to a pull request while an earlier CI run for that pull request is still in progress
- **THEN** the earlier run, including both of its jobs, is cancelled and only the run for the latest commit completes

### Requirement: CI runs the full Gradle check
The workflow SHALL contain a job named `check` that checks out the repository, installs JDK 25, and runs `./gradlew check` so that tests, ktlint and detekt are all executed. The `check` job SHALL fail when any of them fails.

#### Scenario: all checks pass
- **WHEN** a pull request has passing tests and no ktlint or detekt violations
- **THEN** the `check` status check succeeds

#### Scenario: a test fails
- **WHEN** a pull request breaks a unit test
- **THEN** the `check` status check fails

#### Scenario: a lint violation fails the build
- **WHEN** a pull request introduces a ktlint or detekt violation
- **THEN** the `check` status check fails and the violation is shown in the job log

### Requirement: Application build runs in parallel without tests
The workflow SHALL contain a second job named `assemble` that checks out the repository, installs JDK 25, and runs `./gradlew assemble` to build the application without running tests, ktlint or detekt. The `assemble` job SHALL NOT depend on the `check` job (no `needs:`), so the two jobs run in parallel and neither blocks nor masks the other's result.

#### Scenario: jobs run in parallel
- **WHEN** the CI workflow starts for a pull request
- **THEN** the `check` and `assemble` jobs start at the same time and each reports its own status check

#### Scenario: application builds
- **WHEN** a pull request leaves the main sources compiling
- **THEN** the `assemble` status check succeeds and the application jar and distribution archives are built

#### Scenario: build error fails assemble
- **WHEN** a pull request introduces a compile error in the main sources
- **THEN** the `assemble` status check fails

#### Scenario: failing tests do not fail assemble
- **WHEN** a pull request breaks a unit test but the main sources compile
- **THEN** the `assemble` status check succeeds and only the `check` status check fails

#### Scenario: lint violations do not fail assemble
- **WHEN** a pull request introduces a ktlint or detekt violation but the main sources compile
- **THEN** the `assemble` status check succeeds and only the `check` status check fails

### Requirement: Gradle is cached between runs
Both jobs SHALL use the `gradle/actions/setup-gradle` action to restore the Gradle user home cache (dependencies and wrapper distribution). The `check` job SHALL be allowed to write that cache on runs triggered by pull requests; the `assemble` job SHALL restore it read-only so that the parallel jobs do not compete to save the same cache entry. The Gradle wrapper jar SHALL be validated before it is executed.

#### Scenario: warm cache on a later run
- **WHEN** a pull request receives a second commit after a first successful run
- **THEN** both jobs of the second run restore the Gradle cache saved by the first run's `check` job and do not re-download dependencies that are already cached

#### Scenario: cache is written by the check job on pull request runs
- **WHEN** the `check` job of a CI run triggered by a pull request completes
- **THEN** the Gradle cache is saved at the end of that job even though the run is not on the default branch

#### Scenario: assemble does not write the cache
- **WHEN** the `assemble` job completes
- **THEN** it has restored the Gradle cache if one exists and has not saved a cache entry, so the cache saved by `check` is not displaced

#### Scenario: tampered wrapper is rejected
- **WHEN** `gradle/wrapper/gradle-wrapper.jar` does not match a known Gradle release checksum
- **THEN** the workflow fails before running any Gradle build

### Requirement: Marketplace actions over custom scripts
The workflow SHALL use marketplace actions for checkout, JDK setup, Gradle setup and caching, and the pull request coverage comment. The only `run` step in each job SHALL be that job's Gradle invocation; the workflow SHALL NOT contain custom shell scripts for caching, report parsing or commenting.

#### Scenario: workflow steps
- **WHEN** a developer inspects `.github/workflows/ci.yml`
- **THEN** every step is a `uses:` action reference except the single Gradle step in each job (`./gradlew check` in `check`, `./gradlew assemble` in `assemble`)

### Requirement: Test coverage is reported on the pull request
The build SHALL produce a JaCoCo XML coverage report for the `app` module as part of `./gradlew check`, and the `check` job SHALL publish that report on the pull request as a comment showing overall coverage and the coverage of the files changed by the pull request. Subsequent runs on the same pull request SHALL update this comment instead of adding a new one. The coverage comment SHALL NOT cause the build to fail.

#### Scenario: coverage report is generated locally
- **WHEN** a developer runs `./gradlew check`
- **THEN** `app/build/reports/jacoco/test/jacocoTestReport.xml` exists after the run

#### Scenario: coverage comment on first run
- **WHEN** the `check` job completes for a pull request from a branch in the same repository
- **THEN** the pull request has a comment titled "Test coverage" with overall and changed-file coverage

#### Scenario: coverage comment is updated, not duplicated
- **WHEN** a new commit is pushed to a pull request that already has the coverage comment
- **THEN** the existing comment is updated with the new numbers and no second coverage comment is added

#### Scenario: coverage is informational
- **WHEN** coverage is low
- **THEN** the `check` status check is determined only by `./gradlew check` and not by a coverage threshold

#### Scenario: pull request from a fork
- **WHEN** the pull request comes from a fork and the workflow token is read-only
- **THEN** the coverage comment step is skipped, and both jobs still run and determine their status checks

### Requirement: Least-privilege workflow permissions
The workflow SHALL default `GITHUB_TOKEN` permissions to read-only repository contents and SHALL grant `pull-requests: write` only to the `check` job, the job that posts the coverage comment. The `assemble` job SHALL run with read-only contents permission.

#### Scenario: permissions declared
- **WHEN** a developer inspects `.github/workflows/ci.yml`
- **THEN** it declares `contents: read` at workflow level, `pull-requests: write` only on the `check` job, and no extra permissions on the `assemble` job

### Requirement: Tool versions are managed in the version catalog
The JaCoCo engine version SHALL be declared in `gradle/libs.versions.toml` and consumed from the catalog in `app/build.gradle.kts`, with a version that supports the JDK 25 toolchain used by the build.

#### Scenario: version comes from the catalog
- **WHEN** a developer inspects `app/build.gradle.kts`
- **THEN** the JaCoCo `toolVersion` is read from `libs.versions.jacoco` and no version literal appears in the build script
