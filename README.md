# Vector

> A high-performance, ultra-low-latency asymmetric TCP forwarder built on Netty 4 NIO, designed to optimize cross-region traffic routing for high-traffic Minecraft networks.

---

## Features

- **Non-blocking I/O** — Built entirely on Netty 4 NIO with event-loop-driven architecture; no blocking threads on the hot path.
- **Zero-allocation memory pooling** — All byte buffers are allocated from Netty's `PooledByteBufAllocator`, eliminating GC pressure under sustained load.
- **Single-thread event loop optimization** — Each upstream/downstream pair is pinned to a single `EventLoop`, removing cross-thread synchronisation overhead and cache-line contention.
- **Transparent byte forwarding** — Raw bytes are forwarded without protocol parsing, preserving full compatibility with any Minecraft version or proxy flavour.
- **Designed for scale** — Targets 1000+ concurrent player connections with linear scalability across additional event loop threads.
- **Multiple backends** — Round-robin, least-connections, weighted or IP-hash balancing, active TCP health checks, and automatic failover to another backend when a connect fails.
- **Server list cache** — Pings are answered from a periodically refreshed cache (player counts summed across backends) and never reach your servers. An offline MOTD is shown while every backend is down.
- **Abuse protection** — Per-IP concurrent connection and connection-rate limits, timed / CIDR bans with reasons, optional whitelist.
- **Hot reload** — `reload` re-reads `config.json` and `whitelist.txt` without dropping players.
- **Metrics** — Optional Prometheus (`/metrics`), JSON (`/stats`) and health (`/health`) endpoint.
- **Web dashboard** — Live routing map, charts, player list, backend health, bans, whitelist, log tail and config reload in the browser. On port 3000 by default.

---

## Requirements

| Tool  | Version |
|-------|---------|
| Java  | 21 (LTS) |
| Maven | 3.9+    |

> **Why Java 21?** Netty 4.1.x has full, tested support for Java 21 and benefits from its JIT improvements and Virtual Thread awareness. Java 21 is the recommended LTS target for new Netty 4 projects.

---

## Build

```bash
mvn clean package
```

The fat executable JAR is emitted to:

```
target/vector-1.0.0-SNAPSHOT.jar
```

---

## Run

```bash
java -jar target/vector-1.0.0-SNAPSHOT.jar
```

On Windows, you can also run:

```bat
run.bat
```

First start generates `eula.txt` (if missing) and exits by design. Set `eula=true` and start again.

---

## Configuration

`config.json` is generated on first start. Every key is optional; missing keys use the defaults below.

```json
{
  "bind-host": "0.0.0.0",
  "bind-port": 25565,
  "backends": [
    { "name": "lobby-1", "host": "10.0.0.11", "port": 25577, "weight": 1 },
    { "name": "lobby-2", "host": "10.0.0.12", "port": 25577, "weight": 2, "max-connections": 100 }
  ],
  "balance-mode": "round-robin"
}
```

Older configs that only set `backend-host` / `backend-port` keep working as a single backend named `main`.

