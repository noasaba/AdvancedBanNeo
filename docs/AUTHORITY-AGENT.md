# Authority / Agent and ChatSyncer guide

## Roles and storage

AdvancedBan Neo has a fixed role for the lifetime of each process:

- Paper with no `network.yml`: `STANDALONE_AUTHORITY`. It retains all AdvancedBan 2.3.0 behavior and uses local HSQLDB or MySQL according to the existing `config.yml`.
- Velocity: `COORDINATOR_AUTHORITY`. It is the network source of truth and can also use local HSQLDB or MySQL.
- Paper with `Mode: AGENT`: `AGENT_DEGRADED` until it authenticates and installs a full snapshot, then `AGENT`.

A paired Paper node never promotes itself to a standalone Authority because Velocity is temporarily unavailable. An Agent does not initialize HSQLDB/MySQL, read punishment tables, or write punishment tables. Its active enforcement state exists only in memory and is replaced from a full Authority snapshot after every connection or reconnect.

## Pairing one Velocity Authority with Paper Agents

Start the Velocity artifact once. It creates `plugins/advancedban/network.yml` with the transport disabled. Edit it:

```yaml
Enabled: true
Authority:
  Id: velocity
Listen:
  Host: 127.0.0.1
  Port: 27785
Security:
  CredentialsDirectory: nodes
AllowedNodes:
  - survival
  - lobby
```

Restart Velocity. It creates a separate 256-bit credential for each allowed node:

```text
plugins/advancedban/nodes/survival.key
plugins/advancedban/nodes/lobby.key
```

Copy only the matching node file to that Paper server as `plugins/AdvancedBan/network.key`. Then create `plugins/AdvancedBan/network.yml`:

```yaml
Mode: AGENT
Node:
  Id: survival
Coordinator:
  Host: 127.0.0.1
  Port: 27785
Security:
  KeyFile: network.key
```

Use the Authority's private address instead of `127.0.0.1` when the processes are on different hosts. Restrict the port with a firewall to the Paper hosts. The protocol authenticates and integrity-protects every message but does not encrypt punishment text, so route it over a trusted private network or an encrypted tunnel.

Never reuse one node's credential for a different node. Credentials are external files and are not embedded in either JAR or written to logs. On POSIX filesystems, generated Authority credentials are restricted to owner read/write; configure equivalent ACLs after copying them to Paper. To rotate a credential, stop the affected Agent, replace or remove its Authority-side key, restart Velocity so a replacement is created, copy it to the matching Paper node, and restart that Agent.

The first `Mode: AGENT` startup creates `plugins/AdvancedBan/.agent-paired`. This marker prevents a missing or damaged `network.yml` from silently turning the backend into a second Authority. To intentionally return a server to standalone mode, stop it and remove `network.yml`, its node credential, and `.agent-paired` together.

## Synchronization and failure behavior

The TCP connection does not depend on an online Minecraft player. Authentication uses a fresh Agent nonce, a fresh Authority nonce, HMAC-SHA-256, and a derived session key. Signed frames include protocol version, session ID, sequence, timestamp, source, target, message type, and payload. Modified, replayed, stale-session, wrong-source, wrong-target, and incompatible-version frames are rejected.

After authentication the Authority sends a full active-punishment snapshot. Creates, reason changes, and revocations are then sent as ordered incremental updates. A reconnect always starts a new session and full snapshot, atomically removing stale Agent state. Agent command/API requests carry an idempotency UUID and are authorized and executed at Velocity; the Agent never treats a failed remote operation as a successful local mutation.

Both sides require a response to an authenticated heartbeat. A half-open or one-way connection is closed after a missed acknowledgement, the Paper snapshot is marked unavailable, and only a new authenticated session plus full snapshot restores readiness. Bounded per-connection writer queues keep a slow node from blocking persistence or every other Agent.

While an Agent is disconnected or has not installed its snapshot:

- it remains a Degraded Agent and does not open a local DB;
- punishment commands fail rather than run locally;
- player chat and configured muted commands fail closed to prevent a mute bypass;
- Velocity continues network-level login, ban, IP-ban, chat, and command enforcement.

## Commands and permissions

Commands entered on a paired Paper server are forwarded to Velocity. Player identity is verified against the connected Velocity player, and command permissions are evaluated by Velocity's permission provider. Paper-side tab suggestions are non-authoritative; the Velocity Authority performs the real permission check before any mutation.

Paper-console commands are accepted only from a node possessing that node's configured credential and execute as the Velocity console. Protect every node credential as a console-equivalent secret.

Use Velocity's native registration for authoritative tab completion. A paired Paper Agent returns no local punishment/history suggestions because Paper permissions are not the network authority; actual commands remain available and are checked by Velocity. Paper console receives an acceptance notice, while detailed output is written to the Velocity console.

## ChatSyncer 0.2.0-beta.10

If the Paper plugin `ChatSyncerChat` is installed, AdvancedBan Neo automatically obtains the documented public services and installs both:

- a `ChatEventBus` pre-send listener for gameplay/compatibility routes;
- a vNext `ChatPreSendInterceptor` for `PLAYER` and `PRIVATE` messages, with `REJECT` failure policy.

Both gates read the same side-effect-free, thread-safe AdvancedBan runtime mute view. They do not perform JDBC, file, Redis, HTTP, network, or blocking future operations. Messages with no Minecraft player UUID, including Discord and system messages, are allowed. AdvancedBan global mutes are not copied into ChatSyncer's channel-mute storage.

The two public gates register as one security unit. If either registration fails, the other is rolled back. While the complete ChatSyncer hook is active, the generic Bukkit chat listener defers to it so a message receives one warning. Legacy messages are gated only for `MINECRAFT` and `PRIVATE_MESSAGE`; Discord, Velocity relay, and system origins are allowed even if they carry a linked UUID.

The integration is optional and uses only public API names loaded from the enabled ChatSyncer plugin. No ChatSyncer classes are shaded into AdvancedBan Neo. If the services are unavailable, only the integration is disabled; AdvancedBan's normal Bukkit chat enforcement remains active. The hook unregisters both gates when either plugin is disabled and avoids duplicate registration after re-enable.

In a Velocity network the flow is:

```text
Velocity Authority persistence
  -> authenticated punishment update
  -> Paper Agent in-memory state
  -> Bukkit and ChatSyncer mute gates
```

The Paper Agent never restores DB access for ChatSyncer.
