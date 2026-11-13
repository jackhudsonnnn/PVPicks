# ADR 0001: Start with a modular monolith

- Status: accepted
- Date: 2026-11-11

## Context

The application has tightly related betting, settlement and ledger operations.
The LAN rehearsal should expose actual operational pressure before a split.

## Decision

Build a Java 21 / Spring Boot 3 modular monolith using Gradle. Enforce module
boundaries with Spring Modulith and architecture tests. Keep bets, settlement
and ledger together when their work requires one transaction. Reconsider service
extraction after LAN evidence, not before.

## Consequences

Modules expose deliberate APIs and events rather than reaching into other
modules' internals. Phase 1 must demonstrate boundary tests. Deployment remains
one backend initially; eventual extraction requires another decision.
