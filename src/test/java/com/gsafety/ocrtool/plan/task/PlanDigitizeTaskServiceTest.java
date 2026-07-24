package com.gsafety.ocrtool.plan.task;

import com.gsafety.ocrtool.management.PlanAccuracyService;
import com.gsafety.ocrtool.response.PlanDigitizeTaskResponse;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlanDigitizeTaskServiceTest {

    @Test
    void duplicateSubmissionReturnsExistingActiveTaskWithoutStoringFile() {
        PlanDigitizeTaskRepository repository = mock(PlanDigitizeTaskRepository.class);
        PlanTaskStorageService storage = mock(PlanTaskStorageService.class);
        PlanDigitizeTask active = task(PlanDigitizeTaskStatus.RUNNING);
        when(repository.findActive("plan-1")).thenReturn(Optional.of(active));
        PlanDigitizeTaskService service = new PlanDigitizeTaskService(repository, storage, new ObjectMapper(), mock(PlanAccuracyService.class));

        PlanDigitizeTaskResponse response = service.createUpload(
                "plan-1", new MockMultipartFile("file", "plan.pdf", "application/pdf", new byte[] {1}));

        assertThat(response.taskId()).isEqualTo(active.taskId());
        assertThat(response.reused()).isTrue();
        assertThat(response.status()).isEqualTo("RUNNING");
        verify(storage, never()).store(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private PlanDigitizeTask task(PlanDigitizeTaskStatus status) {
        OffsetDateTime now = OffsetDateTime.now();
        return new PlanDigitizeTask(
                UUID.randomUUID(), "plan-1", PlanDigitizeTaskSourceType.UPLOAD, "PDF", "plan.pdf",
                "application/pdf", 1L, null, "task.bin", status, null, null, null,
                "worker", now, null, now, now, null, now, now);
    }
}
