# Metamorph

Metamorph is a declarative stream-processing service for Apache Kafka and other event streaming platforms that lets teams define multi-stage transformations, enrichments, and database sinks as versioned, testable models, like dbt for real-time data.

## Development

### Module layout

Metamorph uses a hexagonal ("ports and adapters") layout, enforced by Gradle modules:

| Module | Package root | Contents |
|---|---|---|
| `core` | `dev.reichling.core` | Domain model (`core.model`) and ports (`core.port`). No third-party dependencies. |
| `adapter-kafka` | `dev.reichling.adapter.kafka` | Kafka implementation of the consumer port. Depends on `core` and `kafka-clients`. |
| `app` | `dev.reichling.app` | Composition root: the only place that knows which adapters are used. |
| `architecture-test` | `dev.reichling.architecture` | Test-only module with the architecture rules. |

The dependency rule: adapters depend on `core`, and `core` depends on nothing. Each new platform gets its
own `adapter-<name>` module. Shared build setup (Kotlin/JVM toolchain, JUnit, ktlint, detekt, JaCoCo) lives in
the `build-logic` convention plugin (`metamorph.kotlin-jvm`), so every module gets it.

### Architecture tests

`architecture-test` uses [Konsist](https://docs.konsist.lemonappdev.com/) to check the production sources of
every module, including modules added later (no wiring needed). The rules cover:

- the dependency direction between layers (Model, Port, Adapter, App)
- `core` imports only from `kotlin`, `java` and `dev.reichling.core`
- adapters do not import each other
- port conventions: only interfaces and `*Exception` classes, with KDoc
- adapter conventions: classes implementing a port live in `dev.reichling.adapter..` and end with `Adapter`
- the public API of an adapter exposes no platform types (`internal` is exempt)
- no layer is empty, so a package rename cannot make a rule pass vacuously

When you add an adapter, register its package and the package prefixes of its platform in
`Architecture.adapterPlatforms` (`architecture-test/src/test/kotlin/dev/reichling/architecture/Architecture.kt`).
Until then the architecture tests fail.

### Running the tests

```sh
./gradlew test             # unit and architecture tests; no Docker needed
./gradlew integrationTest  # Kafka integration tests against a real broker in a container
./gradlew check            # everything
```

### Integration tests with Podman

The integration tests start a Kafka broker with [Testcontainers](https://testcontainers.com/), which expects a
Docker-compatible API. If `docker` on your machine is Podman, point Testcontainers at the Podman socket and
disable Ryuk (the cleanup sidecar, which does not work with Podman out of the box):

```sh
export DOCKER_HOST=unix://$XDG_RUNTIME_DIR/podman/podman.sock   # rootless; needs `systemctl --user enable --now podman.socket`
export TESTCONTAINERS_RYUK_DISABLED=true
./gradlew integrationTest
```

Instead of `DOCKER_HOST` you can set `docker.host=unix:///run/user/<uid>/podman/podman.sock` in
`~/.testcontainers.properties`.

The broker container is removed when the test JVM exits normally. If the JVM is killed, a container can be left
behind. It is harmless, because every test uses its own topic and consumer group, but you can remove it:

```sh
podman ps --filter label=org.testcontainers
podman rm -f <container id>
```

## Code quality

[ktlint](https://pinterest.github.io/ktlint/) checks formatting and [detekt](https://detekt.dev/) checks code quality. Both run as part of `check` in every module and fail the build on violations.

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

Both use the Gradle cache; only `check` writes it. Coverage comes from JaCoCo, and locally `./gradlew check` writes a report per module to `<module>/build/reports/jacoco/test/`.

The workflow does not block merging on its own. To enforce it, add `check` and `assemble` as required status checks for `main` in the repository settings.
