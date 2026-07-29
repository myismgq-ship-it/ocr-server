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
        assertHexiEarthquake(roots);
        assertYongdengEarthquake(roots);
        assertYongdengGeologicalDisaster(roots);
        assertTianjinForestFire(roots);
        assertDatangEmergencyPlan(roots);
        assertBaodingGeologicalDisaster(roots);
        assertLanzhouFloodWarning(roots);
        assertUlanqabNaturalDisasterRelief(roots);
        assertTianjinOverallPlan(roots);
        assertTianjinBusPlan(roots);
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

    private void assertHexiEarthquake(List<Path> roots) throws Exception {
        Path sample = find(roots, "河西区地震应急预案.docx").orElse(null);
        if (sample == null) {
            return;
        }
        SegmentResult result = segmenter.extract(parse(sample));
        assertThat(result.emergencyResponses().subList(0, 3))
                .allSatisfy(level -> assertThat(level.status()).isNotEqualTo("MISSING"));
        ResponseLevelSegment level4 = result.emergencyResponses().get(3);
        assertThat(level4.status())
                .withFailMessage("不应生成四级响应：%s", level4)
                .isEqualTo("MISSING");
        assertThat(level4.activationConditions()).isNull();
        assertThat(level4.directResponseMeasures()).isNull();
    }

    private void assertYongdengEarthquake(List<Path> roots) throws Exception {
        Path sample = find(roots, "永登县地震应急预案.docx").orElse(null);
        if (sample == null) {
            return;
        }
        SegmentResult result = segmenter.extract(parse(sample));
        ResponseLevelSegment level4 = result.emergencyResponses().get(3);
        assertThat(level4.activationConditions())
                .contains("4.0（含）级以上", "5.0级以下")
                .doesNotContain("必要时可启动");
        assertThat(level4.directResponseMeasures()).contains("灾区所在乡（镇）人民政府");
        assertThat(level4.status()).isEqualTo("EXTRACTED");
    }

    private void assertYongdengGeologicalDisaster(List<Path> roots) throws Exception {
        Path sample = find(roots, "永登县突发性地质灾害应急预案.docx").orElse(null);
        if (sample == null) {
            return;
        }
        SegmentResult result = segmenter.extract(parse(sample));
        String[] conditions = {"30人以上死亡", "10人以上、30人以下死亡", "3人以上、10人以下死亡", "3人以下死亡"};
        for (int index = 0; index < conditions.length; index++) {
            ResponseLevelSegment level = result.emergencyResponses().get(index);
            assertThat(level.activationConditions())
                    .contains(conditions[index])
                    .doesNotContain("县政府立即启动", "部署地质灾害应急防治");
            assertThat(level.directResponseMeasures())
                    .contains("启动相关的应急", "应急指挥系统")
                    .doesNotContain("发生特别重大地质灾害时");
            assertThat(level.status()).isEqualTo("EXTRACTED");
        }
        assertThat(result.emergencyResponses().get(0).directResponseMeasures())
                .contains("每6小时向国务院主管部门报告");
        assertThat(result.emergencyResponses().get(3).directResponseMeasures())
                .doesNotContain("临灾应急响应", "地质灾害应急处理", "速报程序");
    }

    private void assertTianjinForestFire(List<Path> roots) throws Exception {
        Optional<Path> samplePath = find(roots, "天津市森林火灾应急预案.docx");
        if (samplePath.isEmpty()) {
            samplePath = find(roots, "天津市森林火灾应急预案(1).docx");
        }
        Path sample = samplePath.orElse(null);
        if (sample == null) {
            return;
        }
        SegmentResult result = segmenter.extract(parse(sample));
        assertThat(result.emergencyResponses()).allSatisfy(level -> {
            assertThat(level.activationConditions()).isNotBlank()
                    .doesNotContain("森林火灾分级", "主要任务", "组织灭火行动", "按照以下程序");
            assertThat(level.directResponseMeasures()).isNotBlank();
        });
        assertThat(result.emergencyResponses().get(0).activationConditions()).contains("48小时");
        assertThat(result.emergencyResponses().get(1).activationConditions()).contains("24小时");
        assertThat(result.emergencyResponses().get(2).activationConditions()).contains("12小时");
        assertThat(result.emergencyResponses().get(3).activationConditions()).contains("4小时");
        ResponseLevelSegment red = result.warningResponses().get(0);
        ResponseLevelSegment orange = result.warningResponses().get(1);
        ResponseLevelSegment yellow = result.warningResponses().get(2);
        ResponseLevelSegment blue = result.warningResponses().get(3);
        assertThat(red.activationConditions()).contains("橙色、红色");
        assertThat(orange.activationConditions()).contains("橙色、红色");
        assertThat(yellow.activationConditions()).contains("蓝色、黄色");
        assertThat(blue.activationConditions()).contains("蓝色、黄色");
        assertThat(red.responseMeasures()).contains("加强森林防火巡护", "进一步加强野外火源管理");
        assertThat(orange.responseMeasures()).contains("加强森林防火巡护", "进一步加强野外火源管理");
        assertThat(yellow.responseMeasures()).contains("加强森林防火巡护")
                .doesNotContain("进一步加强野外火源管理");
        assertThat(blue.responseMeasures()).contains("加强森林防火巡护")
                .doesNotContain("进一步加强野外火源管理");
        assertThat(result.warningResponses()).allSatisfy(level -> {
            assertThat(level.activationConditions()).doesNotContain("信息报告", "应急响应");
            assertThat(level.responseMeasures()).doesNotContain("扑救火灾", "转移安置人员");
        });
        assertThat(result.commandSystem()).isNotNull();
        assertThat(result.commandSystem().title()).doesNotContain("框架图");
    }

    private void assertTianjinOverallPlan(List<Path> roots) throws Exception {
        Path sample = find(roots, "天津市突发事件总体应急预案.doc").orElse(null);
        if (sample == null) {
            return;
        }
        SegmentResult result = segmenter.extract(parse(sample));
        ResponseLevelSegment level1 = result.emergencyResponses().get(0);
        ResponseLevelSegment level2 = result.emergencyResponses().get(1);
        ResponseLevelSegment level3 = result.emergencyResponses().get(2);
        ResponseLevelSegment level4 = result.emergencyResponses().get(3);

        assertThat(level1.activationConditions())
                .contains("发生特别重大突发事件", "发生重大突发事件")
                .doesNotContain("发生较大突发事件", "市级层面应急响应原则上");
        assertThat(level2.activationConditions()).contains("发生重大突发事件")
                .doesNotContain("发生特别重大突发事件", "发生较大突发事件");
        assertThat(level3.activationConditions()).contains("发生较大突发事件")
                .doesNotContain("发生重大突发事件", "发生一般突发事件");
        assertThat(level4.activationConditions()).contains("发生较大突发事件")
                .doesNotContain("发生一般突发事件");

        assertThat(level1.directResponseMeasures()).contains("副总指挥3至4名")
                .doesNotContain("副总指挥2至3名", "3.8 处置措施");
        assertThat(level2.directResponseMeasures()).contains("副总指挥2至3名")
                .doesNotContain("副总指挥3至4名", "3.8 处置措施");
        assertThat(level3.directResponseMeasures()).contains("指导协助事发区")
                .doesNotContain("共同开展应急处置", "3.8 处置措施");
        assertThat(level4.directResponseMeasures()).contains("共同开展应急处置")
                .doesNotContain("指导协助事发区", "3.8 处置措施");
        assertThat(result.emergencyResponses()).allSatisfy(level ->
                assertThat(level.responseMeasures()).contains("组织营救受灾和被困人员"));
    }

    private void assertTianjinBusPlan(List<Path> roots) throws Exception {
        Path sample = find(roots, "天津市城市公共汽电车突发事件应急预案.docx").orElse(null);
        if (sample == null) {
            return;
        }
        SegmentResult result = segmenter.extract(parse(sample));
        ResponseLevelSegment level1 = result.emergencyResponses().get(0);
        ResponseLevelSegment level2 = result.emergencyResponses().get(1);
        ResponseLevelSegment level3 = result.emergencyResponses().get(2);
        ResponseLevelSegment level4 = result.emergencyResponses().get(3);

        assertThat(level1.activationConditions())
                .contains("30人以上死亡或失踪", "10人以上、30人以下死亡或失踪")
                .doesNotContain("3人以下死亡或失踪");
        assertThat(level2.activationConditions()).contains("10人以上、30人以下死亡或失踪")
                .doesNotContain("30人以上死亡或失踪", "3人以上、10人以下死亡或失踪");
        assertThat(level3.activationConditions()).contains("3人以上、10人以下死亡或失踪")
                .doesNotContain("3人以下死亡或失踪");
        assertThat(level4.activationConditions()).contains("3人以上、10人以下死亡或失踪")
                .doesNotContain("3人以下死亡或失踪");

        assertThat(level1.directResponseMeasures()).contains("有关市领导同志赴现场指挥")
                .doesNotContain("现场疏散", "车辆调度", "医学救援");
        assertThat(level2.directResponseMeasures()).contains("市指挥部有关负责同志赴现场指挥")
                .doesNotContain("现场疏散", "车辆调度", "医学救援");
        assertThat(level3.directResponseMeasures()).contains("办公室主要负责同志赴现场指导")
                .doesNotContain("现场疏散", "车辆调度", "医学救援");
        assertThat(level4.directResponseMeasures()).contains("办公室有关负责同志赴现场协调")
                .doesNotContain("现场疏散", "车辆调度", "医学救援");
        assertThat(result.emergencyResponses()).allSatisfy(level ->
                assertThat(level.responseMeasures()).contains("现场疏散", "车辆调度", "医学救援"));
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

    private void assertDatangEmergencyPlan(List<Path> roots) throws Exception {
        Path sample = find(roots, "中国大唐集团公司突发事件.docx").orElse(null);
        if (sample == null) {
            return;
        }
        SegmentResult result = segmenter.extract(parse(sample));
        String[] required = {"死亡10人", "死亡3-9人", "人身死亡或重伤", "重伤或轻伤"};
        for (int index = 0; index < required.length; index++) {
            assertThat(result.emergencyResponses().get(index).activationConditions())
                    .contains(required[index])
                    .doesNotContain("应急响应一般分为", "组织实施");
        }
        assertThat(result.emergencyResponses().get(0).activationConditions()).doesNotContain("死亡3-9人");
        assertThat(result.emergencyResponses().get(1).activationConditions()).doesNotContain("死亡10人");
    }

    private void assertBaodingGeologicalDisaster(List<Path> roots) throws Exception {
        Path sample = find(roots, "保定市突发地质灾害应急预案.docx").orElse(null);
        if (sample == null) {
            return;
        }
        SegmentResult result = segmenter.extract(parse(sample));
        String[] conditionFragments = {"1000人以上", "500人以上", "100人以上", "100人以下"};
        String[] measureFragments = {"配合省应急防治指挥部", "配合省应急防治指挥部", "市指挥部具体指挥", "开发区应急防治指挥部"};
        for (int index = 0; index < conditionFragments.length; index++) {
            ResponseLevelSegment level = result.emergencyResponses().get(index);
            assertThat(level.activationConditions())
                    .contains(conditionFragments[index])
                    .doesNotContain("应急响应", "应急响应结束");
            assertThat(level.directResponseMeasures()).contains(measureFragments[index]);
            assertThat(level.status()).isEqualTo("EXTRACTED");
        }
        assertThat(result.emergencyResponses().get(0).activationConditions()).contains("因灾死亡30人以上");
        assertThat(result.emergencyResponses().get(1).activationConditions()).contains("因灾死亡10人以上、30人以下");
        assertThat(result.emergencyResponses().get(2).activationConditions()).contains("因灾死亡3人以上、10人以下");
        assertThat(result.emergencyResponses().get(3).activationConditions()).contains("因灾死亡3人以下");
    }

    private void assertLanzhouFloodWarning(List<Path> roots) throws Exception {
        Path sample = find(roots, "兰州市防汛应急预案.docx").orElse(null);
        if (sample == null) {
            return;
        }
        SegmentResult result = segmenter.extract(parse(sample));
        String[] rainfallThresholds = {"30mm以上", "25mm以上", "20mm", "10mm"};
        String[] riverThresholds = {"6500m3/s", "5000m3/s", "4000m3/s", "3000m3/s"};
        for (int index = 0; index < rainfallThresholds.length; index++) {
            ResponseLevelSegment level = result.warningResponses().get(index);
            assertThat(level.activationConditions())
                    .contains(rainfallThresholds[index], riverThresholds[index])
                    .doesNotContain("预警信息的响应和发布", "研判会商后发布");
            assertThat(level.directResponseMeasures()).contains("启动应急预案");
            assertThat(level.status()).isEqualTo("EXTRACTED");
        }
    }

    private void assertUlanqabNaturalDisasterRelief(List<Path> roots) throws Exception {
        Path sample = find(roots, "乌兰察布市自然灾害救助应急预案.docx").orElse(null);
        if (sample == null) {
            return;
        }
        ParsedDocument document = parse(sample);
        SegmentResult result = segmenter.extract(document);
        String[] conditionFragments = {"死亡20人以上", "死亡10人以上，20人以下", "死亡5人以上，10人以下", "死亡3人以上，5人以下"};
        String[] measureFragments = {"向市委、市人民政府", "召开灾情分析会", "研究落实灾区救助工作", "指导受灾旗县市区"};
        for (int index = 0; index < conditionFragments.length; index++) {
            ResponseLevelSegment level = result.emergencyResponses().get(index);
            assertThat(level.activationConditions())
                    .contains(conditionFragments[index])
                    .doesNotContain("启动程序", "灾害损失情况", "响应终止");
            assertThat(level.directResponseMeasures())
                    .contains(measureFragments[index])
                    .doesNotContain("共同制定优惠政策", "灾后疾病预防", "决定进入");
            assertThat(level.status()).isEqualTo("EXTRACTED");
        }
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
