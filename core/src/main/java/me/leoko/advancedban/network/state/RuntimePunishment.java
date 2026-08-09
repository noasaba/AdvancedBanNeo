package me.leoko.advancedban.network.state;

import java.util.Objects;

/** Immutable subset of punishment data required for node-local enforcement. */
public final class RuntimePunishment {
    private final long id;
    private final String targetName;
    private final String targetUuid;
    private final String targetIp;
    private final RuntimePunishmentType type;
    private final String reason;
    private final String operator;
    private final long startMillis;
    private final long endMillis;
    private final boolean permanent;
    private final boolean silent;
    private final String calculation;

    public RuntimePunishment(long id, String targetUuid, String targetIp, RuntimePunishmentType type,
                             String reason, String operator, long startMillis, long endMillis,
                             boolean permanent, boolean silent, String calculation) {
        this(id, null, targetUuid, targetIp, type, reason, operator, startMillis, endMillis,
                permanent, silent, calculation);
    }

    public RuntimePunishment(long id, String targetName, String targetUuid, String targetIp,
                             RuntimePunishmentType type, String reason, String operator,
                             long startMillis, long endMillis, boolean permanent, boolean silent,
                             String calculation) {
        if (id < 0) {
            throw new IllegalArgumentException("id must not be negative");
        }
        this.targetName = nullableText(targetName);
        this.targetUuid = normalizeTarget(targetUuid);
        this.targetIp = normalizeTarget(targetIp);
        if (this.targetUuid == null && this.targetIp == null) {
            throw new IllegalArgumentException("a UUID or IP target is required");
        }
        if (!permanent && endMillis < startMillis) {
            throw new IllegalArgumentException("temporary punishment must not end before it starts");
        }
        this.id = id;
        this.type = Objects.requireNonNull(type, "type");
        this.reason = nullToEmpty(reason);
        this.operator = nullToEmpty(operator);
        this.startMillis = startMillis;
        this.endMillis = endMillis;
        this.permanent = permanent;
        this.silent = silent;
        this.calculation = nullToEmpty(calculation);
    }

    public boolean isActive(long nowMillis) {
        return startMillis <= nowMillis && (permanent || endMillis > nowMillis);
    }

    public boolean isExpired(long nowMillis) {
        return !permanent && endMillis <= nowMillis;
    }

    public long getId() {
        return id;
    }

    public String getTargetName() {
        return targetName;
    }

    public String getTargetUuid() {
        return targetUuid;
    }

    public String getTargetIp() {
        return targetIp;
    }

    public RuntimePunishmentType getType() {
        return type;
    }

    public String getReason() {
        return reason;
    }

    public String getOperator() {
        return operator;
    }

    public long getStartMillis() {
        return startMillis;
    }

    public long getEndMillis() {
        return endMillis;
    }

    public boolean isPermanent() {
        return permanent;
    }

    public boolean isSilent() {
        return silent;
    }

    public String getCalculation() {
        return calculation;
    }

    private static String normalizeTarget(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private static String nullableText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RuntimePunishment)) {
            return false;
        }
        RuntimePunishment that = (RuntimePunishment) other;
        return id == that.id && startMillis == that.startMillis && endMillis == that.endMillis
                && permanent == that.permanent && silent == that.silent
                && Objects.equals(targetName, that.targetName) && Objects.equals(targetUuid, that.targetUuid)
                && Objects.equals(targetIp, that.targetIp)
                && type == that.type && reason.equals(that.reason) && operator.equals(that.operator)
                && calculation.equals(that.calculation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, targetName, targetUuid, targetIp, type, reason, operator, startMillis, endMillis,
                permanent, silent, calculation);
    }
}
