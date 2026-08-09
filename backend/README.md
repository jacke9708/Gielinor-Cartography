# Gielinor Cartography backend

Internal ops notes for the one production server this plugin talks to - not something an
installer of the plugin ever needs to run themselves. The plugin hardcodes the server address
(`SERVER_URL` in `GielinorCartographyClient.java`) rather than exposing it as a config option,
since there's only ever one real backend to point at.

Requires nothing beyond Python 3 (uses only the standard library - `http.server` and `sqlite3`),
so there's no `pip install` step. Task list comes from `tasks.json` at the project root - the
same file the plugin bundles, so there's one source of truth.

## Running it

Deployed as a systemd service (`gielinor-cartography.service`, included in this directory) on the
Linux host behind the Cloudflare Tunnel - `systemctl restart gielinor-cartography` after
redeploying `server.py` or `tasks.json`. To set this up fresh on a new host: copy the unit file to
`/etc/systemd/system/`, then `systemctl enable --now gielinor-cartography`.

Listens on `0.0.0.0:8000` locally; the Cloudflare Tunnel is what makes it reachable at
`SERVER_URL`, not a directly-exposed port. A SQLite file (`gielinor_cartography.db`) is created
next to the script on first run, seeded with every task from `tasks.json` (unowned, no cooldown,
until claimed).

## API

- `GET /task?id=...` - current state of one task: `{id, owner, lastTaken, cooldownSeconds}`.
  `owner`/`lastTaken` are `null` until it's first claimed.
- `GET /tasks` - current state of every task, as a JSON array.
- `GET /leaderboard` - every player's points/last-known total level, sorted descending. Each
  player's `totalPoints` includes a live preview of unsettled passive income from tasks they
  currently own, on top of their settled total.
- `POST /complete` - body `{"taskId": "...", "player": "...", "totalLevel": N, "playerToken": "..."}`.
  If the task's cooldown hasn't expired since it was last taken, responds `423 Locked` with
  `{error, owner, unlocksAt}`. If `playerToken` doesn't match what's already bound to that player
  name, responds `403`. Otherwise claims/steals the task and responds `200` with the new state
  plus `{pointsAwarded, stealBonus, stolen, previousOwner}`.

## Points model

- Owning a task pays continuous passive income (`PASSIVE_POINTS_PER_MINUTE` in `server.py`),
  settled into the previous owner's total the moment a task changes hands (including a player
  reclaiming their own task once its cooldown lets them).
- Claiming/stealing always pays a flat `POINTS_AWARDED`. Stealing (not claiming unclaimed
  territory) additionally pays a neglect bonus scaled by how long the previous owner held it
  uncontested, capped at `STEAL_BONUS_CAP`.

## Claim integrity

`playerToken` is a per-installation secret the plugin generates and persists locally (see
`PlayerToken.java` on the plugin side). The backend binds whatever token first claims under a
player name to that name (`player_tokens` table), and rejects any later claim under that name
with a different token. This isn't real account authentication - it's just enough to stop
casually spoofing someone else's claims over plain HTTP.

## Known limitations

- No rate limiting.
- `playerToken` binding is trust-on-first-use, not tied to an actual Jagex account.
