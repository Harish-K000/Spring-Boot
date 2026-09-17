package com.example.platform.agent.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentSafetyPolicyTest {
    @Test void onlyOneAgentRequestCanHoldTheSlotAndItIsReleasedAfterward() {
        var policy = new AgentSafetyPolicy();
        try (var first = policy.enter(AgentSafetyPolicy.Mode.REVIEW)) {
            assertThatThrownBy(() -> policy.enter(AgentSafetyPolicy.Mode.CHAT))
                    .isInstanceOf(AgentSafetyPolicy.RequestBusyException.class);
            first.budget().claim(AgentSafetyPolicy.Capability.SECURITY_SCAN);
        }
        try (var next = policy.enter(AgentSafetyPolicy.Mode.CHAT)) {
            assertThat(next.budget().usedCount()).isZero();
        }
    }

    @Test void chatBudgetAllowsFiveFixedToolsOnceAndDeniesExtraActions() {
        var budget = AgentSafetyPolicy.newBudget(AgentSafetyPolicy.Mode.CHAT);
        assertThatThrownBy(() -> budget.claim(AgentSafetyPolicy.Capability.EDIT_CODE))
                .isInstanceOf(AgentSafetyPolicy.DeniedCapabilityException.class);
        assertThatThrownBy(() -> budget.claim(AgentSafetyPolicy.Capability.ARBITRARY_SHELL))
                .isInstanceOf(AgentSafetyPolicy.DeniedCapabilityException.class);
        assertThatThrownBy(() -> budget.claim(AgentSafetyPolicy.Capability.AZURE_ACCESS))
                .isInstanceOf(AgentSafetyPolicy.DeniedCapabilityException.class);
        assertThatThrownBy(() -> budget.claim(AgentSafetyPolicy.Capability.SECURITY_SCAN))
                .isInstanceOf(AgentSafetyPolicy.DeniedCapabilityException.class);
        budget.claim(AgentSafetyPolicy.Capability.GIT_DIFF);
        assertThatThrownBy(() -> budget.claim(AgentSafetyPolicy.Capability.GIT_DIFF))
                .isInstanceOf(AgentSafetyPolicy.ToolLimitException.class);
        budget.claim(AgentSafetyPolicy.Capability.CHANGED_FILES);
        budget.claim(AgentSafetyPolicy.Capability.SOURCE_READ);
        budget.claim(AgentSafetyPolicy.Capability.COMPILE);
        budget.claim(AgentSafetyPolicy.Capability.TEST);
        assertThat(budget.usedCount()).isEqualTo(5);
    }
}
