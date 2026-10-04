# Velocity mutes and signed chat

## Cause and supported configuration

Minecraft 1.19.1+ signed chat has a message chain. A bare Velocity
`PlayerChatEvent.ChatResult.denied()` does not synchronize that chain with the
backend. Velocity can disconnect the sender with an illegal protocol state
error. Both the supplied `AdvancedBanNeo-master.zip` (commit `989d4fa`) and this
project reject muted proxy chat in `PlayerInputListenerVelocity.onChat`.
This is required enforcement, but needs a signed-chat transport adapter.

AdvancedBan Neo retains the proxy rejection and formally requires
**matching SignedVelocity-Proxy and SignedVelocity-Paper 1.5.0 or newer on
Velocity and EVERY Paper backend** for the current Minecraft 26.2 network.
This applies with or without AdvancedBan Paper Agents, including a network
where AdvancedBan runs only on Velocity. The SignedVelocity Paper component is
still required there. No AdvancedBan database is needed on backends.

SignedVelocity's final proxy listener runs at `Short.MIN_VALUE`, after
AdvancedBan's priority 100 listener. It transports the decision to the current
backend and restores the proxy event to allowed so the signed packet reaches
Paper without Velocity's protocol-state rejection. SignedVelocity Paper
consumes the decision at `AsyncChatEvent` LOWEST and cancels delivery there.
The restored proxy event is transport bookkeeping: it does not authorize
broadcasting a muted message. AdvancedBan must not reset it again afterwards.

Selected baseline:

| Component | Baseline | Verification in this change |
| --- | --- | --- |
| Build/runtime Java | JDK 25.0.2 | Maven automated tests |
| Velocity API | 4.1.0-SNAPSHOT | Adapter compilation and listener tests |
| Paper API | 26.2.build.87-stable | Adapter compilation and listener tests |
| Minecraft client / Paper runtime | 26.2 | Manual acceptance matrix below; not executed in this change |
| SignedVelocity Proxy + Paper | 1.5.0 matching pair | Upstream source inspection; manual packet-level validation required |
| ChatSyncer | 0.2.0-beta.10 public mute pipeline | Existing reflection integration tests |

The existing compatibility report's Paper build 111 / Velocity build 16 results
predate this change and are not signed-chat acceptance evidence. No running
server configuration or installed SignedVelocity version was supplied here;
local repository files cannot establish what is installed on the user's network.

SignedVelocity 1.5.0 fixes late queue results and targets modern Paper 26.1+;
its Paper build uses NMS, so API compilation alone cannot prove compatibility
with a particular Paper 26.2 build. Test the exact deployment builds before
production. Do not deploy Proxy versions below 1.3.0: upstream documents an
input replacement vulnerability. For this target, versions below 1.5.0 also
fall below our queue compatibility baseline. Do not apply this 1.5.0 Paper
requirement to legacy 1.20/1.21 servers; upstream removed those versions.

Sources inspected:

