# Minecraft 26.2 and AdvancedBan Neo 2.4.0-beta.1 compatibility report

## Scope and compatibility baseline

The compatibility baseline is AdvancedBan 2.3.0 at commit `69f3edabfd2f16a181e4c3b4958f3ad2bc3eaabc`. AdvancedBan Neo is versioned as `2.4.0-beta.1`; existing installations still require no conversion of their configuration, messages, layouts, permissions, or storage.

The following 2.3.0 operational resources are byte-for-byte unchanged:

- `config.yml`, `Messages.yml`, and `Layouts.yml`
- HSQLDB format, MySQL table names and columns, punishment layouts, and JDBC configuration keys

The Bukkit/Bungee technical plugin name remains `AdvancedBan`, preserving existing dependencies and data folders. Branding metadata identifies the project as AdvancedBan Neo, retains Leoko as original author, and identifies `nanosize (noasaba)` as maintainer. Velocity's display name is `AdvancedBan Neo` while its stable ID remains `advancedban`. Bukkit command and alias declarations remain unchanged; its descriptor has one additive `ChatSyncerChat` soft dependency.

The public `MethodInterface` methods from 2.3.0 remain callable. The legacy BungeeCord offline-permission provider classes also remain present as deprecated adapters.

## Platforms

| Platform | Build used | Result |
| --- | --- | --- |
| Paper | Minecraft 26.2, build 111 | Loaded, enabled, command/punishment checks, unchanged 2.3.0 HSQLDB data, local persistence, expiry, restart, and DB-less Agent reconnect passed |
| BungeeCord | build 2085 | Loaded, enabled, commands and aliases passed; simultaneous proxy/backend startup passed |
| Velocity | 4.1.0-SNAPSHOT build 16 | Native optional adapter loaded; HSQLDB, commands, aliases, authenticated Paper Agent synchronization, reconnect, plugin listing, and clean shutdown passed |
| MySQL | 8.4.10 | Tables, punishment creation, direct SQL inspection, restart, and persisted lookup passed |

Minecraft 26.2 and current Paper require Java 25. Core, Bukkit, BungeeCord, and the legacy bundle's project/runtime classes remain Java 8 bytecode (`major version 52`) for compatibility with older deployments. The optional self-contained Velocity artifact is separate and compiled for Java 25 (`major version 69`).

## Command compatibility

The runtime and Bukkit descriptor contain the same 23 primary commands and the same three additional aliases as AdvancedBan 2.3.0. Golden tests compare every command name, alias, permission, usage-message path, representative valid input, and representative invalid input.

The preserved command surface is:

`ban`, `tempban`, `ipban` (`banip`, `ban-ip`), `tempipban` (`tipban`), `mute`, `tempmute`, `warn`, `tempwarn`, `note`, `kick`, `unban`, `unmute`, `unwarn`, `unnote`, `unpunish`, `change-reason`, `banlist`, `history`, `warns`, `notes`, `check`, `systemprefs`, and `advancedban`.

Time forms remain `w`, `d`, `h`, `m`, `s`, `mo`, and `#layout`, with the existing argument order and optional `-s` position. Tests cover insufficient arguments, invalid and overflowing durations, list page bounds, unknown targets, aliases, console registration, and tab completion.

## Permission compatibility

A mechanical source extraction found the same 32 literal or template `ab.*` permission strings in the baseline and current Bukkit/Bungee/core implementation. Tests cover exact nodes, `ab.*`, optional `ab.<category>.all`, `ab.all`, undo permissions, and command/tab-completion decisions.

The historical `ab.notes.other` typo continues to authorize the `/warns` completion behavior, while the intended `ab.warns.other` is also accepted. Existing LuckPerms assignments therefore do not need to be changed. Velocity uses the same core permission checks and can optionally query offline users through LuckPerms 5 without making LuckPerms a required dependency.

When LuckPerms is installed on Velocity, AdvancedBan Neo now exposes its complete deterministic permission manifest during proxy startup. This includes command nodes, `ab.*`, optional `.all` nodes, notifications, duration levels, and exemption levels such as `ab.mute.exempt.9`. LuckPerms therefore knows the full tree before an administrator executes each feature for the first time. Registration performs permission checks only; it does not create groups, grant nodes, or alter existing LuckPerms data.

## Fixed defects

