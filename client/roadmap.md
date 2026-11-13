# Frontend roadmap

The current frontend signs in with provisioned accounts, restores and revokes
backend sessions, reads tables and bets, and connects the U2Pick workflow:
creation, picking and clearing, result calling, host points settlement, and
settle-up receipts with private balance-change reviews. Each table also has a
chat tab with paged history, text and GIF messages, reactions and read
position. Active bets expose
only the viewer's participation and aggregate pick count; after lock,
participant names are loaded from the backend. The former mock-backed table,
social and settlement UI remains in the source but is not exposed; the app
does not use mock data as a substitute for unavailable server data.

Uncertain actions keep their retry key per user in local storage, shared by
the browser's tabs. Other devices reconcile from server reads. Tables, bets,
chat, receipts and names refresh live from the realtime gateway.

## Next steps

- Integrate additional workflows incrementally with asynchronous API flows.
- Apply server authorization and Active-pick privacy to every integrated view.
- Connect table management (creation, members, invitations, settings) and
  social views, preserving reconciliation and accessible interactions.
- Replace the minimal betting and receipt views with the prototype's ticket
  and table designs.
- Bring chat closer to the prototype design (seated table, avatars) and keep
  reactions on older loaded messages current; today a change refreshes the
  newest window and the message you reacted to.
