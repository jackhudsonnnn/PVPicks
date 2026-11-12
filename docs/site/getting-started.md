# Getting started

Get oriented without changing shared data or exposing local credentials.

## Prerequisites

- Java 21 and the project Gradle wrapper.
- Node.js 22.12 or newer and npm for the documentation build.
- Docker and Docker Compose for infrastructure integration tests.

The codebase contains a Spring Boot backend in `backend/`, a separate TanStack
prototype in `client/`, and documentation in `docs/`. Java classes in the
backend app's organizational folders still share the `com.p2picks.app` package.

## Build the documentation

From the project root:

```sh
npm ci --prefix docs
npm --prefix docs run build
```

The backend Gradle resource build also builds the site; it requires those npm
dependencies to be installed first. The dev backend serves the bundled site at
`http://127.0.0.1:8080/docs/` when running with the default port. Its contents
are rebuilt into the application artifact; edits are not live in a running jar.
Documentation sources and downloadable references are explicitly allowlisted
in `docs/scripts/build.mjs`; add new reference files there when needed.

## Run focused backend tests

From `backend/`, using an installed Java 21:

```sh
./gradlew :app:test
```

Integration tests use disposable Testcontainers and require Docker. The
<a href="/docs/source/backend-readme.md" download>backend setup notes</a> cover local JDK selection,
provisioning, configuration, and the existing dev stack. They include
operations that affect local services: inspect and obtain approval before
running them against a shared environment.

::: danger Preserve data
Never run `docker compose down -v`, reset a database, or regenerate existing
credentials as a routine setup step. Confirm the target and obtain explicit
approval before any destructive operation.
:::

## Frontend prototype

From `client/`, follow its README and package scripts. Its browser-local data
and demo accounts are not connected to the backend. Do not copy demo credentials
or local environment files into documentation, issue reports, or source code.

## Authentication for API development

The dev backend uses an HttpOnly session cookie, same-origin checks, and a CSRF
token. Read the backend setup notes for the exact flow. LAN and production
profiles remain intentionally gated.