- Replaced regular-expression substitutions in messages and warning actions with literal substitutions, preserving `$`, backslashes, and similar text.
- Made duration parsing exact and overflow-safe while retaining the 2.3.0 input grammar.
- Removed concurrent modification and invalid-page failures in punishment lists.
- Made UUID, missing-result, database-startup, and shutdown paths null-safe.
- Added JDBC and HTTP connection/read timeouts and the current MySQL Connector/J driver.
- Stored the active punishment and history row in one transaction.
- Prevented false success messages, cache removal, and revoke events when database create/update/delete operations fail or affect no row.
- Moved Bukkit API access from asynchronous command work onto the server thread without changing the public API.
- Made BungeeCord login-intent completion exception-safe and fail-closed when configured.
- Replaced lossy BungeeCord punishment payload text with JSON while continuing to accept the old payload; nullable reasons, absolute expiry, and silent state round-trip.
- Corrected Bukkit IP BanList targets and permanent expiry handling.
- Removed unavailable CloudNet build dependencies while retaining CloudNet v2/v3 and CloudPerms discovery through optional reflective adapters.
- Serialized duplicate BAN/MUTE creation in one JVM and, on MySQL, across upgraded proxy processes with a schema-free advisory lock.
- Made multi-item `unwarn clear` and `unnote clear` database changes atomic.
- Made expiry cleanup retry-safe, periodic for loaded entries, and consistent for cached ID lookups.
- Removed all cached copies by database ID and isolated post-commit platform/listener failures from persisted state.
- Added immediate RedisBungee cache invalidation and an optional shared-MySQL refresh mode for Bungee/Velocity networks.
- Serialized database-backed cache refreshes with local cache updates so an older Redis/MySQL snapshot cannot overwrite a newer punishment state.
- Registered the complete Velocity permission surface with LuckPerms at startup, eliminating order-dependent tree discovery and first-use wildcard misses.
- Added explicit Standalone Authority, Coordinator Authority, Agent, and Degraded Agent roles without changing standalone defaults.
- Added a player-independent authenticated Velocity/Paper transport with one explicitly shared external credential, HMAC-SHA-256, fresh nonces, session keys, target/source checks, monotonic sequences, replay rejection, reconnect snapshots, and idempotent mutation request IDs.
- Prevented Paper Agents from initializing or accessing punishment databases and delegated built-in commands and legacy mutation APIs to Velocity without local fallback.
- Added optional ChatSyncer ChatEventBus and vNext pre-send gates backed only by the thread-safe in-memory mute view.
- Made `Network.Enabled` the explicit source of truth: disabled Paper remains standalone even with a key, enabled Paper is an Agent, and Velocity listens only while enabled.
- Persisted Paper pairing state so a missing key cannot create a second Authority.
- Added heartbeat deadlines, handshake cleanup, bounded asynchronous writers, and per-node atomic request idempotency.
- Made Agent snapshot replacement atomic and removed the login/revoke race that could resurrect stale punishments.
- Made ChatSyncer registration all-or-nothing, excluded Discord/system origins, and prevented duplicate chat feedback.
- Coalesced Coordinator bulk revocations into one snapshot diff/broadcast instead of rescanning the full punishment table for every deleted row.
- Bound the Agent handshake to a server-first random challenge and made readiness require complete active and history snapshots.
- Added authenticated-inbound Agent liveness tracking and reconnect-safe full state restoration. With shared-key automatic pairing, Velocity keeps global enforcement active while a disconnected Paper Agent continues from its last received state without locking players out; backend-name routing is not inferred from the Agent's internal UUID.
- Returned Authority command output to the originating Paper console and added non-blocking Authority-backed Paper tab completion.
- Synchronized historical rows for DB-less Agents so legacy history/note/warn reads do not regain database access.
- Made Velocity Authority database initialization and snapshot reads fail closed instead of starting with an empty authoritative state.
- Added Geyser/Floodgate-aware login UUID resolution and a separate persistent name-to-UUID cache, without changing the punishment database schema.

## Automated verification

The current Java 25 reactor run executed 149 test cases: 147 passed, with zero failures or errors. Two MySQL integration cases were skipped because no MySQL service was configured in this run:

- Core: 97 (including the two skipped MySQL integration cases)
- Bukkit/Paper: 31
- BungeeCord: 5
- Velocity: 16

The MySQL tests use two independent pools to verify advisory-lock serialization and rollback when one row in a batch delete is missing; run them with a configured MySQL 8.4 service. Artifact verification also confirmed descriptors, Java 8/25 bytecode boundaries, database drivers, relocation, and optional API isolation.

The generated legacy bundle contains the Bukkit and Bungee descriptors, both database drivers, and only Java 8-compatible classes. The separate self-contained Velocity artifact contains `velocity-plugin.json` and the same storage implementation. CI builds with Java 25, runs `clean verify`, and publishes both artifacts using current GitHub Actions versions. The release workflow's separate Javadoc phase is also reproducible from reactor-installed artifacts.

## Installation and synchronization model

Velocity support is optional. Install the artifact for the platform responsible for network-wide punishments:

- Bukkit/Paper-only network: install `AdvancedBan-Neo` on the server.
- BungeeCord network: install `AdvancedBan-Neo` on BungeeCord.
- Velocity without Paper enforcement: install `AdvancedBan-Neo-Velocity` on Velocity.
- Velocity with Paper/ChatSyncer enforcement: install the Velocity artifact on Velocity and the same legacy-compatible artifact on each Paper server, then pair those Paper copies as Agents.

