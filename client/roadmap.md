# Frontend roadmap

The current frontend signs in with provisioned accounts, restores and revokes
backend sessions, reads tables and bets, and connects the U2Pick workflow:
creation, picking and clearing, result calling, host points settlement, and
settle-up receipts with private balance-change reviews. Active bets expose
only the viewer's participation and aggregate pick count; after lock,
participant names are loaded from the backend. The former mock-backed chat,
social and settlement UI remains in the source but is not exposed; the app
does not use mock data as a substitute for unavailable server data.

Uncertain actions keep their retry key per user in local storage, shared by
the browser's tabs. Other devices reconcile from server reads.

## Next steps

- Integrate additional workflows incrementally with asynchronous API flows.
- Apply server authorization and Active-pick privacy to every integrated view.
- Restore chat and social views as they become server-backed,
  preserving paging, unread state, reconciliation and accessible interactions.
- Replace the minimal betting and receipt views with the prototype's ticket
  and table designs.
- Refresh bets and reviews from realtime events once the outbox relay and
  gateway exist; until then views refresh on demand.
