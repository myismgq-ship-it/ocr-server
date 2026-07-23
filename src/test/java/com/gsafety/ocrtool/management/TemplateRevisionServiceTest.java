package com.gsafety.ocrtool.management;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateRevisionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ManagedTemplateProvider provider =
            new ManagedTemplateProvider(null, objectMapper);
    private final TemplateRevisionService service =
            new TemplateRevisionService(null, objectMapper, provider);

    @Test
    void validatesDraftTemplateStructureAndPatterns() {
        Map<String, Object> valid = Map.of(
                "processor", "safety-license",
                "fields", Map.of(
                        "enterpriseName", Map.of(
                                "labels", List.of("企业名称"),
                                "direction", "right",
                                "minConfidence", 0.8,
                                "pattern", ".+公司$")));

        assertThat(service.validateDefinition(valid).valid()).isTrue();

        Map<String, Object> invalid = Map.of(
                "fields", Map.of(
                        "enterpriseName", Map.of(
                                "labels", List.of(),
                                "direction", "diagonal",
                                "pattern", "[")));
        ValidationResponse result = service.validateDefinition(invalid);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).hasSizeGreaterThanOrEqualTo(3);
    }
}
