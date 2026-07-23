package com.gsafety.ocrtool.management;

import com.gsafety.ocrtool.plan.task.PlanDigitizeTask;
import com.gsafety.ocrtool.plan.task.PlanDigitizeTaskRepository;
import com.gsafety.ocrtool.plan.task.PlanDigitizeTaskSourceType;
import com.gsafety.ocrtool.plan.task.PlanDigitizeTaskStatus;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanComparisonServiceTest {

    @Test
    void comparesKeyedPlanSectionsAndExportsExcel() throws Exception {
        PlanDigitizeTaskRepository repository = mock(PlanDigitizeTaskRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        when(repository.findByPlanAndTaskId("plan-1", from)).thenReturn(Optional.of(task(
                from,
                objectMapper.writeValueAsString(java.util.Map.of(
                        "actionGroups", java.util.List.of(java.util.Map.of(
                                "key", "rescue", "name", "抢险组", "responsibilities", "旧职责")))))));
        when(repository.findByPlanAndTaskId("plan-1", to)).thenReturn(Optional.of(task(
                to,
                objectMapper.writeValueAsString(java.util.Map.of(
                        "actionGroups", java.util.List.of(java.util.Map.of(
                                "key", "rescue", "name", "抢险组", "responsibilities", "新职责")))))));

        PlanComparisonService service = new PlanComparisonService(repository, objectMapper);
        PlanComparisonResponse result = service.compare("plan-1", from, to);

        assertThat(result.changes())
                .anyMatch(change -> change.path().equals("actionGroups.rescue.responsibilities")
                        && change.changeType().equals("MODIFIED"));
        assertThat(service.exportExcel(result)).startsWith((byte) 'P', (byte) 'K');
    }

    private PlanDigitizeTask task(UUID id, String result) {
        OffsetDateTime now = OffsetDateTime.now();
        return new PlanDigitizeTask(
                id,
                "plan-1",
                PlanDigitizeTaskSourceType.URL,
                "PDF",
                "plan.pdf",
                "application/pdf",
                1L,
                "https://example.com/plan.pdf",
                null,
                PlanDigitizeTaskStatus.COMPLETED,
                result,
                null,
                null,
                "worker",
                now,
                null,
                now,
                now,
                now,
                now,
                now);
    }
}
