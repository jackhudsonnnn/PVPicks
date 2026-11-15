# References

Detailed references behind the guides. The OpenAPI file is a draft contract:
it includes planned operations, so it isn't proof an endpoint exists.

## Working references

- [Domain rules and test inventory](/reference/domain-rules): the business rules, invariants and lock order.
- [API behavior notes](/reference/api-notes): per-endpoint behavior, retries and known limits.
- [Realtime contract](/reference/realtime-contract): WebSocket destinations, authorization and recovery.
- [Test guide](/reference/test-guide): what each test suite covers and how to run it.
- <a href="/docs/source/openapi.yaml" download>OpenAPI draft</a> (download): the HTTP contract, implemented and planned.

## Architecture decision records

- [0001 Modular monolith](/adr/0001-modular-monolith)
- [0002 Transactional outbox](/adr/0002-transactional-outbox)
- [0003 Redis as cache only](/adr/0003-redis-cache-only)
- [0004 Sports feed](/adr/0004-sports-feed)
- [0005 Sports data model, ingestion and replay](/adr/0005-sports-data-model)
- [ADR template](/adr/0000-template)
