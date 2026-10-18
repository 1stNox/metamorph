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
