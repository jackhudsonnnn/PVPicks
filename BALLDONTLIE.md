# TODO

**Paused 2026-11-14 for a client cleanup (done: `client/src` is now organized
by feature).** Sports work is at the end of step 3:
score modes (Total Disaster, Give And Take) work end to end with feed results
and host overrides; game history, players and player stats are stored. Next:
the player modes, then the replay feed. A real Give And Take bet (DEN @ NOP,
Game Night) should settle tonight as the first live check. Nothing from
2026-11-14 is committed yet.

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
  - [x] Record player box scores during live games and for six hours after
        they start (one request every 20 s, rotating through games), so the
        player modes have real data to replay.
  - [ ] Check the first stats recordings (Saturday night NBA): how fast stats
        update, how a player who didn't play appears, corrections after Final.
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

- [x] Tables, members, invitations and Friends (follow, block, search) in the
      app.
- [x] Preferences: who can add you, username, color and debt cap.

- [ ] Replay saved games as the dev data stream, and keep a few trimmed games
      as test fixtures.
- [x] Try the whole app in a real browser on an isolated stack: WebSocket
      proxy, chat, ticker and live updates. (Repeat once a key is set, to see
      the ticker with real games.)
- [ ] Housekeeping: update `bun.lock`.
- [x] Port the prototype design onto the real app: tickets, proposal sheet,
      app shell, felt table page, chat, Peers, Host/About, profiles, Tickets
      page, reports and live tomatoes.

## Later

- [ ] Sports modes, built from the providers' OpenAPI specs and sample
      responses while recordings come in (rules decided 2026-11-14; see the
      domain rules, "Sports bet rules"):
  - [x] Save dated copies of both specs; contract tests for recordings
        (found two NFL spec deviations: nulls, decimal averages).
  - [x] Sports data per ADR 0005: game history with `status_state`, points
        per period, players (daily active list) and player stats with history.
  - [ ] Per-game replay slices and the replay feed.
  - [x] Shared sports-bet lifecycle (game binding, live and pregame pick
        windows, feed results with the automatic outcomes) and the host
        override.
  - [x] Total Disaster and Give And Take (scores only).
  - [ ] Watch a real score bet settle tonight (NBA) and Sunday (NFL).
  - [ ] Either Or, Prop Hunt and King Of The Hill (player stats). Choose
        Their Fate and Scorcerer need GOAT play-by-play and stay "Coming
        soon".
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
- [ ] Quick Pick subscriptions and copying results between tables.
- [ ] Before opening it up beyond this machine: TLS, secure cookies, rate
      limits, session revocation, account deletion, backups and monitoring.
