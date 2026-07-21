package com.gsafety.ocrtool.plan.task;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlanDigitizeTaskRepositoryTest {

    @Test
    void claimSqlSeparatesReturningKeywordFromColumnList() {
        String normalized = PlanDigitizeTaskRepository.CLAIM_NEXT_SQL.replaceAll("\\s+", " ");

        assertThat(normalized).contains("RETURNING task.task_id AS task_id");
        assertThat(normalized).doesNotContain("RETURNINGtask_id");
        assertThat(normalized).doesNotContain("RETURNING task_id");
    }
}
