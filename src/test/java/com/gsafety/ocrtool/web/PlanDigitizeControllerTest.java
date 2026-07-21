package com.gsafety.ocrtool.web;

import com.gsafety.ocrtool.common.OcrException;
import com.gsafety.ocrtool.plan.PlanDigitizeService;
import com.gsafety.ocrtool.plan.task.PlanDigitizeTaskService;
import com.gsafety.ocrtool.response.PlanDigitizeResponse;
import com.gsafety.ocrtool.response.PlanSectionResponse;
import com.gsafety.ocrtool.response.ResponseLevelSectionResponse;
import com.gsafety.ocrtool.response.PlanDigitizeTaskResponse;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MultipartFile;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlanDigitizeControllerTest {

    @Test
    void digitizeEndpointReturnsStructuredResult() throws Exception {
        PlanDigitizeService service = mock(PlanDigitizeService.class);
        when(service.digitize(eq("https://example.com/plan.pdf"))).thenReturn(new PlanDigitizeResponse(
                "plan.pdf",
                "PDF",
                "PDF_TEXT",
                new PlanSectionResponse(
                        "command_system",
                        "组织指挥体系",
                        "成立应急指挥部。",
                        List.of(3),
                        "HEADING_ALIAS",
                        List.of("组织指挥体系")),
                List.of(new ResponseLevelSectionResponse(
                        "level_1",
                        "一级响应",
                        "Ⅰ级响应",
                        "启动条件\n响应措施\n组织力量处置。",
                        "组织力量处置。",
                        List.of(8),
                        "HEADING_ALIAS",
                        List.of("Ⅰ级响应"))),
                List.of()));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new PlanDigitizeController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(post("/api/plans/digitize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentUrl\":\"https://example.com/plan.pdf\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value("plan.pdf"))
                .andExpect(jsonPath("$.commandSystem.key").value("command_system"))
                .andExpect(jsonPath("$.commandSystem.matchedBy").value("HEADING_ALIAS"))
                .andExpect(jsonPath("$.commandSystem.sourcePages[0]").value(3))
                .andExpect(jsonPath("$.responseLevels[0].key").value("level_1"))
                .andExpect(jsonPath("$.responseLevels[0].responseMeasures").value("组织力量处置。"));
    }

    @Test
    void digitizeEndpointUsesUnifiedErrorShape() throws Exception {
        PlanDigitizeService service = mock(PlanDigitizeService.class);
        when(service.digitize(eq("ftp://example.com/plan.pdf")))
                .thenThrow(new OcrException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_DOCUMENT_URL",
                        "文档 URL 只支持 http/https。"));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new PlanDigitizeController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(post("/api/plans/digitize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentUrl\":\"ftp://example.com/plan.pdf\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DOCUMENT_URL"))
                .andExpect(jsonPath("$.message").value("文档 URL 只支持 http/https。"));
    }

    @Test
    void digitizeUploadEndpointReturnsStructuredResult() throws Exception {
        PlanDigitizeService service = mock(PlanDigitizeService.class);
        when(service.digitize(any(MultipartFile.class))).thenReturn(new PlanDigitizeResponse(
                "plan.docx",
                "DOCX",
                "WORD",
                new PlanSectionResponse(
                        "command_system",
                        "指挥体系",
                        "成立指挥部。",
                        List.of(1),
                        "HEADING",
                        List.of("指挥体系")),
                List.of(),
                List.of()));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new PlanDigitizeController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "plan.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                new byte[] {1, 2, 3});

        mockMvc.perform(multipart("/api/plans/digitize/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value("plan.docx"))
                .andExpect(jsonPath("$.parseMode").value("WORD"));
    }

    @Test
    void asyncTaskEndpointsReturnStableStatusAndCanBeQueried() throws Exception {
        PlanDigitizeService digitizeService = mock(PlanDigitizeService.class);
        PlanDigitizeTaskService taskService = mock(PlanDigitizeTaskService.class);
        UUID taskId = UUID.randomUUID();
        OffsetDateTime queuedAt = OffsetDateTime.now();
        PlanDigitizeTaskResponse response = new PlanDigitizeTaskResponse(
                taskId, "plan-1", "QUEUED", "排队中", "plan.pdf", queuedAt,
                null, null, null, null, false, null);
        when(taskService.createUrl("plan-1", "https://example.com/plan.pdf")).thenReturn(response);
        when(taskService.get("plan-1", taskId)).thenReturn(response);
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new PlanDigitizeController(digitizeService, taskService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(post("/api/plans/plan-1/digitize/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentUrl\":\"https://example.com/plan.pdf\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").value(taskId.toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.statusName").value("排队中"));

        mockMvc.perform(get("/api/plans/plan-1/digitize/tasks/{taskId}", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId").value("plan-1"));
    }
}
