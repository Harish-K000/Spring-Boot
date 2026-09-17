package com.example.platform.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;


/** One approved test request per chat, including denied attempts. */
public class TestTool {
    private static final Logger log = LoggerFactory.getLogger(TestTool.class);
    private final TestRunner runner;
    private final AgentSafetyPolicy.Budget budget;

    public TestTool(TestRunner runner) {
        this(runner, AgentSafetyPolicy.newBudget(AgentSafetyPolicy.Mode.CHAT));
    }

    public TestTool(TestRunner runner, AgentSafetyPolicy.Budget budget) {
        this.runner = runner;
        this.budget = budget;
    }

    @Tool(description = "Run Maven tests for one registered service and required reactor modules. "
            + "Choose exactly one of auth-service, orders-service, inventory-service, payments-service, "
            + "edge-gateway, worker, engineering-agent. Returns fresh Surefire counts and up to five failures. "
            + "Skipped tests were not run. PASS requires fresh service reports and Maven exit 0. "
            + "Call at most once per request, including denied attempts.")
    public TestRunner.TestResult runTests(
            @ToolParam(description = "Exact registered service name, for example auth-service") String service) {
        budget.claim(AgentSafetyPolicy.Capability.TEST);
        var result = runner.run(service);
        log.info("Tool runTests: status={}, service={}, durationMs={}, exitCode={}, total={}, failed={}, errors={}, skipped={}, incomplete={}",
                result.status(), result.service(), result.durationMs(), result.exitCode(), result.total(),
                result.failed(), result.errors(), result.skipped(), result.incomplete());
        return result;
    }
}
