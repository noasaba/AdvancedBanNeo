package me.leoko.advancedban.bukkit.listener;

import me.leoko.advancedban.bukkit.event.PunishmentEvent;
import me.leoko.advancedban.bukkit.event.RevokePunishmentEvent;
import me.leoko.advancedban.utils.PunishmentType;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.Date;

/**
 *
 * @author Beelzebu
 */
public class InternalListener implements Listener {
    
    @EventHandler
    public void onPunish(PunishmentEvent e) {
        Date expiration = getExpiration(e.getPunishment().getType(), e.getPunishment().getEnd());
        BanList banlist;
        if (e.getPunishment().getType().equals(PunishmentType.BAN) || e.getPunishment().getType().equals(PunishmentType.TEMP_BAN)) {
            banlist = Bukkit.getBanList(BanList.Type.NAME);
            banlist.addBan(e.getPunishment().getName(), e.getPunishment().getReason(), expiration, e.getPunishment().getOperator());
        } else if (e.getPunishment().getType().equals(PunishmentType.IP_BAN) || e.getPunishment().getType().equals(PunishmentType.TEMP_IP_BAN)) {
            banlist = Bukkit.getBanList(BanList.Type.IP);
            banlist.addBan(getBanTarget(e.getPunishment().getType(), e.getPunishment().getName(), e.getPunishment().getUuid()), e.getPunishment().getReason(), expiration, e.getPunishment().getOperator());
        }
    }
    
    @EventHandler
    public void onRevokePunishment(RevokePunishmentEvent e) {
        BanList banlist;
        if (e.getPunishment().getType().equals(PunishmentType.BAN) || e.getPunishment().getType().equals(PunishmentType.TEMP_BAN)) {
            banlist = Bukkit.getBanList(BanList.Type.NAME);
            banlist.pardon(e.getPunishment().getName());
        } else if (e.getPunishment().getType().equals(PunishmentType.IP_BAN) || e.getPunishment().getType().equals(PunishmentType.TEMP_IP_BAN)) {
            banlist = Bukkit.getBanList(BanList.Type.IP);
            banlist.pardon(getBanTarget(e.getPunishment().getType(), e.getPunishment().getName(), e.getPunishment().getUuid()));
        }
    }

    static Date getExpiration(PunishmentType type, long end) {
        return type.isTemp() ? new Date(end) : null;
    }

    static String getBanTarget(PunishmentType type, String name, String uuid) {
        return type.isIpOrientated() ? uuid : name;
    }
}
