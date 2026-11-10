# Changelog

All notable changes to Vector are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

### Added

**Routing and backends**
- Multiple backends with `round-robin`, `least-connections`, `weighted`, `ip-hash`, `random` and `least-latency` balancing, per-backend `weight` and `max-connections`, and failover when a connect fails.
- TCP or real server-list-ping health checks (`health-check-mode`), drain/undrain, and `evacuate` to move players between backends.
- Host-name routing (`routes`) with exact, wildcard and catch-all matches.
- Maintenance mode with its own MOTD, kick message and a whitelist bypass.

**Server list**
- Cached server list pings with summed player counts across backends, and a generated offline response while every backend is down.

**Protection**
- Per-IP connection and rate limits, a global connection cap, idle and handshake timeouts.
- Timed and CIDR bans, optional whitelist, `allowed-hosts`, a client protocol range and auto-ban for repeat offenders.

**Operations**
- Hot `reload`, runtime edits that validate before writing, `--check-config`, `VECTOR_*` environment overrides, `VECTOR_EULA`, comments and trailing commas in `config.json`.
- Rotating, gzipped log files, Prometheus/JSON/health endpoints, Discord/Slack webhook alerts.
- Dockerfile, docker-compose example, systemd unit and `run.sh`.

**Web dashboard** (port 3000 by default)
- Live routing map, ten-minute charts, players table with traffic, backends, activity feed, access lists, log tail and an editable config view.
- Drain, move players, kick, ban, whitelist and safe settings from the browser; session login, CSRF protection and a strict CSP.

**Console**
- `backends`, `players`, `find`, `stats`, `drain`, `undrain`, `evacuate`, `kickall`, `maintenance`, `whitelist`, `reload`, `version`.

### Changed
- `config.json` is parsed with a real JSON reader instead of regular expressions, so malformed files fail with a line and column. Old `backend-host` / `backend-port` files keep working.
- Relay writes are batched per read loop and both directions apply backpressure; the sniffer allocates its buffers lazily and frees them as soon as parsing is finished.
- Backend selection no longer allocates a candidate list per connection and per-IP actions use an index instead of scanning every connection.

### Testing
- JUnit tests for the parser, config loader, pools, limiter, bans, whitelist, routing, webhooks, log rotation and more, plus an end-to-end harness (`tests/e2e`) that drives a real Vector process against fake Minecraft backends.
