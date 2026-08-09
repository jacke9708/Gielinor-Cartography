"""
Gielinor Cartography backend - LAN-only prototype, no auth. Task list comes from tasks.json
at the project root - the same file the plugin bundles, so there's one source of truth.

Uses only the Python standard library (http.server + sqlite3) so there's nothing to
pip install: run it with `python server.py` once you have Python 3 installed.

Endpoints:
  GET  /task?id=...  -> current state of the given task
  GET  /tasks         -> current state of every task, as a JSON array
  GET  /leaderboard   -> every player's total points/last-known total level, as a JSON array
                          (unsorted-by-tier - the client buckets into tiers itself). Each
                          player's totalPoints includes a live preview of unsettled passive
                          income from tasks they currently own, on top of their settled total -
                          see PASSIVE_POINTS_PER_MINUTE below.
  POST /complete      -> {"taskId": "...", "player": "...", "totalLevel": N, "playerToken": "..."}
                          ; claims/steals the task if its cooldown has expired (else 423), and
                          credits the player's running point total. totalLevel is optional
                          (older clients won't send it) and just updates that player's
                          last-known value. playerToken is a per-installation secret bound to a
                          player name on that name's first-ever claim (see PlayerToken.java on
                          the plugin side) - later claims under that name with a different (or
                          missing) token are rejected with 403. Not real auth, just enough to
                          stop casually spoofing someone else's claims over plain HTTP.

Points model:
  - Owning a task pays PASSIVE_POINTS_PER_MINUTE continuously. This is never paid out via a
    background job - it's settled into the previous owner's total_points the moment the task
    changes hands (including a player reclaiming their own task once its cooldown lets them),
    based on elapsed time since that task's last_taken. Until then, /leaderboard shows it as a
    live, unsettled preview on top of the owner's settled total.
  - Claiming/stealing a task always pays the flat POINTS_AWARDED. Stealing (not claiming
    unclaimed territory) additionally pays a neglect bonus at STEAL_BONUS_PER_MINUTE, scaled by
    how long the previous owner held it uncontested, capped at STEAL_BONUS_CAP so an
    abandoned-for-weeks task doesn't pay out an absurd one-time windfall.
"""

import json
import sqlite3
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse, parse_qs

HOST = "0.0.0.0"
PORT = 8000

DB_PATH = Path(__file__).parent / "gielinor_cartography.db"
TASKS_FILE = Path(__file__).parent.parent / "tasks.json"

COOLDOWN_SECONDS = 15 * 60  # placeholder, unbalanced on purpose
POINTS_AWARDED = 10  # flat, paid on every claim/steal regardless of neglect bonus
# Continuous income for whoever currently owns a task - 0.1/min is 6/hour, so owning ~5 tasks
# nets ~30/hour passively, meaningful without dwarfing a single active claim (10 points).
PASSIVE_POINTS_PER_MINUTE = 0.1
# Same rate, but only paid once, to whoever steals a task, based on how long the previous owner
# held it uncontested - rewards taking neglected territory without an unbounded windfall.
STEAL_BONUS_PER_MINUTE = 0.1
STEAL_BONUS_CAP = 15  # caps out around 2.5 hours of neglect


def load_task_ids():
    with open(TASKS_FILE, "r", encoding="utf-8") as f:
        return [task["id"] for task in json.load(f)]


TASK_IDS = load_task_ids()


