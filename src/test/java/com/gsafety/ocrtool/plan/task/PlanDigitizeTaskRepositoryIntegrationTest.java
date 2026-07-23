package com.gsafety.ocrtool.plan.task;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class PlanDigitizeTaskRepositoryIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static DataSource dataSource;
    private PlanDigitizeTaskRepository repository;

    @BeforeAll
    static void initializeSchema() {
        DriverManagerDataSource configured = new DriverManagerDataSource();
        configured.setUrl(POSTGRES.getJdbcUrl());
        configured.setUsername(POSTGRES.getUsername());
        configured.setPassword(POSTGRES.getPassword());
        dataSource = configured;
        new ResourceDatabasePopulator(
                new ClassPathResource("db/postgresql/schema.sql"),
                new ClassPathResource("db/migration/V2__management_and_review.sql"))
                .execute(dataSource);
    }

    @BeforeEach
    void reset() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM plan_digitize_task");
        repository = new PlanDigitizeTaskRepository(jdbc);
    }

    @Test
    void concurrentClaimsReturnDifferentTasks() throws Exception {
        repository.insert(task("plan-1"));
        repository.insert(task("plan-2"));
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<PlanDigitizeTask>> first = executor.submit(
                    () -> repository.claimNext("worker-a", UUID.randomUUID(), OffsetDateTime.now()));
            Future<Optional<PlanDigitizeTask>> second = executor.submit(
                    () -> repository.claimNext("worker-b", UUID.randomUUID(), OffsetDateTime.now()));

            assertThat(first.get()).isPresent();
            assertThat(second.get()).isPresent();
            assertThat(first.get().orElseThrow().taskId())
                    .isNotEqualTo(second.get().orElseThrow().taskId());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void staleClaimTokenCannotOverwriteNewAttempt() {
        PlanDigitizeTask queued = task("plan-lease");
        repository.insert(queued);
        OffsetDateTime now = OffsetDateTime.now();
        UUID firstToken = UUID.randomUUID();
        PlanDigitizeTask first = repository.claimNext("worker-a", firstToken, now).orElseThrow();

        assertThat(repository.requeueStale(now.plusMinutes(10))).isEqualTo(1);
        UUID secondToken = UUID.randomUUID();
        PlanDigitizeTask second = repository.claimNext("worker-b", secondToken, now.plusMinutes(10))
                .orElseThrow();

        assertThat(repository.complete(
                first.taskId(), "worker-a", firstToken, "{}", "rules-old", now.plusMinutes(11)))
                .isZero();
        assertThat(repository.complete(
                second.taskId(), "worker-b", secondToken, "{}", "rules-new", now.plusMinutes(11)))
                .isEqualTo(1);
        PlanDigitizeTask completed = repository
                .findByPlanAndTaskId("plan-lease", queued.taskId())
                .orElseThrow();
        assertThat(completed.status()).isEqualTo(PlanDigitizeTaskStatus.COMPLETED);
        assertThat(completed.attempt()).isEqualTo(2);
        assertThat(completed.ruleVersion()).isEqualTo("rules-new");
    }

    @Test
    void onlyOneActiveTaskIsAllowedPerPlan() {
        repository.insert(task("same-plan"));

        assertThatThrownBy(() -> repository.insert(task("same-plan")))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    private PlanDigitizeTask task(String planId) {
        OffsetDateTime now = OffsetDateTime.now();
        return new PlanDigitizeTask(
                UUID.randomUUID(),
                planId,
                PlanDigitizeTaskSourceType.URL,
                "PDF",
                "plan.pdf",
                "application/pdf",
                10L,
                "https://example.com/plan.pdf",
                null,
                PlanDigitizeTaskStatus.QUEUED,
                null,
                null,
                null,
                null,
                null,
                null,
                "QUEUED",
                0,
                0,
                null,
                null,
                now,
                null,
                null,
                now,
                now);
    }
}
