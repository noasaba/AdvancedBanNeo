package me.leoko.advancedban.runtime;

/**
 * Defines which process owns punishment decisions and persistent state.
 */
public enum RuntimeRole {
    STANDALONE_AUTHORITY(true, false),
    COORDINATOR_AUTHORITY(true, false),
    AGENT(false, true),
    AGENT_DEGRADED(false, true);

    private final boolean authority;
    private final boolean agent;

    RuntimeRole(boolean authority, boolean agent) {
        this.authority = authority;
        this.agent = agent;
    }

    public boolean isAuthority() {
        return authority;
    }

    public boolean isAgent() {
        return agent;
    }
}
