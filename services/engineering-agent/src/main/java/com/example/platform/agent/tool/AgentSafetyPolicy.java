package com.example.platform.agent.tool;

import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import java.util.EnumSet;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** V1 capability allowlists and request limits. This is an application policy, not an OS sandbox. */
@Component
@Profile("!mcp-server")
public final class AgentSafetyPolicy {
    public enum Mode { CHAT, REVIEW }
    public enum Capability {
        GIT_DIFF, CHANGED_FILES, SOURCE_READ, COMPILE, TEST, SECURITY_SCAN, MODEL_ANALYSIS,
        EDIT_CODE, ARBITRARY_SHELL, COMMIT, PUSH, MERGE, DEPLOY, AZURE_ACCESS
    }

    private static final EnumSet<Capability> CHAT_ALLOWED = EnumSet.of(
            Capability.GIT_DIFF, Capability.CHANGED_FILES, Capability.SOURCE_READ,
            Capability.COMPILE, Capability.TEST);
    private static final EnumSet<Capability> REVIEW_ALLOWED = EnumSet.of(
            Capability.GIT_DIFF, Capability.CHANGED_FILES, Capability.SOURCE_READ,
            Capability.COMPILE, Capability.TEST, Capability.SECURITY_SCAN,
            Capability.MODEL_ANALYSIS);

    // A review may run Maven, three scanners and the local model; only one agent request starts at a time.
    private final Semaphore requestSlot = new Semaphore(1);

    public Permit enter(Mode mode) {
        if (!requestSlot.tryAcquire()) throw new RequestBusyException();
        return new Permit(requestSlot, newBudget(mode));
    }

    public static Budget newBudget(Mode mode) {
        return new Budget(mode, mode == Mode.CHAT ? CHAT_ALLOWED : REVIEW_ALLOWED);
    }

    public static final class Budget {
        private final Mode mode;
        private final EnumSet<Capability> allowed;
        private final EnumSet<Capability> used = EnumSet.noneOf(Capability.class);

        private Budget(Mode mode, EnumSet<Capability> allowed) {
            this.mode = mode;
            this.allowed = allowed.clone();
        }

        public synchronized void claim(Capability capability) {
            if (!allowed.contains(capability)) throw new DeniedCapabilityException();
            if (used.contains(capability) || used.size() >= allowed.size()) {
                throw new ToolLimitException();
            }
            used.add(capability);
        }

        public Mode mode() { return mode; }
        public synchronized int usedCount() { return used.size(); }
    }

    public static final class Permit implements AutoCloseable {
        private final Semaphore slot;
        private final Budget budget;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Permit(Semaphore slot, Budget budget) {
            this.slot = slot;
            this.budget = budget;
        }

        public Budget budget() { return budget; }

        @Override public void close() {
            if (closed.compareAndSet(false, true)) slot.release();
        }
    }

    public static class RequestBusyException extends RuntimeException {}
    public static class DeniedCapabilityException extends IllegalStateException {}
    public static class ToolLimitException extends IllegalStateException {}
}
