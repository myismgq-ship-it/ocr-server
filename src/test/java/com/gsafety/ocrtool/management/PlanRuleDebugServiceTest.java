package com.gsafety.ocrtool.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gsafety.ocrtool.document.DocumentBlock;
import com.gsafety.ocrtool.document.DocumentFileType;
import com.gsafety.ocrtool.document.DocumentParseMode;
import com.gsafety.ocrtool.document.ParsedDocument;
import com.gsafety.ocrtool.plan.PlanDigitizeDebugRun;
import com.gsafety.ocrtool.plan.PlanDigitizeService;
import com.gsafety.ocrtool.plan.task.PlanDigitizeTaskRepository;
import com.gsafety.ocrtool.plan.task.PlanTaskStorageService;
import com.gsafety.ocrtool.response.PlanDigitizeResponse;
import com.gsafety.ocrtool.segment.DatabaseSegmentRuleProvider;
import com.gsafety.ocrtool.segment.SegmentRuleRepository;
import com.gsafety.ocrtool.segment.SegmentRules;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class PlanRuleDebugServiceTest {

    @Test
    void reportsSectionFallbackWhenMultipleUsableCandidatesExist() {
        UUID revisionId = UUID.randomUUID();
        PlanRuleDefinition sectionRule = new PlanRuleDefinition(
                "SECTION", "emergency_scope", "应急响应章节", "应急响应", 10, 10, true);
        PlanRuleRevisionResponse revision = new PlanRuleRevisionResponse(
                revisionId, 3, "DRAFT", List.of(sectionRule), "test", OffsetDateTime.now(), null);
        SegmentRules rules = new SegmentRules(
                "command", List.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of("emergency_scope", List.of("应急响应")), Map.of(), List.of(), "debug-test");
        ParsedDocument document = new ParsedDocument(
                "sample.docx", DocumentFileType.DOCX, DocumentParseMode.WORD,
                List.of(
                        new DocumentBlock("第三章 应急响应", 2, 1, false, List.of()),
                        new DocumentBlock("第四章 应急响应", 6, 1, false, List.of())),
                List.of());
        PlanDigitizeResponse result = new PlanDigitizeResponse(
                "sample.docx", "DOCX", "WORD", null, List.of(), List.of(), List.of(), List.of(), List.of(), "debug-test");

        PlanRuleRevisionService revisions = mock(PlanRuleRevisionService.class);
        PlanDigitizeService digitize = mock(PlanDigitizeService.class);
        when(revisions.get(revisionId)).thenReturn(revision);
        when(revisions.snapshot(revisionId)).thenReturn(rules);
        MockMultipartFile file = new MockMultipartFile("file", "sample.docx", "application/octet-stream", new byte[] {1});
        when(digitize.debug(file, rules)).thenReturn(new PlanDigitizeDebugRun(result, document, null));
        PlanRuleDebugService service = new PlanRuleDebugService(
                revisions, digitize, mock(PlanDigitizeTaskRepository.class), mock(PlanTaskStorageService.class),
                mock(DatabaseSegmentRuleProvider.class), mock(SegmentRuleRepository.class));

        PlanRuleDebugResponse response = service.debugFile(revisionId, file);

        assertEquals("FALLBACK", response.traces().get(0).status());
        assertEquals("FULL_DOCUMENT", response.traces().get(0).matchedBy());
        assertEquals(2, response.traces().get(0).candidates().size());
    }
    @Test
    void exposesDatabaseActiveRulesSeparatelyFromRevisionHistory() {
        SegmentRules runtimeRules = new SegmentRules(
                "command", List.of("指挥体系"), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                List.of("附则"), "runtime-rule-version");
        PlanRuleDefinition activeRule = new PlanRuleDefinition(
                "COMMAND", "command", "指挥体系", "指挥体系", 10, 10, true);
        DatabaseSegmentRuleProvider provider = mock(DatabaseSegmentRuleProvider.class);
        SegmentRuleRepository repository = mock(SegmentRuleRepository.class);
        when(provider.currentRules()).thenReturn(runtimeRules);
        when(repository.findEnabledRuleDefinitions()).thenReturn(List.of(activeRule));
        PlanRuleDebugService service = new PlanRuleDebugService(
                mock(PlanRuleRevisionService.class), mock(PlanDigitizeService.class),
                mock(PlanDigitizeTaskRepository.class), mock(PlanTaskStorageService.class), provider, repository);

        PlanActiveRuleResponse response = service.activeRules();

        assertEquals("DATABASE_ACTIVE", response.source());
        assertEquals("runtime-rule-version", response.ruleVersion());
        assertEquals(List.of(activeRule), response.rules());
    }
}
