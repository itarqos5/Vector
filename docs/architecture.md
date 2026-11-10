# Architecture

A short tour of how Vector is put together, for people changing it.

## Startup

`gg.literal.VectorLauncher` is the entry point. It runs in the ordinary application class loader and only
touches classes that must be shared:

1. accept the EULA (`EulaManager`), load `config.json` (`VectorConfigLoader`, `Json`), start file logging,
2. validate the hardware settings and download the Netty jars into `libraries/` if they are missing,
3. build a **child-first class loader** over the jar plus those Netty jars and call
   `gg.literal.runtime.VectorRuntime.boot(config)` reflectively.

Because of step 3, three package groups behave differently:

| Package | Loaded by | Why |
|---|---|---|
| `gg.literal.config`, `gg.literal.log`, `gg.literal.bootstrap`, `gg.literal` | application loader | shared between the launcher and the runtime (`VectorConfig` crosses the boundary) |
| `gg.literal.runtime`, `.console`, `.detect`, `io.netty.*` | child loader (child first) | the proxy itself, with the downloaded Netty |

A class in `config` or `log` must never reference one in `runtime`.

## The connection pipeline

Every client connection gets this pipeline (`SecurePipelineInitializer`):

```
ban-check -> connection-limit -> [idle] -> mc-sniffer -> packet-limiter -> eu-frontend
```

- **BanCheckHandler** refuses banned IPs/CIDR blocks before anything else happens.
- **ConnectionLimitHandler** applies the per-IP, rate and global limits (`ConnectionLimiter`) and reports violations to `AutoBan`.
- **IdleStateHandler** closes silent connections.
- **MinecraftProtocolSniffer** passes bytes through unchanged while parsing just enough of the protocol to learn the
  handshake (host name, protocol version, next state), the login name (joins, whitelist, maintenance, protocol range)
  and, optionally, chat. It can veto a login by writing a Login Disconnect and swallowing the bytes.
- **PacketLimiterHandler** drops clients that exceed bytes/packets per second.
- **EuFrontendHandler** decides what the connection is and relays it.

### EuFrontendHandler in detail

It buffers the first bytes until the sniffer has seen the handshake, then picks one of:

- **Local status**: answers the server list ping itself from `StatusService` (cached merged status, or a
  `SyntheticStatus` for offline/maintenance) and closes after the pong. No backend is involved.
- **Proxy**: asks `BackendPool.select` for a backend (honouring `RouteTable` host routes, health, draining and caps),
  connects with failover, optionally sends a PROXY v2 header, replays the buffered bytes, then relays both directions.
  Writes are batched per read loop and each direction pauses the other when its socket is not writable.

## Backends

`BackendPool` holds `Backend` objects (health, draining, counters). `HealthChecker` probes them periodically with a
TCP connect or a status ping; real connects feed the same health state machine. `BackendPool.apply` swaps the list on
reload while keeping state for backends whose address did not change.

## Configuration

`VectorConfig` is an immutable record of nested records. `ConfigHolder` owns the live snapshot; `reload()` re-reads
the file and `update(...)` validates an edited copy before atomically replacing `config.json`. Handlers read the
current snapshot per connection, so most settings apply to new connections immediately.

## Observability and control

- `Metrics` (counters), `MetricsHistory` (ten minutes of per-second rates), `ActivityLog` (event feed) and
  `TerminalLogger` (log ring buffer + optional file) are the data sources.
- `MetricsServer` exposes Prometheus/JSON/health; `DashboardServer` serves the single-page UI in
  `src/main/resources/dashboard` and a small JSON API. Both use the JDK's built-in HTTP server.
- `VectorConsole` is the stdin command line; `WebhookNotifier` subscribes to the activity feed.

## Tests

- `mvn test` runs the unit tests (`src/test`).
- `tests/e2e/run.sh` builds the jar and runs `Harness.java`, which starts fake Minecraft backends and a real Vector
  process and checks behaviour over the wire. `tests/e2e/Demo.java` generates mock traffic for the dashboard.
