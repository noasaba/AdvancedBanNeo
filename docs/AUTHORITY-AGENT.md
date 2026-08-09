# Authority / Agent and ChatSyncer guide

## Roles and storage

AdvancedBan Neo has a fixed role for the lifetime of each process:

- Paper with no `network.key` and no previous pairing marker: `STANDALONE_AUTHORITY`. It retains all AdvancedBan 2.3.0 behavior and uses local HSQLDB or MySQL according to the existing `config.yml`.
- Velocity: `COORDINATOR_AUTHORITY`. It is the network source of truth and can also use local HSQLDB or MySQL.
- Paper with the shared `network.key`: `AGENT_DEGRADED` until it authenticates and installs a full snapshot, then `AGENT`.

A paired Paper node never promotes itself to a standalone Authority because Velocity is temporarily unavailable. An Agent does not initialize HSQLDB/MySQL, read punishment tables, or write punishment tables. Its active enforcement state exists only in memory and is replaced from a full Authority snapshot after every connection or reconnect.

## Pairing one Velocity Authority with Paper Agents

Start the Velocity artifact once. Velocity automatically acts as the Authority, listens on `127.0.0.1:27785`, and creates:

```text
plugins/advancedban/network.key
```

Copy that same file to every Paper server which should be a DB-less Agent:

```text
plugins/AdvancedBan/network.key
```

No `network.yml`, mode flag, node list, or per-server credential generation is required. The platform and presence of the shared key determine the role automatically. Existing `network.yml` files from development builds are not used.

On first pairing, Paper also creates `agent.id`. This automatically generated UUID is only a stable internal connection identity; it is not a secret, is not configured by the administrator, and must not be copied between servers. Keep it with that Paper server's data when moving or restoring the installation.

The default address needs no configuration when Velocity and Paper can both reach `127.0.0.1:27785`. For a different address, add only the required optional keys to the existing AdvancedBan `config.yml`.

On Velocity:

```yaml
Network:
  BindHost: 0.0.0.0
  Port: 27785
```

On each Paper Agent:

```yaml
Network:
  CoordinatorHost: 10.0.0.10
  CoordinatorPort: 27785
```

Omit these keys to keep the defaults. Use the Authority's private address when the processes are on different hosts. Restrict the port with a firewall to the Paper hosts. The protocol authenticates and integrity-protects every message but does not encrypt punishment text, so route it over a trusted private network or an encrypted tunnel.

The shared-key setup deliberately has no backend-name-to-Agent credential binding. Velocity therefore does not reject backend routing based on an Agent UUID. Network-wide login, chat, and configured muted-command enforcement remains active at Velocity, while each installed Paper Agent independently fails closed for local chat and muted commands until it has installed both snapshots and sent `READY`. This prevents an unavailable Agent from silently opening its own enforcement path without pretending that an automatically generated identity proves which Velocity backend it represents.

The shared credential is external to the JAR and is never written to logs. Treat it as a network-wide, console-equivalent secret: any holder can authenticate as an Agent and forward console operations. On POSIX filesystems, both Velocity and Paper restrict the credential to owner read/write; configure equivalent ACLs on platforms without POSIX permissions. To rotate it, stop the Authority and all Agents, replace `network.key` on Velocity, copy the same replacement to every Agent, and restart the network.

The first Agent startup creates `plugins/AdvancedBan/.agent-paired`. This marker prevents a missing or damaged `network.key` from silently turning the backend into a second Authority. To intentionally return a server to standalone mode, stop it and remove both `network.key` and `.agent-paired`.

## Synchronization and failure behavior

The TCP connection does not depend on an online Minecraft player. Authentication starts with a fresh Authority challenge, binds a fresh Agent nonce and Authority nonce into the authenticated transcript, and derives a new HMAC-SHA-256 session key. Signed frames include protocol version, session ID, sequence, timestamp, source, target, message type, and payload. Modified, replayed, stale-session, wrong-source, wrong-target, expired-handshake, and incompatible-version frames are rejected, including a captured client hello replayed after an Authority restart.

After authentication the Authority sends full active-punishment and chunked history snapshots. The Agent becomes ready only after both snapshots are complete. Creates, reason changes, revocations, and history additions are then sent as ordered incremental updates. A reconnect always starts a new session and full snapshot, atomically removing stale Agent state. Agent command/API requests carry a retry-stable idempotency UUID and are authorized and executed at Velocity; the Agent never treats a failed remote operation as a successful local mutation.

Both sides require a response to an authenticated heartbeat. A half-open or one-way connection is closed after a missed acknowledgement, the Paper snapshot is marked unavailable, and only a new authenticated session plus full snapshot restores readiness. Bounded per-connection writer queues keep a slow node from blocking persistence or every other Agent.

While an Agent is disconnected or has not installed its snapshot:

- it remains a Degraded Agent and does not open a local DB;
- punishment commands fail rather than run locally;
- player chat and configured muted commands fail closed to prevent a mute bypass;
- Velocity continues network-level login, ban, IP-ban, chat, and command enforcement.

Velocity routing itself remains available because the shared credential does not establish a trustworthy one-to-one mapping between an internal Agent UUID and a configured backend name. If a backend must require local ChatSyncer enforcement, use normal proxy/server maintenance or routing controls while its Agent is offline.

## Commands and permissions

Commands entered on a paired Paper server are forwarded to Velocity. Player identity is verified against the connected Velocity player, and command permissions are evaluated by Velocity's permission provider. Paper-side tab suggestions are non-authoritative; the Velocity Authority performs the real permission check before any mutation.

Paper-console commands are accepted only from an Agent possessing the shared credential and execute as the Velocity console. Protect `network.key` as a network-wide, console-equivalent secret.

Velocity remains the authority for tab completion and command output. A paired Paper Agent requests completions asynchronously using the Velocity player's identity and permissions, caches the result briefly without blocking the Paper main thread, and returns it on the next completion request. Paper-console commands execute at Velocity and their complete command output is returned to the originating Paper console.

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
