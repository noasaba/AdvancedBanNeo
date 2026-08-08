package me.leoko.advancedban.utils;

import me.leoko.advancedban.MethodInterface;
import me.leoko.advancedban.Universal;
import me.leoko.advancedban.manager.DatabaseManager;
import me.leoko.advancedban.manager.MessageManager;
import me.leoko.advancedban.manager.PunishmentManager;
import me.leoko.advancedban.manager.TimeManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * Created by Leoko @ dev.skamps.eu on 30.05.2016.
 */
public class Punishment {

    private static final MethodInterface mi = Universal.get().getMethods();
    private final String name, uuid, operator, calculation;
    private final long start, end;
    private final PunishmentType type;

    private String reason;
    private int id;
    private boolean silent;

    public Punishment(String name, String uuid, String reason, String operator, PunishmentType type, long start, long end, String calculation, int id) {
        this(name, uuid, reason, operator, type, start, end, calculation, id, false);
    }

    public Punishment(String name, String uuid, String reason, String operator, PunishmentType type, long start,
                      long end, String calculation, int id, boolean silent) {
        this.name = name;
        this.uuid = uuid;
        this.reason = reason;
        this.operator = operator;
        this.type = type;
        this.start = start;
        this.end = end;
        this.calculation = calculation;
        this.id = id;
        this.silent = silent;
    }

    public static void create(String name, String target, String reason, String operator, PunishmentType type, Long end,
                              String calculation, boolean silent) {
        createChecked(name, target, reason, operator, type, end, calculation, silent);
    }

    public static boolean createChecked(String name, String target, String reason, String operator, PunishmentType type,
                                        Long end, String calculation, boolean silent) {
        return new Punishment(name, target, reason, operator, end == -1 ? type.getPermanent() : type,
                TimeManager.getTime(), end, calculation, -1).createChecked(silent);
    }

    /**
     * Command-only creation path that performs the duplicate check and write
     * atomically while retaining the public 2.3.0 create API semantics.
     */
    public static DatabaseManager.PunishmentCreationResult createCommandChecked(
            String name, String target, String reason, String operator, PunishmentType type,
            Long end, String calculation, boolean silent) {
        Punishment punishment = new Punishment(name, target, reason, operator,
                end == -1 ? type.getPermanent() : type, TimeManager.getTime(), end, calculation, -1);
        return punishment.createCommandChecked(silent);
    }

    public String getReason() {
        return (reason == null ? mi.getString(mi.getConfig(), "DefaultReason", "none") : reason).replaceAll("'", "");
    }

    public String getHexId() {
        return Integer.toHexString(id).toUpperCase();
    }

    public String getDate(long date) {
        SimpleDateFormat format = new SimpleDateFormat(mi.getString(mi.getConfig(), "DateFormat", "dd.MM.yyyy-HH:mm"));
        return format.format(new Date(date));
    }

    public void create() {
        create(false);
    }

    public void create(boolean silent) {
        createChecked(silent);
    }

    public boolean createChecked(boolean silent) {
        if (!validateForCreation()) {
            return false;
        }

        this.silent = silent;

        final int cWarnings = getType().getBasic() == PunishmentType.WARNING ? (PunishmentManager.get().getCurrentWarns(getUuid()) + 1) : 0;

        Integer persistedId = DatabaseManager.get().createPunishment(getType() == PunishmentType.KICK,
                getName(), getUuid(), getReason(), getOperator(), getType().name(),
                getStart(), getEnd(), getCalculation());
        if (persistedId == null) {
            logCreationFailure();
            return false;
        }
        id = persistedId;
        completeCreation(silent, cWarnings);
        return true;
    }

    private DatabaseManager.PunishmentCreationResult createCommandChecked(boolean silent) {
        if (!validateForCreation()) {
            return DatabaseManager.PunishmentCreationResult.failed();
        }

        PunishmentType basicType = getType().getBasic();
        if (basicType != PunishmentType.BAN && basicType != PunishmentType.MUTE) {
            return createChecked(silent)
                    ? DatabaseManager.PunishmentCreationResult.created(getId())
                    : DatabaseManager.PunishmentCreationResult.failed();
        }

        this.silent = silent;
        DatabaseManager.PunishmentCreationResult result = DatabaseManager.get().createPunishmentIfAbsent(
                getType() == PunishmentType.KICK, getUuid(), basicType, TimeManager.getTime(),
                getName(), getUuid(), getReason(), getOperator(), getType().name(),
                getStart(), getEnd(), getCalculation());
        if (result.getStatus() == DatabaseManager.PunishmentCreationResult.Status.CREATED) {
            id = result.getId();
            completeCreation(silent, 0);
        } else if (result.getStatus() == DatabaseManager.PunishmentCreationResult.Status.FAILED) {
            logCreationFailure();
        }
        return result;
    }

