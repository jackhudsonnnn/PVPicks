# Frontend roadmap

The current frontend is a read-only backend preview: it signs in with provisioned
accounts, restores and revokes backend sessions, and reads current tables and
their bets. Active bets expose only the viewer's participation and aggregate
pick count; after lock, participant names are loaded from the backend.
The former mock-backed betting, chat, social and settlement UI remains in the
source but is not exposed by the current entry point. Betting mutations and
receipt actions remain gated; this preview does not use mock data as a
substitute for unavailable server data.

A backend-backed receipt and durable-review preview exists only in tests.
It covers group and individual actions, review-before-send enforcement, and
same-tab retry of uncertain actions. It is not exposed in the normal app and
does not provide cross-device retry recovery.

A separate test-only U2Pick preview connects creation, picking and clearing.
It preserves Active-pick privacy and uses the same same-tab retry mechanism.
Result calling and points settlement are not connected in this preview.

## Next steps

- Integrate additional workflows incrementally with asynchronous API flows.
- Apply server authorization and Active-pick privacy to every integrated view.
- Restore chat, betting and settlement views as they become server-backed,
  preserving paging, unread state, reconciliation and accessible interactions.
- Complete and validate the gated betting lifecycle and safe retry recovery
  before exposing settlement and betting mutations.
