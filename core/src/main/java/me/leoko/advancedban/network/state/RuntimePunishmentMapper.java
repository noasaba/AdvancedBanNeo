package me.leoko.advancedban.network.state;

import me.leoko.advancedban.utils.Punishment;
import me.leoko.advancedban.utils.PunishmentType;

/** Converts between the legacy public punishment and transport runtime form. */
public final class RuntimePunishmentMapper {
    private RuntimePunishmentMapper() {
    }

    public static RuntimePunishment fromLegacy(Punishment punishment) {
        boolean ip = punishment.getType().isIpOrientated();
        return new RuntimePunishment(punishment.getId(), punishment.getName(),
                ip ? null : punishment.getUuid(), ip ? punishment.getUuid() : null,
                RuntimePunishmentType.valueOf(punishment.getType().name()),
                punishment.getReason(), punishment.getOperator(), punishment.getStart(), punishment.getEnd(),
                !punishment.getType().isTemp(), punishment.isSilent(), punishment.getCalculation());
    }

    public static Punishment toLegacy(RuntimePunishment punishment) {
        String target = punishment.getTargetUuid() == null
                ? punishment.getTargetIp() : punishment.getTargetUuid();
        return new Punishment(punishment.getTargetName(), target, punishment.getReason(),
                punishment.getOperator(), PunishmentType.valueOf(punishment.getType().name()),
                punishment.getStartMillis(), punishment.getEndMillis(), punishment.getCalculation(),
                (int) punishment.getId(), punishment.isSilent());
    }
}
