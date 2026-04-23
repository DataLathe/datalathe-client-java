# Contributing to datalathe-client-java

Thanks for your interest in contributing! This is the Java client for the [Datalathe](https://datalathe.com) API.

## Getting set up

You need Java 18 or newer and Maven.

```bash
git clone https://github.com/<your-fork>/datalathe-client-java.git
cd datalathe-client-java
mvn clean install
```

Run the test suite:

```bash
mvn test
```

Run the dependency security scan:

```bash
mvn dependency-check:check
```

## Supported Java versions

Java 18 and 21. CI runs the test suite against both — please make sure your change works on both.

## Making a change

1. Fork the repo and create a branch off `main`.
2. Make your change. Add or update tests under `src/test/java/` to cover it.
3. Run `mvn test` locally and confirm everything passes.
4. Open a PR against `DataLathe/datalathe-client-java:main`. CI will run automatically.

### Style

- Match the surrounding code. Public API lives under `com.datalathe.client`; keep new public classes there only if they are intended for external use.
- The codebase uses Lombok — if you touch data classes, prefer `@Value` / `@Builder` over hand-written boilerplate.
- Prefer small, focused PRs. If a change touches more than one area, split it.

### Commit messages

Short imperative subject line (e.g. `Add retry support to DatalatheClient`). Reference issues with `Fixes #123` in the body when applicable.

## Reporting bugs

Open an issue with:

- What you ran (minimal reproducing snippet preferred)
- What you expected to happen
- What actually happened (including the full exception + stack trace if any)
- `datalathe-client` version and Java version (`java -version`)

## Releases

Releases are cut by the maintainers — contributors don't need to do anything release-related as part of a PR.

## License

By contributing, you agree that your contributions will be licensed under the project's MIT License.
