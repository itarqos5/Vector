# Vector

> A high-performance, ultra-low-latency asymmetric TCP forwarder built on Netty 4 NIO, designed to optimize cross-region traffic routing for high-traffic Minecraft networks.

---

## Features

- **Non-blocking I/O** — Built entirely on Netty 4 NIO with event-loop-driven architecture; no blocking threads on the hot path.
- **Zero-allocation memory pooling** — All byte buffers are allocated from Netty's `PooledByteBufAllocator`, eliminating GC pressure under sustained load.
- **Single-thread event loop optimization** — Each upstream/downstream pair is pinned to a single `EventLoop`, removing cross-thread synchronisation overhead and cache-line contention.
- **Transparent byte forwarding** — Raw bytes are forwarded without protocol parsing, preserving full compatibility with any Minecraft version or proxy flavour.
- **Designed for scale** — Targets 1000+ concurrent player connections with linear scalability across additional event loop threads.

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

## License

MIT
