package com.gsafety.ocrtool.segment;

import com.gsafety.ocrtool.config.PlanProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseSegmentRuleProviderTest {

    @Test
    void loadsOrderedRulesAndReusesCachedSnapshot() {
        SegmentRuleRepository repository = mock(SegmentRuleRepository.class);
        when(repository.findEnabledRules()).thenReturn(List.of(
                row("COMMAND", "command_system", "指挥体系", "指挥体系"),
                row("RESPONSE", "level_1", "一级响应", "一级响应"),
                row("RESPONSE", "level_1", "一级响应", "Ⅰ级响应"),
                row("RESPONSE", "level_1", "一级响应", "Ⅰ级应急响应"),
                row("RESPONSE", "level_2", "二级响应", "二级响应"),
                row("WARNING", "warning_level_1", "一级预警", "红色预警"),
                row("SECTION", "warning_scope", "预警章节", "预警响应"),
                row("MARKER", "activation_condition", "启动条件标记", "启动条件"),
                row("TAIL", "response_tail", "响应结束标题", "响应终止")));
        PlanProperties properties = new PlanProperties();
        properties.getSegmentRules().setCacheTtl(Duration.ofMinutes(1));
        DatabaseSegmentRuleProvider provider = new DatabaseSegmentRuleProvider(repository, properties);

        SegmentRules first = provider.currentRules();
        SegmentRules second = provider.currentRules();

        assertThat(first).isSameAs(second);
        assertThat(first.commandKey()).isEqualTo("command_system");
        assertThat(first.commandAliases()).containsExactly("指挥体系");
        assertThat(first.responseAliases()).containsOnlyKeys("一级响应", "二级响应");
        assertThat(first.responseAliases().get("一级响应"))
                .containsExactly("一级响应", "Ⅰ级响应", "Ⅰ级应急响应");
        assertThat(first.responseKeys()).containsEntry("一级响应", "level_1");
        assertThat(first.warningKeys()).containsEntry("一级预警", "warning_level_1");
        assertThat(first.sectionAliases()).containsEntry("warning_scope", List.of("预警响应"));
        assertThat(first.markerAliases()).containsEntry("activation_condition", List.of("启动条件"));
        assertThat(first.responseTailHeadings()).containsExactly("响应终止");
        verify(repository).findEnabledRules();
    }

    private SegmentRuleRow row(String type, String code, String canonicalName, String alias) {
        return new SegmentRuleRow(type, code, canonicalName, alias);
    }
}