| Key | Default | Reloadable | Description |
|-----|---------|:---:|-------------|
| `backends` | one local backend | yes | List of `{name, host, port, weight, max-connections}`. Names must be unique and contain no spaces. `max-connections` (optional, `0` = unlimited) caps the players on one backend; extra players overflow to the others. |
| `full-message` | `§cAll servers are full…` | yes | Disconnect text when every available backend is at its cap. |
| `balance-mode` | `round-robin` | yes | `round-robin`, `least-connections` (load ÷ weight), `weighted` (smooth weighted round-robin), `ip-hash` (a client IP sticks to one backend), `random`, or `least-latency` (prefers the backend with the fastest health probe, scaled by how loaded it is). |
| `health-check-interval-ms` | `5000` | yes | Delay between TCP health probes. |
| `health-check-timeout-ms` | `2000` | yes | Connect timeout of a probe. |
| `health-check-mode` | `tcp` | yes | `tcp` only opens a connection to each backend. `status` sends a real server list ping (with a PROXY header when `proxy-protocol` is on), so a backend whose port is open but whose Minecraft server is hung is detected too. |
| `health-check-failures` | `2` | yes | Consecutive failures (probes or real connects) before a backend is marked down. One success brings it back. |
| `max-connect-attempts` | `3` | yes | How many different backends a joining player is tried against. |
| `proxy-protocol` | `false` | yes | Send a PROXY protocol v2 header to the backend (Velocity: `haproxy-protocol = true`). |
| `max-connections-per-ip` | `20` | yes | Concurrent connections per client IP. `0` disables. |
| `max-connection-rate-per-ip` | `20` | yes | New connections per IP per window. `0` disables. |
| `connection-rate-window-ms` | `10000` | yes | Window for the rate limit. |
| `status-cache-enabled` | `true` | yes | Answer server list pings from the cache. |
| `status-cache-ttl-ms` | `5000` | yes | How often backends are re-polled for the cache. |
| `status-sum-players` | `true` | yes | Report the summed online/max player counts of all healthy backends. |
| `offline-motd` | `§cServer is currently offline` | yes | MOTD and login-disconnect text while every backend is down. Empty disables the offline response. |
| `whitelist-enabled` | `false` | yes | Only let players in whose username or IP is in `whitelist.txt`. |
| `whitelist-message` | `§cYou are not whitelisted…` | yes | Disconnect message for refused logins. |
| `metrics-enabled` | `false` | no | Start the HTTP metrics endpoint. |
| `metrics-host` / `metrics-port` | `127.0.0.1` / `9100` | no | Where it listens. |
| `metrics-token` | empty | yes | If set, `/metrics` and `/stats` require `Authorization: Bearer <token>`. |
| `log-chat` | `true` | yes | Log chat and commands. Set `false` to stop parsing packets once a player is in game, which saves CPU on busy offline-mode backends (joins and leaves are still logged). |
| `idle-timeout-seconds` | `120` | yes | Close a connection that has sent nothing for this long (new connections only). Clients send keep-alives every few seconds, so silence means a dead peer. `0` disables. |
| `handshake-timeout-ms` | `5000` | yes | Close a connection that sends no bytes at all within this time (new connections only). `0` disables. |
| `max-connections` | `0` | yes | Global cap on open client connections (status pings included). New connections beyond it are refused. `0` = unlimited. |
| `maintenance-enabled` | `false` | yes | Maintenance mode: refuse logins unless the username or IP is whitelisted. Toggle live with the `maintenance on/off` console command. |
| `maintenance-message` | `§eThe server is under maintenance…` | yes | Disconnect text shown to refused players. |
| `maintenance-motd` | `§6Under maintenance` | yes | MOTD shown in the server list while maintenance mode is on. |
| `min-protocol` / `max-protocol` | `0` / `0` | yes | Accepted client protocol range at login (`0` = unbounded), e.g. `767` is Minecraft 1.21.1. Players outside it are refused with `protocol-message`. |
| `allowed-hosts` | `[]` | yes | Host names clients must connect with (exact, `*.example.com` or `*`). Connections using another name, including a bare IP, are dropped silently, which shuts out most scanners and bots. Empty = accept any. |
| `auto-ban-violations` | `0` | yes | Ban an IP automatically after this many violations (connection-rate hits, packet floods, refused host names) inside `auto-ban-window-seconds` (default `60`) for `auto-ban-minutes` (default `30`). `0` = off. |
| `dashboard-enabled` | `true` | no | Start the web dashboard. |
| `dashboard-host` / `dashboard-port` | `127.0.0.1` / `3000` | no | Where the dashboard listens. Use `0.0.0.0` to reach it from other machines. |
| `dashboard-password` | empty | no | Password for the dashboard. See [Dashboard](#dashboard). |
| `bind-host`, `bind-port`, `boss-threads`, `worker-threads` | | no | Need a restart. `reload` tells you when one of these changed. |
| `max-bytes-per-second`, `max-packets-per-second`, `connect-timeout-ms` | | yes | Per-connection packet limiter and backend connect timeout (new connections only). |

Notes:

- Health checks are plain TCP connects, so they work whether or not the backend requires the PROXY protocol. The server list cache does send a PROXY v2 `LOCAL` header when `proxy-protocol` is on.
- If the cache cannot get a status from any healthy backend, pings fall through to a normal proxied connection.
- The whitelist matches the username sent at login. With offline-mode backends usernames can be spoofed, so combine it with IP entries there.

### Host-based routing

Send players who joined through different host names to different backends:

```json
"backends": [
  { "name": "lobby-1",  "host": "10.0.0.11", "port": 25577 },
  { "name": "lobby-2",  "host": "10.0.0.12", "port": 25577 },
  { "name": "survival", "host": "10.0.0.21", "port": 25577 }
],
"routes": [
  { "host": "lobby.example.com",  "backends": ["lobby-1", "lobby-2"] },
  { "host": "*.play.example.com", "backends": ["survival"] }
]
```

A host matches exactly first, then the most specific wildcard (`*.example.com` matches subdomains, not the bare domain), then a `"*"` catch-all. A host with no matching route may use every backend. Balancing, health, draining and caps apply inside the allowed set; if none of a route's backends is available the player is refused instead of being sent elsewhere. Routes are reloadable.

Auto-ban counts three kinds of violation per IP: hitting the connection-rate limit, tripping the packet limiter, and connecting with a host name that is not in `allowed-hosts`. Automatic bans appear in the ban list with the reason `auto-ban: ...` and can be lifted with `unban`.

## Console commands

| Command | Description |
|---------|-------------|
| `list` | Active connections with player name and backend. |
| `players` / `find <name\|ip>` | List connected players with backend and session length, or search connections by name or IP. |
| `backends` | Backend health, weight, open/total connections, failures and probe latency. |
| `stats` | Traffic, blocked connections by reason, status ping counters. |
| `kickall [backend]` | Disconnect everyone, or everyone routed to one backend. |
| `evacuate <from> <to>` | Drain `from`, disconnect its players and send their next login to `to`. |
| `drain <backend>` / `undrain <backend>` | Stop or resume sending new players to a backend; existing sessions continue. Useful before restarting a server. |
| `reload` | Reload `config.json` and `whitelist.txt`. An invalid file is rejected and the old config stays active. |
| `kick <ip>` | Disconnect a client. |
| `send <ip> <backend>` | Disconnect the player and route their next login to the named backend. |
| `send <ip> <host> <port>` | Transfer the client to another server (Minecraft 1.20.5+). |
| `ban <ip\|cidr> [30m\|12h\|7d] [reason…]` | Permanent or timed ban, e.g. `ban 203.0.113.0/24 7d botnet`. Persisted in `ban-list.txt`. |
| `unban <ip\|cidr>`, `banlist` | Remove / list bans. |
| `whitelist [list\|add\|remove] <name\|ip\|cidr>` | Manage `whitelist.txt`. |
| `maintenance [on\|off]` | Show or switch maintenance mode (saved to `config.json`). |
| `stop` | Shut down. |

## Dashboard

Open `http://localhost:3000` (change it with `dashboard-port`). It updates every two seconds and has six tabs:

- **Overview** — a live map of players flowing through Vector to each backend (link width follows open connections, red means down), key figures, and ten-minute charts of connections, throughput and events per second.
- **Players** — every open connection with player name, backend and protocol; kick, ban or send to another backend.
- **Backends** — health, weight, open and total connections, failed connects and probe latency.
- **Access** — add and remove bans (with duration and reason) and whitelist entries.
- **Logs** — the console log with level and text filters.
- **Config** — the active settings (secrets are only shown as set or not set) and a **Reload config.json** button.

Access and security:

- By default the dashboard listens on `127.0.0.1` with no password, and refuses requests whose `Host` header is not `localhost`, `127.0.0.1` or `::1` (this blocks DNS-rebinding attacks).
- To use it remotely set `dashboard-host` (for example `0.0.0.0`) **and** `dashboard-password`. If you bind a non-loopback address without a password, Vector generates a random password for that run and prints it in the console.
- With a password, sign-in creates a 12 hour `HttpOnly`, `SameSite=Strict` session. Five wrong passwords lock that IP out for a minute. Scripts can send `Authorization: Bearer <password>` instead.
- Every change (kick, ban, whitelist, reload) needs a same-origin request carrying an `X-Requested-With` header, and is written to the console log as `[DASHBOARD]`.
- The dashboard speaks plain HTTP. For access over the internet put it behind a reverse proxy that terminates HTTPS.

## Metrics

With `metrics-enabled`, `http://<metrics-host>:<metrics-port>/` serves:

- `/metrics` — Prometheus text format (`vector_connections_active`, `vector_blocked_total{reason}`, `vector_backend_up{backend}`, `vector_backend_latency_ms{backend}`, …)
- `/stats` — the same data as JSON
- `/health` — `200` while at least one backend is up, `503` otherwise (for load balancer / uptime checks)

---

## License

MIT
