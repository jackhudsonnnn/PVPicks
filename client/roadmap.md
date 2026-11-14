# Frontend roadmap

The current frontend signs in with provisioned accounts, restores and revokes
backend sessions, reads tables and bets, and connects the U2Pick workflow:
creation, picking and clearing, result calling, host points settlement, and
settle-up receipts with private balance-change reviews. Each table also has a
chat tab with paged history, text and GIF messages, reactions and read
position. Active bets expose
only the viewer's participation and aggregate pick count; after lock,
participant names are loaded from the backend. Tables, members, invitations,
friends, preferences (including device display settings), deleted-table
archives, the guide with feedback, and an admin site-health view are connected.
The mock-store prototype was removed from the source (git tag `prototype-ui`);
the app does not use mock data as a substitute for unavailable server data.

Uncertain actions keep their retry key per user in local storage, shared by
the browser's tabs. Other devices reconcile from server reads. Tables, bets,
chat, receipts and names refresh live from the realtime gateway.

## Next steps

- Integrate additional workflows incrementally with asynchronous API flows.
- Apply server authorization and Active-pick privacy to every integrated view.
- Connect Quick Pick controls and, once the backend supports them, sports-bet
  modes and host overrides.
- Admin tools beyond site health (user audit, moderation, report and feedback
  queues) once the backend has endpoints for them.
- Replace the minimal betting and receipt views with the ticket and table
  designs from the `prototype-ui` tag.
- Bring chat closer to the prototype design (seated table, avatars) and keep
  reactions on older loaded messages current; today a change refreshes the
  newest window and the message you reacted to.
