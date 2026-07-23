package com.gsafety.ocrtool.management;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PlanRuleRevisionServiceTest {

    private final PlanRuleRevisionService service = new PlanRuleRevisionService(
            null,
            new ObjectMapper(),
            mock(com.gsafety.ocrtool.segment.DatabaseSegmentRuleProvider.class));

    @Test
    void validatesRequiredRuleGroupsAndDuplicateAliases() {
        List<PlanRuleDefinition> valid = List.of(
                rule("COMMAND", "command", "指挥体系", "组织指挥体系"),
                rule("RESPONSE", "level_1", "一级响应", "Ⅰ级响应"),
                rule("TAIL", "tail", "尾部", "附则"));

        assertThat(service.validateRules(valid).valid()).isTrue();

        List<PlanRuleDefinition> invalid = List.of(
                rule("COMMAND", "command", "指挥体系", "组织指挥体系"),
                rule("COMMAND", "command", "指挥体系", "组织指挥体系"));
        ValidationResponse result = service.validateRules(invalid);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("重复别名"));
        assertThat(result.errors()).anyMatch(error -> error.contains("RESPONSE"));
    }

    private PlanRuleDefinition rule(String type, String code, String name, String alias) {
        return new PlanRuleDefinition(type, code, name, alias, 10, 10, true);
    }
}