    private boolean validateForCreation() {
        if (id != -1) {
            Universal.get().log("!! Failed! AB tried to overwrite the punishment:");
            Universal.get().log("!! Failed at: " + toString());
            return false;
        }

        if (uuid == null) {
            Universal.get().log("!! Failed! AB has not saved the " + getType().getName() + " because there is no fetched UUID");
            Universal.get().log("!! Failed at: " + toString());
            return false;
        }
        return true;
    }

    private void logCreationFailure() {
        Universal.get().log("!! Failed to save punishment; no notification or action was emitted.");
        Universal.get().log("!! Failed at: " + toString());
    }

    private void completeCreation(boolean silent, int cWarnings) {
        PunishmentManager.get().addLoadedPunishment(this, false);

        if (!silent) {
            runPostCommit("announce punishment", () -> announce(cWarnings));
        }

        runPostCommit("apply punishment to an online player", () -> {
            if (mi.isOnline(getName())) {
                final Object player = mi.getPlayer(getName());

                if (getType().getBasic() == PunishmentType.BAN || getType() == PunishmentType.KICK) {
                    mi.runSync(() -> mi.kickPlayer(getName(), getLayoutBSN()));
                } else if (player != null) {
                    PunishmentManager.get().addLoadedPunishment(this, true);
                    if (getType().getBasic() != PunishmentType.NOTE) {
                        for (String str : getLayout()) {
                            mi.sendMessage(player, str);
                        }
                    }
                }
            }
        });

        runPostCommit("publish punishment update", () -> mi.publishPunishmentUpdate(getName(), getUuid()));
        runPostCommit("call punishment event", () -> mi.callPunishmentEvent(this));

        if (getType().getBasic() == PunishmentType.WARNING) {
            String cmd = null;
            for (int i = 1; i <= cWarnings; i++) {
                if (mi.contains(mi.getConfig(), "WarnActions." + i)) {
                    cmd = mi.getString(mi.getConfig(), "WarnActions." + i);
                }
            }
            if (cmd != null) {
                final String finalCmd = cmd.replace("%PLAYER%", getName()).replace("%COUNT%", cWarnings + "").replace("%REASON%", getReason());
                runPostCommit("schedule warning action", () -> mi.runSync(() -> {
                    runPostCommit("execute warning action", () -> {
                        mi.executeCommand(finalCmd);
                        Universal.get().log("Executing command: " + finalCmd);
                    });
                }));
            }
        }
    }

    public void updateReason(String reason) {
        updateReasonChecked(reason);
    }

    public boolean updateReasonChecked(String reason) {
        if (id == -1 || !DatabaseManager.get().executeStatementChecked(SQLQuery.UPDATE_PUNISHMENT_REASON, reason, id)) {
            return false;
        }
        this.reason = reason;
        return true;
    }

    private void announce(int cWarnings) {
        List<String> notification = MessageManager.getLayout(mi.getMessages(),
                getType().getName() + ".Notification",
                "OPERATOR", getOperator(),
                "PREFIX", mi.getBoolean(mi.getConfig(), "Disable Prefix", false) ? "" : MessageManager.getMessage("General.Prefix"),
                "DURATION", getDuration(true),
                "REASON", getReason(),
                "NAME", getName(),
                "ID", String.valueOf(id),
                "HEXID", getHexId(),
                "DATE", getDate(start),
                "COUNT", cWarnings + "");

        mi.notify("ab.notify." + getType().getName(), notification);
    }

    public void delete() {
        delete(null, false, true);
    }

    public void delete(String who, boolean massClear, boolean removeCache) {
        deleteChecked(who, massClear, removeCache);
    }

    public boolean deleteChecked(String who, boolean massClear, boolean removeCache) {
        if (getType() == PunishmentType.KICK) {
            Universal.get().log("!! Failed deleting! You are not able to delete Kicks!");
            return false;
        }

        if (id == -1) {
            Universal.get().log("!! Failed deleting! The Punishment is not created yet!");
            Universal.get().log("!! Failed at: " + toString());
            return false;
        }

        if (!DatabaseManager.get().executeStatementChecked(SQLQuery.DELETE_PUNISHMENT, getId())) {
            Universal.get().log("!! Failed deleting punishment from the database; cache and events were left unchanged.");
            return false;
        }

        completeDeletion(who, massClear, removeCache);
        return true;
    }

    /**
     * Deletes every supplied punishment in one database transaction and only
     * then updates caches, notifications, and existing revoke events.
     */
    public static boolean deleteAllChecked(List<Punishment> punishments, String who,
                                           boolean massClear, boolean removeCache) {
        if (punishments == null || punishments.isEmpty()) {
            return false;
        }

        List<Integer> ids = new ArrayList<>(punishments.size());
        for (Punishment punishment : punishments) {
            if (punishment == null || punishment.getType() == PunishmentType.KICK || punishment.getId() == -1) {
                return false;
            }
            ids.add(punishment.getId());
        }

        if (!DatabaseManager.get().deletePunishmentsAtomically(ids)) {
            return false;
        }
        for (Punishment punishment : punishments) {
            punishment.completeDeletion(who, massClear, removeCache);
        }
        return true;
    }

