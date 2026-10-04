# AdvancedBan Neo


Bukkit/Paper, BungeeCord, and optionally Velocity from the same project. <br>
Check out our [Spigot-Page](https://www.spigotmc.org/resources/advancedban.8695/) for more  information!

![Minecraft Version 1.7-1.13](https://img.shields.io/badge/supports%20minecraft%20versions-1.7--1.16-brightgreen.svg)
![license GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-lightgrey.svg)
[![CircleCI](https://circleci.com/gh/DevLeoko/AdvancedBan.svg?style=svg)](https://circleci.com/gh/DevLeoko/AdvancedBan)

_Original author: Leoko_<br>
_Maintainer: nanosize (noasaba)_

## Description
AdvancedBan Neo is a compatibility-focused continuation of AdvancedBan, the All-In-One Punishment-System with warns, tempwarns, mutes, tempmutes, bans, tempbans, ipbans and kicks.
There is also a PlayerHistory so you can see the players past punishments and 
the plugin has configurable Time & Message-Layouts which automatically calculate and increase the Punishment-Time for certain reasons.
AdvancedBan provides also a full Message-File so you can change and translate all messages & a detailed config-file with a lot of useful settings.
This project supports BungeeCord, Velocity, and Bukkit/Paper with MySQL and Local-File-Storage.

## Minecraft 26.2 and Velocity

The legacy-compatible `AdvancedBan-Neo` bundle supports Paper 26.2 and current BungeeCord. Velocity support is an optional, native adapter distributed as `AdvancedBan-Neo-Velocity`: place that JAR in Velocity's `plugins` directory instead of installing BungeeCord. It reuses the AdvancedBan 2.3.0 commands, aliases, `ab.*` permissions, configuration files, messages, layouts, HSQLDB storage, and MySQL schema without conversion.

Velocity does not have to be installed and is not injected into an existing BungeeCord setup. Paper-only installations remain standalone Authorities with no new configuration. In a Velocity network, the Velocity artifact can be the sole persistent Authority while the same Paper artifact runs as a DB-less Agent. Agents receive authenticated snapshots and incremental updates over a player-independent connection; Velocity may use either the existing local database or MySQL.

Networking is explicitly controlled by `Network.Enabled` in each platform's existing `config.yml`. Enable it on Velocity to generate/load the external `network.key`, copy that file into each Paper AdvancedBan data folder, then enable it on those Paper servers. A key file alone never changes Paper's role. No `network.yml` is required, and both sides default to `127.0.0.1:27785`.

Geyser/Floodgate logins use the UUID supplied by the platform login event when the proxy, Agent forwarding, or Floodgate API confirms that identity. AdvancedBan Neo also keeps a separate `uuid-cache.properties` file in its own data directory, so names learned from prior logins remain resolvable after restart without changing the punishment database schema. On Paper, install Floodgate on the backend as well when standalone offline-mode UUID lookup needs Floodgate identification; for an AdvancedBan Agent, the forwarded login UUID is used directly.

See [the Authority/Agent guide](docs/AUTHORITY-AGENT.md) for pairing and ChatSyncer setup, and [the 26.2 compatibility report](docs/COMPATIBILITY-26.2.md) for compatibility details.

### Signed chat and proxy mutes

For Minecraft 26.2, install matching **SignedVelocity-Proxy and SignedVelocity-Paper 1.5.0 or newer on Velocity and every Paper backend**, including when AdvancedBan runs only on Velocity. Proxy mute rejection remains active. Without the complete adapter installation, cancelling modern signed chat can disconnect muted players. Startup diagnostics report local missing or obsolete installations; they cannot verify other nodes. Paper uses its modern chat event and preserves SignedVelocity cancellation. Paper Agents default to denying chat until their authenticated snapshot is ready; `Network.FailClosed: false` is an explicit opt-out.

See [the signed-chat guide](docs/SIGNED-CHAT.md) for the cause, version limits, installation and the two-backend acceptance procedure. Real signed-client packet behavior must be validated against the exact deployment builds.

## API
To use the API you need to add AdvancedBan to your project and declare it as a dependency in the plugin.yml.

Add AdvancedBan to you project by adding the AdvancedBan.jar to your build-path or as a:
#### Maven dependency in your pom.xml

Example Usage from Jitpack:
```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>
...
<dependency>
  <groupId>com.github.DevLeoko</groupId>
  <artifactId>AdvancedBan</artifactId>
  <version>2.4.0-beta.1</version>
</dependency>
```
Note: Jitpack also supports dependencies for gradle!

[AdvancedBan on Jitpack](https://jitpack.io/#DevLeoko/AdvancedBan)


You can use this API for both Spigot and Bungeecord plugins.
Check out the [Java Docs](https://devleoko.github.io/AdvancedBan/) to get started.
