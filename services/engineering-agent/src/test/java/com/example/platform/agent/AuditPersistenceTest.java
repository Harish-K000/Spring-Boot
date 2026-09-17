package com.example.platform.agent;

import com.example.platform.agent.dto.*;
import com.example.platform.agent.service.ApprovalWorkflow;
import com.example.platform.agent.service.AuditStore;
import com.example.platform.agent.tool.RepositoryGit;
import com.example.platform.agent.tool.EngineeringToolGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditPersistenceTest {
    @TempDir Path temporary;

    @Test void reviewAndOneChoiceSurviveFreshStoreInstances() {
        String url = "jdbc:h2:file:" + temporary.resolve("audit");
        DataSource firstDataSource = dataSource(url);
        new ResourceDatabasePopulator(new ClassPathResource("db/audit-schema.sql"))
                .execute(firstDataSource);
        AuditStore first = store(firstDataSource);
        ApprovalWorkflow firstWorkflow = new ApprovalWorkflow(first);
        String id = first.startReview("engineering-agent");
        first.tool(id, "compile", "engineering-agent", "FAIL", 31);
        var report = new ReviewReport(ReviewReport.ReviewDecision.FAIL,
                ReviewReport.RiskLevel.HIGH, "not stored", List.of(), List.of(), 0,
                List.of(), List.of(), List.of(), List.of(), ReviewReport.VerificationStatus.FAIL);
        var invitation = firstWorkflow.open(id, "engineering-agent", report, null, null, null);

        // A new JDBC connection and service object model a restarted agent process.
        AuditStore restarted = store(dataSource(url));
        ApprovalWorkflow restartedWorkflow = new ApprovalWorkflow(restarted);
        assertThat(restartedWorkflow.get(id).status()).isEqualTo("WAITING_FOR_APPROVAL");
        assertThat(restarted.trail(id).toolExecutions()).hasSize(1);
        assertThat(restarted.trail(id).review().decision()).isEqualTo("FAIL");
        assertThat(restarted.trail(id).review().riskLevel()).isEqualTo("HIGH");
        assertThat(restarted.trail(id).review().commitHash()).matches("[0-9a-fA-F]{40,64}");
        assertThat(restarted.trail(id).toString()).doesNotContain("not stored", invitation.actionToken());
        assertThatThrownBy(() -> restartedWorkflow.decide(id, invitation.actionToken(),
                new ApprovalActionRequest(ApprovalAction.APPROVE, "Cannot approve.")))
                .isInstanceOf(ApprovalWorkflow.FailedReviewCannotBeApprovedException.class);
        restartedWorkflow.decide(id, invitation.actionToken(),
                new ApprovalActionRequest(ApprovalAction.REQUEST_CHANGES, "Fix the build."));

        AuditStore secondRestart = store(dataSource(url));
        assertThat(secondRestart.approvalCase(id).status()).isEqualTo("CHANGES_REQUESTED");
        assertThat(secondRestart.trail(id).actions()).hasSize(1);
        assertThat(secondRestart.trail(id).actions().getFirst().action())
                .isEqualTo("REQUEST_CHANGES");
        assertThatThrownBy(() -> new ApprovalWorkflow(secondRestart).decide(id, invitation.actionToken(),
                new ApprovalActionRequest(ApprovalAction.REJECT, "Again.")))
                .isInstanceOf(ApprovalWorkflow.DecisionConflictException.class);
    }

    private static DataSource dataSource(String url) {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.h2.Driver");
        source.setUrl(url);
        return source;
    }

    private static AuditStore store(DataSource source) {
        EngineeringToolGateway tools = org.mockito.Mockito.mock(EngineeringToolGateway.class);
        org.mockito.Mockito.when(tools.headCommit()).thenReturn(new RepositoryGit("").headCommit());
        return new AuditStore(new JdbcTemplate(source), new DataSourceTransactionManager(source),
                tools, "qwen2.5:1.5b");
    }
}