    private void completeDeletion(String who, boolean massClear, boolean removeCache) {
        if (removeCache) {
            PunishmentManager.get().removeLoadedPunishment(getId());
        }

        if (who != null) {
            runPostCommit("announce punishment revocation", () -> {
                String message = MessageManager.getMessage("Un" + getType().getBasic().getConfSection("Notification"),
                        true, "OPERATOR", who, "NAME", getName());
                mi.notify("ab.undoNotify." + getType().getBasic().getName(), Collections.singletonList(message));
                Universal.get().debug(who + " is deleting a punishment");
            });
        }

        runPostCommit("log punishment revocation", () -> Universal.get().debug(
                "Deleted punishment " + getId() + " from " + getName() + " punishment reason: " + getReason()));
        runPostCommit("publish punishment update", () -> mi.publishPunishmentUpdate(getName(), getUuid()));
        runPostCommit("call punishment revocation event", () -> mi.callRevokePunishmentEvent(this, massClear));
    }

    private void runPostCommit(String action, Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException exception) {
            Universal.get().log("A post-commit action failed while trying to " + action + '.');
            Universal.get().debugException(exception);
        }
    }

    public List<String> getLayout() {
        boolean isLayout = getReason().startsWith("@") || getReason().startsWith("~");

        return MessageManager.getLayout(
                isLayout ? mi.getLayouts() : mi.getMessages(),
                isLayout ? "Message." + getReason().split(" ")[0].substring(1) : getType().getName() + ".Layout",
                "OPERATOR", getOperator(),
                "PREFIX", mi.getBoolean(mi.getConfig(), "Disable Prefix", false) ? "" : MessageManager.getMessage("General.Prefix"),
                "DURATION", getDuration(false),
                "REASON", isLayout ? (getReason().split(" ").length < 2 ? "" : getReason().substring(getReason().split(" ")[0].length() + 1)) : getReason(),
                "HEXID", getHexId(),
                "ID", String.valueOf(id),
                "DATE", getDate(start),
                "COUNT", getType().getBasic() == PunishmentType.WARNING ? (PunishmentManager.get().getCurrentWarns(getUuid()) + 1) + "" : "0");
    }

    public String getDuration(boolean fromStart) {
        String duration = "permanent";
        if (getType().isTemp()) {
            long diff = ceilDiv(getEnd() - (fromStart ? start : TimeManager.getTime()), 1000L);
            if (diff > 60 * 60 * 24) {
                duration = MessageManager.getMessage("General.TimeLayoutD", getDurationParameter("D", diff / 60 / 60 / 24 + "", "H", diff / 60 / 60 % 24 + "", "M", diff / 60 % 60 + "", "S", diff % 60 + ""));
            } else if (diff > 60 * 60) {
                duration = MessageManager.getMessage("General.TimeLayoutH", getDurationParameter("H", diff / 60 / 60 + "", "M", diff / 60 % 60 + "", "S", diff % 60 + ""));
            } else if (diff > 60) {
                duration = MessageManager.getMessage("General.TimeLayoutM", getDurationParameter("M", diff / 60 + "", "S", diff % 60 + ""));
            } else {
                duration = MessageManager.getMessage("General.TimeLayoutS", getDurationParameter("S", diff + ""));
            }
        }
        return duration;
    }

    long ceilDiv(long x, long y) {
        return -Math.floorDiv(-x, y);
    }

    private String[] getDurationParameter(String... parameter) {
        int length = parameter.length;
        String[] newParameter = new String[length * 2];
        for (int i = 0; i < length; i += 2) {
            String name = parameter[i];
            String count = parameter[i + 1];

            newParameter[i] = name;
            newParameter[i + 1] = count;
            newParameter[length + i] = name + name;
            newParameter[length + i + 1] = (count.length() <= 1 ? "0" : "") + count;
        }

        return newParameter;
    }

    public String getLayoutBSN() {
        StringBuilder msg = new StringBuilder();
        for (String str : getLayout()) {
            msg.append("\n").append(str);
        }
        return msg.substring(1);
    }

    public boolean isExpired() {
        return getType().isTemp() && getEnd() <= TimeManager.getTime();
    }

    public String getName() {
        return this.name;
    }

    public String getUuid() {
        return this.uuid;
    }

    public String getOperator() {
        return this.operator;
    }

    public boolean isSilent() {
        return silent;
    }

    public String getCalculation() {
        return this.calculation;
    }

    public long getStart() {
        return this.start;
    }

    public long getEnd() {
        return this.end;
    }

    public PunishmentType getType() {
        return this.type;
    }

    public int getId() {
        return this.id;
    }

    public String toString() {
        return "Punishment(name=" + this.getName() + ", uuid=" + this.getUuid() + ", operator=" + this.getOperator() + ", calculation=" + this.getCalculation() + ", start=" + this.getStart() + ", end=" + this.getEnd() + ", type=" + this.getType() + ", reason=" + this.getReason() + ", id=" + this.getId() + ")";
    }
}
