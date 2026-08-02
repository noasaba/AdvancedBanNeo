# Minecraft 26.2 and AdvancedBan 2.3.0 compatibility report

## Scope and compatibility baseline

The compatibility baseline is AdvancedBan 2.3.0 at commit `69f3edabfd2f16a181e4c3b4958f3ad2bc3eaabc`. The implementation deliberately keeps the public version at 2.3.0 and avoids migration steps for existing installations.

The following 2.3.0 resources are byte-for-byte unchanged:

- `config.yml`, `Messages.yml`, and `Layouts.yml`
- Bukkit `plugin.yml` and BungeeCord `bungee.yml`
- HSQLDB format, MySQL table names and columns, punishment layouts, and JDBC configuration keys

The public `MethodInterface` methods from 2.3.0 remain callable. The legacy BungeeCord offline-permission provider classes also remain present as deprecated adapters.

## Platforms

| Platform | Build used | Result |
| --- | --- | --- |
| Paper | Minecraft 26.2, build 87 | Loaded, enabled, command/punishment checks, local persistence, expiry, and restart passed |
| BungeeCord | build 2085 | Loaded, enabled, commands and aliases passed; simultaneous proxy/backend startup passed |
| Velocity | 4.1.0-SNAPSHOT build 14 | Native optional adapter loaded; HSQLDB, commands, aliases, plugin listing, and clean shutdown passed |
| MySQL | 8.4.10 | Tables, punishment creation, direct SQL inspection, restart, and persisted lookup passed |

Minecraft 26.2 and current Paper require Java 25. Core, Bukkit, and BungeeCord classes remain Java 8 bytecode (`major version 52`) for compatibility with older deployments; the optional Velocity adapter is compiled for Java 25 (`major version 69`).

## Command compatibility

The runtime and Bukkit descriptor contain the same 23 primary commands and the same three additional aliases as AdvancedBan 2.3.0. Golden tests compare every command name, alias, permission, usage-message path, representative valid input, and representative invalid input.

The preserved command surface is:

`ban`, `tempban`, `ipban` (`banip`, `ban-ip`), `tempipban` (`tipban`), `mute`, `tempmute`, `warn`, `tempwarn`, `note`, `kick`, `unban`, `unmute`, `unwarn`, `unnote`, `unpunish`, `change-reason`, `banlist`, `history`, `warns`, `notes`, `check`, `systemprefs`, and `advancedban`.

Time forms remain `w`, `d`, `h`, `m`, `s`, `mo`, and `#layout`, with the existing argument order and optional `-s` position. Tests cover insufficient arguments, invalid and overflowing durations, list page bounds, unknown targets, aliases, console registration, and tab completion.

## Permission compatibility

A mechanical source extraction found the same 32 literal or template `ab.*` permission strings in the baseline and current Bukkit/Bungee/core implementation. Tests cover exact nodes, `ab.*`, optional `ab.<category>.all`, `ab.all`, undo permissions, and command/tab-completion decisions.

The historical `ab.notes.other` typo continues to authorize the `/warns` completion behavior, while the intended `ab.warns.other` is also accepted. Existing LuckPerms assignments therefore do not need to be changed. Velocity uses the same core permission checks and can optionally query offline users through LuckPerms 5 without making LuckPerms a required dependency.

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

## Automated verification

The Java 25 clean reactor build contains six modules and passes 38 tests:

- Core: 28
- Bukkit/Paper: 3
- BungeeCord: 5
- Velocity: 2

The generated bundle contains all three platform descriptors, both database drivers, and the platform entry points. CI builds with Java 25, runs `clean verify`, and publishes artifacts using current GitHub Actions versions. The release workflow's separate Javadoc phase is also reproducible from reactor-installed artifacts.

## Installation and synchronization model

Velocity support is optional. Install the combined JAR on exactly the platform responsible for network-wide punishments:

- Bukkit/Paper-only network: install it on the server.
- BungeeCord network: install it on BungeeCord.
- Velocity network: install it on Velocity.

For multiple proxy instances, point each instance at the same MySQL database. This provides the same database-backed punishment state used by BungeeCord deployments. The new adapter does not require BungeeCord and does not automatically bridge a simultaneously running BungeeCord and Velocity process.

## Remaining integration coverage

The following combinations were not available for full end-to-end automation and are not claimed as completed:

- A real Minecraft 26.2 client login/chat session. The available Mineflayer release rejected protocol `26.2` before connecting; proxy and backend startup were verified independently.
- Live LuckPerms offline-user resolution on Velocity and a LuckPerms-only `ab.*` test server.
- RedisBungee/Velocity, CloudNet v2/v3, and multiple live proxy instances sharing MySQL.
- Velocity-to-Bukkit plugin-message bridging. Velocity instead provides native proxy enforcement and database-backed synchronization.
- Third-party plugin binary tests beyond reflection checks of the 2.3.0 public compatibility surface.

These are test-environment gaps rather than known command, permission, or data migrations. No incompatible change was introduced to work around them.

## Primary compatibility references

- [Minecraft Java Edition 26.2 release](https://www.minecraft.net/article/minecraft-java-edition-26-2)
- [Paper project setup and dependency versioning](https://docs.papermc.io/paper/dev/project-setup/)
- [Paper and Minecraft Java requirements](https://docs.papermc.io/paper/getting-started/)
- [Velocity supported Minecraft versions](https://docs.papermc.io/velocity/server-compatibility/)
- [Velocity plugin, command, and event development](https://docs.papermc.io/velocity/dev/creating-your-first-plugin/)
