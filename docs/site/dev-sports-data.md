# Dev sports data

Test sports bets without waiting for a real game. The dev backend can feed
made-up or replayed games into the same pipeline the live feed uses, so bets
on them lock, resolve, settle and show up in the ticker exactly like real
ones. Dev profile only.

There are two kinds of source:

- **Scenario files:** small hand-written JSON files you edit while they run.
  Change a score, a stat or the status, save, and the next tick (every 2 s)
  picks it up. Steps can also script changes over time.
- **Recorded chunks:** short stretches (about 20 minutes) cut from real
  recordings, replayed at 0.5× to 8× with pause and restart. Each has a title
  and a description saying what happens in it.

## Using it

Open **Dev data** with the flask icon beside the logo; it appears only when the
backend has dev data enabled. Each scenario and chunk is listed with its title, description and
league, plus Start, Stop and Restart; chunks also have Pause and speed. A
running source shows its games and players, or what's wrong with the file
(the last good state stays in place).

Running sources add **dev games** next to the real ones:

- Ids are `NBA-DEV-<source>-<game>` or `NFL-DEV-<source>-<game>`.
- They're marked **DEV** in the ticker and the proposal sheet's game picker.
- Start times are relative to when you pressed Start, so they show up today.

Real games keep polling, so a real bet still settles while you test. Dev games
never mark the real feed healthy, but they still show when the real feed is
down (for example with no API key).

Stopping a source leaves its games at their last state. Restart replays it
from the beginning, and run state doesn't survive a backend restart. A
typical check: start a scenario, propose a bet on its game, pick, let it lock,
then edit the file to finish the game and watch the bet resolve (score bets
wait 20 minutes after Final, as live).

## Where the files live

| Path | What | In git |
|---|---|---|
| `dev/sports/scenarios/<name>.json` | Scenario files | Yes |
| `dev/sports/chunks/<name>/chunk.json` and `responses/` | Recorded chunks | No: cut from provider data, like the recordings |
| `recordings/sports/<league>/<New York date>/` | Raw recordings chunks are cut from | No |

Compose mounts `dev/sports` read-only at `/dev-sports` and sets
`PVPICKS_DEV_SPORTS_DIR`. Leave it unset to turn dev data off.
`PVPICKS_DEV_SPORTS_TICK` sets the tick (default `PT2S`). Names are lowercase
letters, digits, `-` and `_`, because they become part of game ids.

## Scenario files

```json
{
  "title": "NFL: late touchdown, then Final",
  "description": "What happens and what it's good for.",
  "league": "NFL",
  "games": [
    {
      "id": "g1",
      "startsInMinutes": -150,
      "status": "LIVE",
      "period": 4,
      "clock": "2:10",
      "home": { "abbreviation": "DET", "name": "Lions", "city": "Detroit",
                "score": 17, "periods": [7, 3, 7, 0] },
      "away": { "abbreviation": "GB", "score": 20, "periods": [0, 14, 3, 3] }
    }
  ],
  "players": [
    { "id": "wr1", "game": "g1", "team": "DET", "firstName": "Dev",
      "lastName": "Receiver", "position": "WR",
      "stats": { "receiving_yards": 71, "receptions": 6, "touchdowns": 0 } }
  ],
  "steps": [
    { "afterSeconds": 60,
      "games": [{ "id": "g1", "home": { "score": 24 } }],
      "players": [{ "id": "wr1", "stats": { "touchdowns": 1 } }] },
    { "afterSeconds": 120, "games": [{ "id": "g1", "status": "FINAL" }] }
  ]
}
```

- **Games:**
  - `status` is `SCHEDULED`, `LIVE`, `FINAL` or `POSTPONED`.
  - `providerState` is optional, and defaults from the status. Use
    `suspended` or `canceled` to test those washes.
  - `startsInMinutes` is relative to Start; negative means already started.
  - `periods` are points per period; overtime is a fifth period.
- **Players:**
  - `stats` use the canonical keys (see [Player stats](/player-stats));
    missing keys are 0, and unknown keys are an error.
  - `played: false` is a player who didn't play.
- **Steps** merge into games and players by `id` once `afterSeconds` have
  passed since Start. Nested objects merge; other values replace them, and
  `null` clears a value.

Included scenarios:

| File | What it's for |
|---|---|
| `edit-me.json` | A live NFL game with no script, for editing by hand. |
| `nfl-late-touchdown.json` | A late touchdown by a named receiver, then Final: player touchdowns, spread, total. |
| `nba-total-on-the-line.json` | Ends on exactly 224 combined: a 224 total pushes, 224.5 goes Under; plus a player who didn't play. |

## Recorded chunks

`scripts/cut-sports-chunk.py` lists what's recorded and cuts chunks:

```sh
# Each game's live window and number of score changes on a recording day.
scripts/cut-sports-chunk.py --list --league nba --date 2026-11-13

# Cut 25 minutes (UTC start; folders are New York dates, file names are UTC).
scripts/cut-sports-chunk.py --league nba --from 2026-11-14T03:23:00Z --minutes 25 \
  --games 21718098 --name atl-chi-overtime --title "ATL @ CHI: tied, then overtime" \
  --description "Tied at the end of regulation, CHI wins in OT; good for 'overtime counts'."
```

A chunk keeps the recorded responses. Replay parses them with the live
mapping and uses the latest games and stats response at each point. Player
stats appear only in recordings made since stats recording started
(2026-11-14). Cut so far, scores only:

| Chunk | What happens |
|---|---|
| `atl-chi-overtime` | ATL @ CHI tied 110-110 late, overtime, CHI 125-117. Regulation total 220, final 242. |
| `uta-hou-close-finish` | UTA @ HOU, HOU up 2 with 5 minutes left, wins 125-118. |

Chunks cut from recordings made since 2026-11-14 include player stats; none
have been cut yet.

## Limits

- Play-by-play modes (Choose Their Fate, Scorcerer) aren't covered: they need
  GOAT play-by-play, and scenarios don't model plays.
- Dev games stay in the database. Delete them by hand
  (`DELETE ... WHERE provider_id LIKE 'DEV-%'`) only if no bets reference them.
