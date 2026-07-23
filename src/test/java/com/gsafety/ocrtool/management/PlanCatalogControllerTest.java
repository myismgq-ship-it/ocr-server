package com.gsafety.ocrtool.management;

import com.gsafety.ocrtool.web.GlobalExceptionHandler;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlanCatalogControllerTest {

    @Test
    void listReturnsDatabaseBackedPlanMetadata() throws Exception {
        PlanCatalogService service = mock(PlanCatalogService.class);
        when(service.list()).thenReturn(List.of(new PlanCatalogResponse(
                "plan-earthquake", "TJ-DZ-01", "天津市地震应急预案", "自然灾害",
                "天津市应急管理局", "2026版", OffsetDateTime.parse("2026-07-22T10:00:00+08:00"))));
        MockMvc mvc = mvc(service);

        mvc.perform(get("/api/plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("plan-earthquake"))
                .andExpect(jsonPath("$[0].name").value("天津市地震应急预案"))
                .andExpect(jsonPath("$[0].department").value("天津市应急管理局"));
    }

    @Test
    void createValidatesAndReturnsCreatedPlan() throws Exception {
        PlanCatalogService service = mock(PlanCatalogService.class);
        when(service.create(any())).thenReturn(new PlanCatalogResponse(
                "plan-1", "YA-001", "生产安全事故应急预案", "事故灾难",
                "应急管理局", "2026版", OffsetDateTime.now()));
        MockMvc mvc = mvc(service);

        mvc.perform(post("/api/plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"planId":"plan-1","code":"YA-001","name":"生产安全事故应急预案",
                                 "category":"事故灾难","department":"应急管理局","version":"2026版"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("YA-001"));
    }

    private MockMvc mvc(PlanCatalogService service) {
        return MockMvcBuilders.standaloneSetup(new PlanCatalogController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }
}
