package me.leoko.advancedban.velocity.event;

import me.leoko.advancedban.utils.Punishment;

public final class RevokePunishmentEvent {
    private final Punishment punishment;
    private final boolean massClear;

    public RevokePunishmentEvent(Punishment punishment, boolean massClear) {
        this.punishment = punishment;
        this.massClear = massClear;
    }

    public Punishment getPunishment() {
        return punishment;
    }

    public boolean isMassClear() {
        return massClear;
    }
}
