package me.leoko.advancedban.bungee.listener;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import me.leoko.advancedban.Universal;
import me.leoko.advancedban.bungee.event.PunishmentEvent;
import me.leoko.advancedban.bungee.event.RevokePunishmentEvent;
import me.leoko.advancedban.manager.TimeManager;
import me.leoko.advancedban.utils.Punishment;
import me.leoko.advancedban.utils.PunishmentType;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

import java.util.Arrays;
import java.util.List;

/**
 *
 * @author Beelzebu
 */
public class InternalListener implements Listener {

    private final Universal universal = Universal.get();

    @EventHandler
    public void onPunish(PunishmentEvent e) {
        sendToBukkit("Punish", Arrays.asList(serializePunishment(e.getPunishment())));
    }

    @EventHandler
    public void onUnPunish(RevokePunishmentEvent e) {
        sendToBukkit("Unpunish", Arrays.asList(serializePunishment(e.getPunishment())));
    }

    @EventHandler
    public void onPluginMessageEvent(PluginMessageEvent e) {
        if (!e.getTag().equals("advancedban:main")) {
            return;
        }
        if (e.getSender() instanceof ProxiedPlayer) {
            return;
        }
        ByteArrayDataInput in = ByteStreams.newDataInput(e.getData());
        String channel = in.readUTF();
        switch (channel) {
            case "Punish":
                String message = in.readUTF();
                try {
                    Punishment punishment = deserializePunishment(message);
                    new Punishment(
                            punishment.getName(), punishment.getUuid(), punishment.getReason(),
                            punishment.getOperator() != null ? punishment.getOperator() : "CONSOLE",
                            punishment.getType(), punishment.getStart(), punishment.getEnd(), punishment.getCalculation(),
                            -1
                    ).create(punishment.isSilent());
                    universal.log("A punishment was created using PluginMessaging listener.");
                    universal.debug(message);
                } catch (JsonParseException | IllegalArgumentException | NullPointerException ex) {
                    universal.log("An exception as occurred while reading a punishment from plugin messaging channel.");
                    universal.debug("Message: " + message);
                    universal.log("StackTrace:");
                    ex.printStackTrace();
                }
                break;
            default:
                universal.debug("Unknown channel for tag \"AdvancedBan\"");
                break;
        }
    }

    public void sendToBukkit(String channel, List<String> messages) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(channel);
        messages.forEach(out::writeUTF);
        ProxyServer.getInstance().getServers().keySet().forEach(server -> ProxyServer.getInstance().getServerInfo(server).sendData("advancedban:main", out.toByteArray(), true));
    }

    static String serializePunishment(Punishment punishment) {
        return Universal.get().getGson().toJson(punishment);
    }

    static Punishment deserializePunishment(String message) {
        JsonObject payload = Universal.get().getGson().fromJson(message, JsonObject.class);
        if (payload.has("type")) {
            return Universal.get().getGson().fromJson(payload, Punishment.class);
        }

        long end = payload.get("end").getAsLong();
        JsonElement reason = payload.get("reason");
        JsonElement operator = payload.get("operator");
        JsonElement start = payload.get("start");
        JsonElement calculation = payload.get("calculation");
        JsonElement silent = payload.get("silent");
        return new Punishment(
                payload.get("name").getAsString(), payload.get("uuid").getAsString(),
                reason == null || reason.isJsonNull() ? null : reason.getAsString(),
                operator == null || operator.isJsonNull() ? "CONSOLE" : operator.getAsString(),
                PunishmentType.valueOf(payload.get("punishmenttype").getAsString().toUpperCase()),
                start == null || start.isJsonNull() ? TimeManager.getTime() : start.getAsLong(),
                end == -1 ? -1 : TimeManager.getTime() + end,
                calculation == null || calculation.isJsonNull() ? null : calculation.getAsString(),
                -1, silent != null && !silent.isJsonNull() && silent.getAsBoolean());
    }
}
