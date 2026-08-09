package me.leoko.advancedban.runtime;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeRoleInvariantTest {
    @Test
    void authorityAndAgentRolesAreDisjointAndExhaustive() {
        Set<RuntimeRole> authorities = Arrays.stream(RuntimeRole.values())
                .filter(RuntimeRole::isAuthority).collect(Collectors.toSet());
        Set<RuntimeRole> agents = Arrays.stream(RuntimeRole.values())
                .filter(RuntimeRole::isAgent).collect(Collectors.toSet());

        assertEquals(EnumSet.of(RuntimeRole.STANDALONE_AUTHORITY, RuntimeRole.COORDINATOR_AUTHORITY),
                authorities);
        assertEquals(EnumSet.of(RuntimeRole.AGENT, RuntimeRole.AGENT_DEGRADED), agents);
        assertTrue(authorities.stream().noneMatch(agents::contains));
        assertEquals(RuntimeRole.values().length, authorities.size() + agents.size());
    }

    @Test
    void degradedAgentNeverCarriesAuthorityCapability() {
        assertTrue(RuntimeRole.AGENT_DEGRADED.isAgent());
        assertFalse(RuntimeRole.AGENT_DEGRADED.isAuthority());
        assertTrue(RuntimeRole.AGENT.isAgent());
        assertFalse(RuntimeRole.AGENT.isAuthority());
    }
}