def get_db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def init_db():
    conn = get_db()
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS tasks (
            id TEXT PRIMARY KEY,
            owner TEXT,
            last_taken INTEGER
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS players (
            name TEXT PRIMARY KEY,
            total_points INTEGER NOT NULL DEFAULT 0,
            total_level INTEGER
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS player_tokens (
            name TEXT PRIMARY KEY,
            token TEXT NOT NULL
        )
        """
    )
    conn.executemany(
        "INSERT OR IGNORE INTO tasks (id, owner, last_taken) VALUES (?, NULL, NULL)",
        [(task_id,) for task_id in TASK_IDS],
    )
    conn.commit()
    conn.close()


def task_to_json(row):
    return {
        "id": row["id"],
        "owner": row["owner"],
        "lastTaken": row["last_taken"],
        "cooldownSeconds": COOLDOWN_SECONDS,
    }


def player_to_json(row):
    return {
        "name": row["name"],
        "totalPoints": row["total_points"],
        "totalLevel": row["total_level"],
    }


class Handler(BaseHTTPRequestHandler):
    # Force every connection closed after one response and say so explicitly. OkHttp (the
    # plugin's HTTP client) pools connections for reuse by default; BaseHTTPRequestHandler
    # doesn't reliably signal "not reusable" otherwise, which shows up as
    # "unexpected end of stream" on the client when it reuses a connection this server
    # already dropped.
    close_connection = True

    def _send_json(self, status, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        parsed = urlparse(self.path)

        if parsed.path == "/tasks":
            conn = get_db()
            rows = conn.execute("SELECT * FROM tasks").fetchall()
            conn.close()
            self._send_json(200, [task_to_json(row) for row in rows])
            return

        if parsed.path == "/leaderboard":
            conn = get_db()
            rows = conn.execute("SELECT * FROM players").fetchall()
            owned = conn.execute(
                "SELECT owner, last_taken FROM tasks WHERE owner IS NOT NULL AND last_taken IS NOT NULL"
            ).fetchall()
            conn.close()

            now = int(time.time())
            live_preview_by_owner = {}
            for task in owned:
                held_minutes = (now - task["last_taken"]) / 60
                live_preview_by_owner[task["owner"]] = (
                    live_preview_by_owner.get(task["owner"], 0) + held_minutes * PASSIVE_POINTS_PER_MINUTE
                )

            players = []
            for row in rows:
                entry = player_to_json(row)
                entry["totalPoints"] += round(live_preview_by_owner.get(row["name"], 0))
                players.append(entry)
            players.sort(key=lambda p: p["totalPoints"], reverse=True)

            self._send_json(200, players)
            return

        if parsed.path != "/task":
            self._send_json(404, {"error": "not found"})
            return

        task_id = parse_qs(parsed.query).get("id", [None])[0]
        if not task_id:
            self._send_json(400, {"error": "missing id"})
            return

        conn = get_db()
        row = conn.execute("SELECT * FROM tasks WHERE id = ?", (task_id,)).fetchone()
        conn.close()

        if row is None:
            self._send_json(404, {"error": "unknown task"})
            return

        self._send_json(200, task_to_json(row))

    def do_POST(self):
        if self.path != "/complete":
            self._send_json(404, {"error": "not found"})
            return

        length = int(self.headers.get("Content-Length", 0))
        try:
            body = json.loads(self.rfile.read(length) or b"{}")
        except json.JSONDecodeError:
            self._send_json(400, {"error": "invalid json"})
            return

        player = body.get("player")
        task_id = body.get("taskId")
        total_level = body.get("totalLevel")  # optional
        player_token = body.get("playerToken")
        if not player or not isinstance(player, str):
            self._send_json(400, {"error": "missing player"})
            return
        if not task_id or not isinstance(task_id, str):
            self._send_json(400, {"error": "missing taskId"})
            return
        if not player_token or not isinstance(player_token, str):
            self._send_json(400, {"error": "missing playerToken"})
            return

        conn = get_db()

        # Bind this token to the player name on first use (trust-on-first-use); any later claim
        # under this name must present the same token, or it's rejected as a spoofed identity -
        # not real auth, just enough to stop casually claiming as someone else over plain HTTP.
        token_row = conn.execute("SELECT token FROM player_tokens WHERE name = ?", (player,)).fetchone()
        if token_row is None:
            conn.execute("INSERT INTO player_tokens (name, token) VALUES (?, ?)", (player, player_token))
            conn.commit()
        elif token_row["token"] != player_token:
            conn.close()
            self._send_json(403, {"error": "player name already claimed by a different installation"})
            return

        row = conn.execute("SELECT * FROM tasks WHERE id = ?", (task_id,)).fetchone()
        if row is None:
            conn.close()
            self._send_json(404, {"error": "unknown task"})
            return

        now = int(time.time())
        if row["last_taken"] is not None and now - row["last_taken"] < COOLDOWN_SECONDS:
            conn.close()
            self._send_json(
                423,
                {
                    "error": "locked",
                    "owner": row["owner"],
                    "unlocksAt": row["last_taken"] + COOLDOWN_SECONDS,
                },
            )
            return

        previous_owner = row["owner"]
        previous_last_taken = row["last_taken"]
        stolen = previous_owner is not None and previous_owner != player

        # Settle the outgoing owner's passive income for however long they held it - this also
        # covers a player reclaiming their own task once its cooldown expires, which just banks
        # what they'd earned so far and restarts the clock via the last_taken update below.
        passive_earned = 0
        steal_bonus = 0
        if previous_owner is not None and previous_last_taken is not None:
            held_minutes = (now - previous_last_taken) / 60
            passive_earned = round(held_minutes * PASSIVE_POINTS_PER_MINUTE)
            if stolen:
                steal_bonus = min(STEAL_BONUS_CAP, round(held_minutes * STEAL_BONUS_PER_MINUTE))

        conn.execute(
            "UPDATE tasks SET owner = ?, last_taken = ? WHERE id = ?",
            (player, now, task_id),
        )
        if previous_owner is not None and passive_earned > 0:
            conn.execute(
                """
                INSERT INTO players (name, total_points, total_level) VALUES (?, ?, NULL)
                ON CONFLICT(name) DO UPDATE SET total_points = total_points + excluded.total_points
                """,
                (previous_owner, passive_earned),
            )
        conn.execute(
            """
            INSERT INTO players (name, total_points, total_level) VALUES (?, ?, ?)
            ON CONFLICT(name) DO UPDATE SET
                total_points = total_points + excluded.total_points,
                total_level = COALESCE(excluded.total_level, players.total_level)
            """,
            (player, POINTS_AWARDED + steal_bonus, total_level),
        )
        conn.commit()
        updated = conn.execute("SELECT * FROM tasks WHERE id = ?", (task_id,)).fetchone()
        conn.close()

        response = task_to_json(updated)
        response["pointsAwarded"] = POINTS_AWARDED + steal_bonus
        response["stealBonus"] = steal_bonus
        response["stolen"] = stolen
        response["previousOwner"] = previous_owner
        self._send_json(200, response)

    def log_message(self, format, *args):
        print("[gielinor-cartography]", self.address_string(), format % args)


def main():
    init_db()
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    print(f"Gielinor Cartography backend listening on http://{HOST}:{PORT}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
