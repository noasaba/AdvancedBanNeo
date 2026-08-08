# AdvancedBan


Bukkit/Paper, BungeeCord, and optionally Velocity from the same project. <br>
Check out our [Spigot-Page](https://www.spigotmc.org/resources/advancedban.8695/) for more  information!

![Minecraft Version 1.7-1.13](https://img.shields.io/badge/supports%20minecraft%20versions-1.7--1.16-brightgreen.svg)
![license GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-lightgrey.svg)
[![CircleCI](https://circleci.com/gh/DevLeoko/AdvancedBan.svg?style=svg)](https://circleci.com/gh/DevLeoko/AdvancedBan)

_Coded by Leoko_ 

## Description
AdvancedBan is an All-In-One Punishment-System with warns, tempwarns, mutes, tempmutes, bans, tempbans, ipbans and kicks.
There is also a PlayerHistory so you can see the players past punishments and 
the plugin has configurable Time & Message-Layouts which automatically calculate and increase the Punishment-Time for certain reasons.
AdvancedBan provides also a full Message-File so you can change and translate all messages & a detailed config-file with a lot of useful settings.
This project supports BungeeCord, Velocity, and Bukkit/Paper with MySQL and Local-File-Storage.

## Minecraft 26.2 and Velocity

The legacy combined bundle supports Paper 26.2 and current BungeeCord. Velocity support is an optional, native adapter distributed as `AdvancedBan-Velocity`: place that JAR in Velocity's `plugins` directory instead of installing BungeeCord. It reuses the AdvancedBan 2.3.0 commands, aliases, `ab.*` permissions, configuration files, messages, layouts, HSQLDB storage, and MySQL schema without conversion.

Velocity does not have to be installed and is not injected into an existing BungeeCord setup. For multiple proxies, configure the same MySQL database on each proxy. RedisBungee deployments receive immediate Bungee cache invalidations; mixed Bungee/Velocity or multi-Velocity networks can opt into shared-MySQL cache refresh by adding `MySQLCacheSyncInterval: 1` (seconds) to the existing config on each proxy. The setting is absent and disabled by default, so legacy installations keep their previous behavior. See [the 26.2 compatibility report](docs/COMPATIBILITY-26.2.md) for details.

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
  <version>v2.3.0</version>
</dependency>
```
Note: Jitpack also supports dependencies for gradle!

[AdvancedBan on Jitpack](https://jitpack.io/#DevLeoko/AdvancedBan)


You can use this API for both Spigot and Bungeecord plugins.
Check out the [Java Docs](https://devleoko.github.io/AdvancedBan/) to get started.
