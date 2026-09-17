package com.example.platform.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;


/** One approved compile request per chat, including denied attempts. */
public class BuildTool {
    private static final Logger log = LoggerFactory.getLogger(BuildTool.class);
    private final BuildRunner runner;
    private final AgentSafetyPolicy.Budget budget;

    public BuildTool(BuildRunner runner) {
        this(runner, AgentSafetyPolicy.newBudget(AgentSafetyPolicy.Mode.CHAT));
    }

    public BuildTool(BuildRunner runner, AgentSafetyPolicy.Budget budget) {
        this.runner = runner;
        this.budget = budget;
    }

    @Tool(description = "Compile one registered service and required reactor modules with the fixed Maven wrapper operation. "
            + "Choose exactly one of auth-service, orders-service, inventory-service, payments-service, "
            + "edge-gateway, worker, engineering-agent. Returns process exit code, duration and bounded diagnostics. "
            + "PASS means compile succeeded; no tests are run. Call at most once per request, even if denied.")
    public BuildRunner.BuildResult runBuild(
            @ToolParam(description = "Exact registered service name, for example auth-service") String service) {
        budget.claim(AgentSafetyPolicy.Capability.COMPILE);
        var result = runner.run(service);
        log.info("Tool runBuild: status={}, service={}, durationMs={}, exitCode={}, outputTruncated={}",
                result.status(), result.service(), result.durationMs(), result.exitCode(), result.outputTruncated());
        return result;
    }
}