Detailed pairing, credential rotation, DB placement, degraded behavior, and ChatSyncer operation are documented in [the Authority/Agent guide](AUTHORITY-AGENT.md).

Authority/Agent networking requires no separate `network.yml`. Set `Network.Enabled: true` explicitly on both sides and keep the shared secret in the external relative `KeyFile`. Both sides default to `127.0.0.1:27785`; deployments needing a different address use `Network.BindHost` / `Network.Port` on Velocity and `Network.CoordinatorHost` / `Network.CoordinatorPort` on Paper.

For multiple proxy instances, point each instance at the same MySQL database. RedisBungee-enabled Bungee instances invalidate one another immediately. For multi-Velocity networks, or a Bungee/Velocity transition where both proxy types are live, add the following optional key to each proxy's existing `config.yml`:

```yaml
MySQLCacheSyncInterval: 1
```

The value is the refresh interval in seconds. It is disabled when absent or `0`, so every unmodified AdvancedBan 2.3.0 configuration retains its previous behavior. When enabled, only online-player caches are refreshed; storage files, tables, commands, and permissions are unchanged. The native Velocity adapter remains a separate optional artifact and never requires BungeeCord.

## Validation notes

The following combinations were not available for full end-to-end automation and are not claimed as completed:

- Live E2E was run with Paper 26.2 build 129, Velocity 4.1.0, Floodgate 2.2.5, Geyser 2.11.3, and the release bundle/Velocity artifacts. A 26.2 Java client connected through Velocity modern forwarding to a Paper Agent; the Agent authenticated, `/check` preserved the permission-denied response, an Agent-issued `/note` was visible in Velocity history, and an Agent-issued `/ban` kicked the online player and rejected the next login. After restarting Velocity, the same BAN was still enforced from its local HSQLDB. Geyser/Floodgate both loaded and Geyser bound its UDP listener with Floodgate authentication configured. A real Bedrock/Xbox-authenticated client login was not available, so Floodgate's Bedrock identity path is not claimed as live-client verified.
- Live LuckPerms offline-user resolution on Velocity and a LuckPerms-only `ab.*` test server.
- A live RedisBungee/Velocity mixed network and CloudNet v2/v3. Shared-MySQL locking and rollback are covered with MySQL 8.4 integration tests, but multi-process player E2E remains manual.
- MySQL 8.4 backed multi-process synchronization and a real Bedrock/Xbox client session remain unverified in this local E2E run.
- An unmodified existing AdvancedBan 2.3.0 HSQLDB data set was opened on Paper 26.2, read, mutated, restarted, and read again without conversion.
- ChatSyncer `0.2.0-beta.10` runtime validation. That artifact was not available in the workspace or public repositories; the supplied beta.10 API contract matches the available beta.2 source/JAR and is covered by a public-API fixture, but a beta.10 server must still be smoke-tested when the artifact is supplied.
- Third-party plugin binary tests beyond reflection checks of the 2.3.0 public compatibility surface.
- The historical Bungee-to-Bukkit `advancedban:main` sender has no authenticated Bukkit receiver in 2.3.0. Enabling a receiver without a shared secret would let client plugin messages attempt native BanList changes, so this PR does not claim plugin messaging as a secure synchronization transport. Proxy enforcement plus shared MySQL is the supported network model.
- BungeeCord and Velocity currently publish the required development APIs as snapshot coordinates. Pinning a timestamped snapshot would eventually become unavailable under upstream retention, so the POM follows the named upstream snapshot; organizations requiring hermetic builds should mirror the resolved artifacts internally.
- Direct third-party calls to the legacy public `Punishment.create(...)` API intentionally retain 2.3.0 semantics and do not perform the command layer's duplicate check. Built-in commands use the new local/MySQL lock.
- Legacy void mutation APIs invoked on a Paper Agent dispatch to the Authority without blocking Bukkit's main thread. New result-bearing asynchronous overloads are available for callers that need completion; a synchronous checked call on the main thread fails closed because it cannot safely wait for the network.
- Paper Agent tab completion is asynchronously resolved with Velocity permissions and briefly cached; the first request for a new input may be empty while the Authority result is in flight.

These gaps and retained limitations require no command, permission, configuration, or data migration. No incompatible workaround was introduced.

## Primary compatibility references

- [Minecraft Java Edition 26.2 release](https://www.minecraft.net/article/minecraft-java-edition-26-2)
- [Paper project setup and dependency versioning](https://docs.papermc.io/paper/dev/project-setup/)
- [Paper and Minecraft Java requirements](https://docs.papermc.io/paper/getting-started/)
- [Velocity supported Minecraft versions](https://docs.papermc.io/velocity/server-compatibility/)
- [Velocity plugin, command, and event development](https://docs.papermc.io/velocity/dev/creating-your-first-plugin/)
