# TODO

## Now: live sports data this weekend

- [ ] Get the BallDontLie feed ready for NBA games over the next few nights and
      NFL on Sunday (Nov 15), plus Monday night:
  - [x] Read `status_state` (scheduled, in_progress, final, postponed, …)
        instead of guessing from the free-text `status`.
  - [x] Handle the NFL response having no clock or quarter field: take the
        period from the quarter scores.
  - [x] Make late games work whichever date `dates[]` uses: one request asks
        for yesterday, today and tomorrow. Confirmed: NBA uses the Eastern
        date, NFL the UTC date.
  - [x] Sample every All-Star and GOAT endpoint once (in
        `recordings/sports/samples/`). Still to sample: NFL player props for a
        week 10 Sunday game, once they are posted.
  - [x] Stay within the free tier's 5 requests per minute, including manual
        testing.
  - [x] Save every raw response (dev only, git-ignored), so the weekend
        becomes replay data.
  - [x] Set `PVPICKS_BALLDONTLIE_API_KEY` in `infra/compose/.env` (GOAT trial,
        both leagues, 5 requests/min).
  - [x] Watch the ticker during a live NBA game (Nov 13): live scores and
        clocks update through realtime, live games first. NBA `time` includes
        the quarter ("Q1 11:26").
  - [ ] Watch NFL on Sunday: live status labels, quarter from scores, the
        Sunday night game listed under Monday's UTC date.
  - [ ] After Sunday: check the recordings for anything odd (status changes,
        corrections after Final, postponements) and start the replay feed.

## Next

- [ ] Replay saved games as the dev data stream, and keep a few trimmed games
      as test fixtures.
- [x] Try the whole app in a real browser on an isolated stack: WebSocket
      proxy, chat, ticker and live updates. (Repeat once a key is set, to see
      the ticker with real games.)
- [ ] Housekeeping: commit AGENTS.md, update `bun.lock`.

## Later

- [ ] Sports bets: proposal checks, then locking and results from the feed
      once its data is verified, with the automatic outcomes and host
      overrides in the domain rules ("Sports outcomes and host overrides").
- [ ] Sports data per ADR 0005: game history, rosters, player stats, per-game
      replay slices.
- [ ] Buy All-Star for NBA and NFL (decided) before the GOAT trial ends
      (about Nov 20). Check BallDontLie's terms for showing and storing their
      data in the app first. Build nothing on GOAT-only endpoints (odds,
      sportsbook props, play-by-play, box scores, NFL rosters).
- [ ] After buying: set the plan to All-Star for both leagues and shorten
      the poll interval (60 requests/min allows polling every 15–20 s).
- [ ] Make the plan a per-league setting that drives poll rate, endpoints and
      offered modes (ADR 0005).
- [ ] Maybe: a host setting for who may override sports results, like the
      leftover-cents rule.
- [ ] Maybe: show host-modified outcomes in the settlement message (details
      popup).
- [ ] Table management and social screens: create tables, invitations,
      members, settings, follows and blocks.
- [ ] Quick Pick subscriptions and copying results between tables.
- [ ] Before opening it up beyond this machine: TLS, secure cookies, rate
      limits, session revocation, account deletion, backups and monitoring.
