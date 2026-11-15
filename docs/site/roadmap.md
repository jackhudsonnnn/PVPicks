# Project roadmap

What's left, by area. See [Project status](/status) for what's built. Work
proceeds in parallel where dependencies allow; nothing here has a date.

## Betting

- **Next:** the player-stat modes (Either Or, Prop Hunt, King Of The Hill) on
  the stored player stats. One product decision is open first: whether
  "touchdowns" counts return, fumble and interception touchdowns
  ([Player stats](/player-stats)).
- Quick Pick subscriptions and copying results between tables; end-to-end
  debt-cap enforcement across copies.
- Maybe: a host setting for who may override sports results; showing
  host-changed outcomes in the settlement message.
- Every mode reuses the shared pick, privacy, cap, lock, payout and
  settlement transactions; only the feed supplies sports outcomes.

## Sports data

- **Buy All-Star for NBA and NFL before the GOAT trial ends (about Nov 20).**
  Check BallDontLie's terms for showing and storing their data first. The
  switch itself is configuration only ([Player stats](/player-stats)).
- Make the plan a per-league setting that drives poll rate, endpoints and
  offered modes (ADR 0005).
- Check the recordings: how fast stats update, how a player who didn't play
  appears, corrections after Final, and NFL status and quarter handling on a
  live Sunday.
- Cut chunks with player stats (`scripts/cut-sports-chunk.py`), and add player
  stats to the demo games.

## Social and tables

- An archive data cleanup policy, and revoking a single session ("sign out of
  other devices"). Freezing and deleting an account already end every
  session.
- Keep reactions on older loaded chat messages current. Today a change
  refreshes the newest window and the message you reacted to.

## Delivery

- Ordering and gap detection beyond refetch-on-reconnect, and durable
  consumers that record processed events.

## Documentation

- An interactive API reference, once request execution and access safeguards
  are defined. Finish the API contract review.

## Security, operations and launch

- Rate limits, secure cookies, TLS and other protections before LAN.
- Retention and rotation for retry records and audit data.
- Backups, recovery and observability before production.
- Until then the dev environment is disposable. Before LAN, switch to
  preserving data and approving destructive operations.