- [PaperMC Velocity FAQ](https://docs.papermc.io/velocity/faq/#plugins-unable-to-modify-messages-or-commands)
- [SignedVelocity installation and security notice](https://github.com/4drian3d/SignedVelocity)
- [SignedVelocity 1.5.0 release](https://github.com/4drian3d/SignedVelocity/releases/tag/1.5.0)
- [Proxy listener at 1.5.0](https://github.com/4drian3d/SignedVelocity/blob/1.5.0/velocity/src/main/java/io/github/_4drian3d/signedvelocity/velocity/listener/PlayerChatListener.java)
- [Paper listener at 1.5.0](https://github.com/4drian3d/SignedVelocity/blob/1.5.0/backend/paper/src/main/java/io/github/_4drian3d/signedvelocity/paper/listener/PlayerChatListener.java)

## Installation and diagnosis

1. Back up configuration and data. Stop the proxy and backends.
2. Install the AdvancedBan Neo Velocity artifact on the proxy. If using Agents,
   install the legacy bundle on Paper and retain the authenticated pairing
   configuration described in [the Agent guide](AUTHORITY-AGENT.md).
3. Install matching SignedVelocity-Proxy and SignedVelocity-Paper releases on
   the proxy and every backend, including lobbies, fallback and transfer targets.
   Remove obsolete duplicate versions. Use the upstream official downloads.
4. Start backends and proxy. Confirm SignedVelocity enabled successfully in all
   logs. AdvancedBan logs a warning for missing/disabled, unknown/prerelease, or
   below-baseline local versions on Velocity and paired Paper Agents. A local
   detection message explicitly asks the operator to check all other nodes.
5. Run the full acceptance matrix below with real signed clients. Record exact
   client, proxy, Paper and SignedVelocity builds and the results.

Diagnostics are advisory and never turn off proxy mute enforcement. Dependency
metadata is optional to preserve older standalone deployments. A missing
adapter can therefore still cause the original disconnect; the supported
no-disconnect guarantee is conditional on the complete verified installation.
Metadata is not an authenticated backend capability handshake: proxy detection
cannot prove Paper installation, matching versions, enabled listeners or healthy
queues. An unpaired Paper running no SignedVelocity does not emit this new local
warning. Other chat plugins must respect cancelled events; plugins that
uncancel or manually broadcast cancelled chat can bypass backend cancellation.
Hot reload of either plugin is unsupported; restart the network after changes.

## Paper enforcement and failure behavior

Paper with the modern API registers only `PaperChatListener` on `AsyncChatEvent`,
instead of a legacy `AsyncPlayerChatEvent` listener. The handler runs at LOW:
after SignedVelocity LOWEST and before ChatSyncer's NORMAL routing. It never
uncancels events or repeats warnings on already cancelled events. A healthy
complete ChatSyncer pre-send hook continues to own AdvancedBan mute decisions;
without that hook the normal Paper mute check runs before routing can broadcast.
On Bukkit without the modern Paper API the existing legacy listener remains.

Proxy punishment enforcement remains enabled even if an Agent is disconnected.
Paper `Network.FailClosed` defaults to `true`: a paired Paper Agent denies chat
until an authenticated snapshot is ready, with no database fallback. Operators
who explicitly set it to `false` accept enforcement from only the last known
snapshot during an outage. Velocity does not reject backend routing based on an
Agent UUID because the shared key does not bind that UUID to a backend name.
SignedVelocity does not replace the authenticated AdvancedBan Agent transport.

## Regression and acceptance procedure

Use an isolated Velocity plus **two** Paper servers (A and B), a signed vanilla
26.2 client M and an unmuted receiver R. Use real online-mode authentication
and normal modern forwarding; do not disable signing/reporting to hide the
problem. Set up a channel visible across A/B if ChatSyncer is installed.

| Case | Action | Required observation |
| --- | --- | --- |
| Original failure | In the isolated network remove SignedVelocity, apply `/mute M reason`, have M chat | Proxy denial remains; reproduce protocol-state disconnect where the protocol enforces it; startup warning names the remedy |
| Correct installation | Install matching adapters everywhere, mute M, send several messages including rapid consecutive messages | M stays connected and sees punishment feedback; R on A and then B receives none |
| Proxy-only AdvancedBan | Remove AdvancedBan Paper from A/B, keep SignedVelocity everywhere, repeat mute | No chat delivery, no disconnect; no dependence on an Agent |
| Agent network | Pair both Agents, repeat mute and `/tempmute` before and after expiry | Denied while active, delivered after expiry, no disconnect |
| Transfer/reconnect | While muted move M between A/B, reconnect, restart the network | Mute persists and no delivery or protocol disconnect on either backend |
| Agent transport loss | With healthy SignedVelocity, sever one Agent's Authority connection | Proxy still rejects muted chat; degraded Paper gate rejects chat; no JDBC fallback; new routes remain denied until resnapshot ACK |
| Unmute | `/unmute M`, send normal and rapid chat | Delivery resumes once; no stale cancellation from the preceding muted message |
| ChatSyncer enabled | Repeat with public channel and supported PRIVATE path | No muted public/channel/private output; complete pre-send hook owns its decisions; no duplicate public chat warning |
| ChatSyncer absent/incomplete | Disable ChatSyncer or make one public mute API unavailable | Paper fallback cancels before normal routing; incomplete hooks roll back |
| Adapter missing on B / mismatched | Remove/disable Paper adapter on B or use an obsolete local version in the isolated network | Treat as failed deployment even if proxy looks healthy; local Agent logs warn where observable; never assert no-disconnect or no-leak acceptance |
| Legacy client / Bukkit | Where supported, exercise a pre-signed client and standalone Bukkit fallback | Existing punishment enforcement continues without a new mandatory runtime dependency |

For configured muted commands, also test `/msg` and each `MuteCommands` entry.
Command coverage depends on the configured command list and ChatSyncer's PRIVATE
gate; this change does not make every third-party messaging plugin compatible.

Automated checks cover proxy denied/allowed/previously denied decisions and
ordering, modern Paper cancellation, preserving SignedVelocity cancellation,
ChatSyncer deferral and routing priority, local version diagnostics and optional
load dependencies. Existing Agent and ChatSyncer tests cover degraded-state
fail-closed behavior and hook rollback. These tests do not emulate Minecraft's
signed packet chain or prove that SignedVelocity's NMS hooks work on a real
server; the acceptance matrix is required for that claim.

```sh
mvn -B clean verify
bash scripts/verify-artifacts.sh
```

## Validation recorded for this change (2026-10-04)

After integrating the latest `master` (AdvancedBan Neo 2.4.0-beta.1),
`mvn -B -ntp clean verify` passed on JDK 25.0.2 across all six modules:
Core 100, Bukkit 36, Bungee 5, Velocity 20; 161 passed, 0 failed, 0 errors,
2 skipped. The skipped core tests require a configured MySQL integration
service. `bash scripts/verify-artifacts.sh` passed for legacy Java 8 and
Velocity Java 25 bytecode boundaries, descriptors and optional API isolation.
The 12 targeted signed-chat diagnostics/Proxy/Paper tests passed within that
suite. No signed vanilla client or real SignedVelocity/Paper packet-chain
acceptance run was performed; these unit results do not establish runtime
packet-chain compatibility.
