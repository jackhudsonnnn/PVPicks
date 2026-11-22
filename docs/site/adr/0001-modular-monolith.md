# ADR 0001: Start with a modular monolith

- Status: accepted
- Date: 2026-11-11

## Context

The application has tightly related betting, settlement and ledger operations.
The LAN rehearsal should expose actual operational pressure before a split.

## Decision

Build a Java 21 / Spring Boot 3 modular monolith using Gradle. Each feature is
a Spring Modulith module implemented as a package in the single `app` project;
its public types are its API and its `package-info.java` declares allowed
dependencies. Enforce these boundaries with Spring Modulith and architecture
tests. Keep bets, settlement and ledger together when their work requires one
transaction. Split modules into separate Gradle projects only when compile-time
isolation is worth the build cost, and reconsider service extraction after LAN
evidence, not before.

## Consequences

Modules expose deliberate APIs and events rather than reaching into other
modules' internals. Database tables are not yet partitioned by module: queries
may read or lock other modules' tables within one transaction, and must move
behind module APIs before any extraction. Deployment remains
one backend initially; eventual extraction requires another decision.
