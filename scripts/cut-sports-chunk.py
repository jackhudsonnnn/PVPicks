#!/usr/bin/env python3
"""Cut a labeled dev chunk from the sports recordings, or list what's recorded.

Recordings (git-ignored, written by the dev backend) live in recordings/sports/<league>/<date>/ as
<UTC timestamp>_<endpoint>.json, each {receivedAt, league, endpoint, query, status, body}. A chunk is a short
stretch of them (games and player stats) that the Dev page replays as DEV- games. See docs/site/dev-sports-data.md.

  # What's recorded on a day: each game's first/last live update and how many score changes it had.
  scripts/cut-sports-chunk.py --list --league nba --date 2026-11-14

  # Cut 20 minutes starting at a UTC time, for one or more games (provider game ids from --list).
  scripts/cut-sports-chunk.py --league nba --from 2026-11-15T02:40:00Z --minutes 20 --games 18447 \\
      --name den-nop-q4 --title "DEN @ NOP: fourth quarter" \\
      --description "Close game, lead changes, Final at the end; good for spreads and late totals."
"""
import argparse
import json
import re
import shutil
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RECORDINGS = ROOT / "recordings" / "sports"
CHUNKS = ROOT / "dev" / "sports" / "chunks"
NAME = re.compile(r"[a-z0-9][a-z0-9_-]{0,40}")
FILE = re.compile(r"(\d{4}-\d{2}-\d{2}T\d{2}-\d{2}-\d{2}(?:\.\d+)?Z)_(\w+)\.json")


def received(path):
    m = FILE.fullmatch(path.name)
    if not m:
        return None, None
    stamp = m.group(1)
    date, time = stamp[:10], stamp[11:-1].replace("-", ":")
    return datetime.fromisoformat(f"{date}T{time}+00:00"), m.group(2)


def recordings(league, start=None, end=None):
    """Every recording for a league between two times (or all), oldest first."""
    folder = RECORDINGS / league.lower()
    if not folder.is_dir():
        sys.exit(f"No recordings in {folder}")
    found = []
    for path in sorted(folder.glob("*/*.json")):
        at, endpoint = received(path)
        if at is None or (start and at < start) or (end and at > end):
            continue
        found.append((at, endpoint, path))
    return found


def game_ids(query):
    return re.findall(r"game_ids(?:%5B%5D|\[\])=(\d+)", query or "")


def list_day(league, date):
    games = {}
    for at, endpoint, path in recordings(league):
        if endpoint != "games" or not str(path.parent.name).startswith(date):
            continue
        body = json.loads(path.read_text()).get("body", {})
        for game in body.get("data", []):
            gid = str(game.get("id"))
            home = (game.get("home_team") or {}).get("abbreviation", "?")
            away = (game.get("visitor_team") or game.get("away_team") or {}).get("abbreviation", "?")
            score = (game.get("home_team_score"), game.get("visitor_team_score", game.get("away_team_score")))
            entry = games.setdefault(gid, {"label": f"{away} @ {home}", "live": [], "changes": 0, "last": None})
            state = game.get("status_state") or game.get("status")
            if state in ("in_progress", "halftime") or (isinstance(state, str) and state[:1].isdigit()):
                entry["live"].append(at)
            if score != entry["last"]:
                entry["changes"] += 1
                entry["last"] = score
    stats = {}
    for at, endpoint, path in recordings(league):
        if endpoint == "stats" and str(path.parent.name).startswith(date):
            for gid in game_ids(json.loads(path.read_text()).get("query")):
                stats[gid] = stats.get(gid, 0) + 1
    if not games:
        print("No games recorded that day.")
    for gid, entry in sorted(games.items(), key=lambda item: item[1]["live"][0] if item[1]["live"] else datetime.max.replace(tzinfo=timezone.utc)):
        live = entry["live"]
        span = f"live {live[0]:%H:%M}–{live[-1]:%H:%M} UTC" if live else "never live"
        print(f"{gid:>10}  {entry['label']:<12} {span:<28} {entry['changes']:>3} score changes, "
              f"{stats.get(gid, 0)} stats responses")


def cut(args):
    if not NAME.fullmatch(args.name):
        sys.exit("--name: lowercase letters, digits, - or _ (it becomes part of game ids)")
    start = datetime.fromisoformat(args.start.replace("Z", "+00:00"))
    end = start + timedelta(minutes=args.minutes)
    wanted = set(args.games.split(","))
    target = CHUNKS / args.name
    if target.exists() and not args.force:
        sys.exit(f"{target} exists; pass --force to replace it")
    responses = target / "responses"
    responses.mkdir(parents=True, exist_ok=True)
    for old in responses.glob("*.json"):
        old.unlink()
    written = {"games": 0, "stats": 0}
    for at, endpoint, path in recordings(args.league, start, end):
        record = json.loads(path.read_text())
        offset = int((at - start).total_seconds() * 1000)
        body = record.get("body", {})
        if endpoint == "games":
            kept = [game for game in body.get("data", []) if str(game.get("id")) in wanted]
            if not kept:
                continue
            record["body"] = {**body, "data": kept}
            (responses / f"{offset:09d}_games.json").write_text(json.dumps(record, indent=1))
            written["games"] += 1
        elif endpoint == "stats":
            for gid in game_ids(record.get("query")):
                if gid in wanted:
                    (responses / f"{offset:09d}_stats_{gid}.json").write_text(json.dumps(record, indent=1))
                    written["stats"] += 1
    if written["games"] == 0:
        shutil.rmtree(target)
        sys.exit("No games responses for those games in that window; check --from (UTC; folders are New York "
                 "dates, file names are UTC) and --games")
    (target / "chunk.json").write_text(json.dumps({
        "title": args.title,
        "description": args.description,
        "league": args.league.upper(),
        "recordedFrom": start.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "durationSeconds": args.minutes * 60,
        "sourceGames": sorted(wanted),
    }, indent=2) + "\n")
    print(f"Wrote {target}: {written['games']} games and {written['stats']} stats responses.")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--league", required=True, choices=["nba", "nfl", "NBA", "NFL"])
    parser.add_argument("--list", action="store_true", help="list a day's recorded games instead of cutting")
    parser.add_argument("--date", help="with --list: the recording folder date, YYYY-MM-DD")
    parser.add_argument("--from", dest="start", help="chunk start, UTC (e.g. 2026-11-15T02:40:00Z)")
    parser.add_argument("--minutes", type=int, default=20)
    parser.add_argument("--games", help="comma-separated provider game ids")
    parser.add_argument("--name", help="folder name and id prefix, e.g. den-nop-q4")
    parser.add_argument("--title", help="short label shown on the Dev page")
    parser.add_argument("--description", default="", help="what happens in it and what it's good for")
    parser.add_argument("--force", action="store_true", help="replace an existing chunk")
    args = parser.parse_args()
    if args.list:
        if not args.date:
            sys.exit("--list needs --date")
        list_day(args.league, args.date)
        return
    for flag in ("start", "games", "name", "title"):
        if not getattr(args, flag):
            sys.exit(f"--{'from' if flag == 'start' else flag} is required to cut a chunk")
    cut(args)


if __name__ == "__main__":
    main()
