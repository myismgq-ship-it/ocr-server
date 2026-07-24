package com.gsafety.ocrtool.document;

import com.gsafety.ocrtool.config.PlanProperties;
import com.gsafety.ocrtool.segment.PlanSegmentService;
import com.gsafety.ocrtool.segment.ResponseLevelSegment;
import com.gsafety.ocrtool.segment.SegmentResult;
import com.gsafety.ocrtool.segment.TestSegmentRules;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Optional local regression suite for the externally supplied plan corpus. */
class PlanSampleCorpusTest {

    private final DocumentDownloadService detector = new DocumentDownloadService(new PlanProperties());
    private final DocumentParseService parser = new DocumentParseService(List.of(
            new WordDocumentParser(), new MhtmlDocumentParser()));
    private final PlanSegmentService segmenter = new PlanSegmentService(TestSegmentRules::defaults);

    @Test
    void parsesConfiguredCorpusAndChecksRepresentativeGoldens() throws Exception {
        String configuredRoot = System.getProperty("plan.sample.root", System.getenv("PLAN_SAMPLE_ROOT"));
        Assumptions.assumeTrue(configuredRoot != null && !configuredRoot.isBlank(),
                "Set PLAN_SAMPLE_ROOT or -Dplan.sample.root to run external plan regressions.");
        List<Path> roots = java.util.Arrays.stream(configuredRoot.split(";"))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(Path::of)
                .toList();
        Assumptions.assumeTrue(!roots.isEmpty() && roots.stream().allMatch(Files::isDirectory),
                "Configured plan sample root does not exist.");

        List<Path> samples = new java.util.ArrayList<>();
        for (Path root : roots) {
            try (var paths = Files.walk(root)) {
                samples.addAll(paths.filter(Files::isRegularFile)
                        .filter(this::isWordSample)
                        .toList());
            }
        }
        samples = samples.stream().sorted(Comparator.comparing(Path::toString)).toList();
        int minimum = Integer.getInteger("plan.sample.minimum", 1);
        assertThat(samples).as("plan sample corpus").hasSizeGreaterThanOrEqualTo(minimum);

        for (Path sample : samples) {
            ParsedDocument document = parse(sample);
            assertThat(document.blocks()).as(sample.toString()).isNotEmpty();
            assertThat(document.blocks()).as(sample.toString())
                    .allSatisfy(block -> assertThat(block.text().length()).isLessThanOrEqualTo(20_000));
            SegmentResult result = segmenter.extract(document);
            assertThat(result.warningResponses()).as(sample.toString()).hasSize(4);
            assertThat(result.emergencyResponses()).as(sample.toString()).hasSize(4);
            assertBounded(result.warningResponses(), sample);
            assertBounded(result.emergencyResponses(), sample);
        }

        assertNationalEarthquake(roots);
        assertTraditionalDoc(roots);
        assertMhtml(roots);
        assertNonPlan(roots);
    }

    private void assertNationalEarthquake(List<Path> roots) throws Exception {
        Path sample = find(roots, "国家地震应急预案.docx").orElse(null);
        if (sample == null) {
            return;
        }
        SegmentResult result = segmenter.extract(parse(sample));
        assertThat(result.emergencyResponses().get(0).activationConditions())
                .contains("特别重大地震灾害");
        assertThat(result.emergencyResponses().get(0).directResponseMeasures())
                .contains("抗震救灾");
    }

    private void assertTraditionalDoc(List<Path> roots) throws Exception {
        Path sample = find(roots, "临沧市自然灾害救助应急预案.doc").orElse(null);
        if (sample == null) {
            return;
        }
        ParsedDocument document = parse(sample);
        assertThat(document.fileType()).isEqualTo(DocumentFileType.DOC);
        assertThat(segmenter.extract(document).emergencyResponses())
                .anySatisfy(level -> assertThat(level.status()).isNotEqualTo("MISSING"));
    }

    private void assertMhtml(List<Path> roots) throws Exception {
        Path sample = find(roots, "普洱市人民政府办公室关于印发普洱市防汛抗旱应急预案的通知.doc")
                .orElse(null);
        if (sample == null) {
            return;
        }
        ParsedDocument document = parse(sample);
        assertThat(document.fileType()).isEqualTo(DocumentFileType.MHTML);
        assertThat(document.blocks()).anySatisfy(block -> assertThat(block.text()).contains("防汛"));
    }

    private void assertNonPlan(List<Path> roots) throws Exception {
        Path sample = find(roots, "中华人民共和国道路交通安全法.docx").orElse(null);
        if (sample == null) {
            return;
        }
        SegmentResult result = segmenter.extract(parse(sample));
        assertThat(result.emergencyResponses())
                .allSatisfy(level -> assertThat(level.status()).isEqualTo("MISSING"));
        assertThat(result.warnings()).contains("未识别到应急预案响应结构。");
    }

    private ParsedDocument parse(Path path) throws Exception {
        try {
            DocumentFileType type = detector.detectFileType(path, path.getFileName().toString(), "");
            DownloadedDocument document = new DownloadedDocument(
                    path, path.getFileName().toString(), "", Files.size(path), type);
            return parser.parse(document);
        } catch (RuntimeException error) {
            throw new AssertionError("Failed to parse sample: " + path, error);
        }
    }

    private void assertBounded(List<ResponseLevelSegment> levels, Path sample) {
        for (ResponseLevelSegment level : levels) {
            if (level.activationConditions() != null) {
                assertThat(level.activationConditions().length()).as(sample + " conditions").isLessThanOrEqualTo(8_000);
            }
            if (level.directResponseMeasures() != null) {
                assertThat(level.directResponseMeasures().length()).as(sample + " measures").isLessThanOrEqualTo(8_000);
            }
        }
    }

    private Optional<Path> find(List<Path> roots, String fileName) throws Exception {
        for (Path root : roots) {
            try (var paths = Files.walk(root)) {
                Optional<Path> match = paths.filter(path -> path.getFileName().toString().equals(fileName)).findFirst();
                if (match.isPresent()) {
                    return match;
                }
            }
        }
        return Optional.empty();
    }

    private boolean isWordSample(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return !name.startsWith("~$") && (name.endsWith(".doc") || name.endsWith(".docx"));
    }
}
