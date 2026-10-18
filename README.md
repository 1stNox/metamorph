# Metamorph

Metamorph is a declarative stream-processing service for Apache Kafka and other event streaming platforms that lets teams define multi-stage transformations, enrichments, and database sinks as versioned, testable models, like dbt for real-time data.


## Code quality

[ktlint](https://pinterest.github.io/ktlint/) checks formatting and [detekt](https://detekt.dev/) checks code quality. Both run as part of `check` and fail the build on violations.

```sh
./gradlew check         # run tests, ktlint and detekt
./gradlew ktlintFormat  # auto-fix formatting
```

- Formatting style: [`.editorconfig`](.editorconfig)
- detekt rules: [`config/detekt/detekt.yml`](config/detekt/detekt.yml)

The two tools are configured so that each rule is owned by exactly one of them. ktlint owns formatting; detekt owns code quality, including naming and unused imports. The duplicates are disabled in the configs above, so if you add or enable a rule, check that the other tool does not cover it too.

## Continuous integration

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs on every pull request to `main`. It has two jobs that run in parallel:

- `check` runs `./gradlew check` (tests, ktlint and detekt) and comments the test coverage on the pull request. The comment is skipped for pull requests from forks.
- `assemble` runs `./gradlew assemble` to build the application without running tests or linters.

Both use the Gradle cache; only `check` writes it. Coverage comes from JaCoCo, and locally `./gradlew check` writes the report to `app/build/reports/jacoco/test/`.

The workflow does not block merging on its own. To enforce it, add `check` and `assemble` as required status checks for `main` in the repository settings.
