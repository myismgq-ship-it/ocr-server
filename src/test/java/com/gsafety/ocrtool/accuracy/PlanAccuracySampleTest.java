package com.gsafety.ocrtool.accuracy;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;
import com.gsafety.ocrtool.document.DocumentFileType;
import com.gsafety.ocrtool.document.DownloadedDocument;
import com.gsafety.ocrtool.document.ParsedDocument;
import com.gsafety.ocrtool.document.WordDocumentParser;
import com.gsafety.ocrtool.segment.PlanSegmentService;
import com.gsafety.ocrtool.segment.ResponseLevelSegment;
import com.gsafety.ocrtool.segment.SegmentResult;
import com.gsafety.ocrtool.segment.SegmentRules;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.util.StringUtils;

/**
 * 使用本地真实预案执行结构化识别回归。
 *
 * <p>样本文件可能包含业务资料，因此仓库只保存文件名、哈希和期望结果。
 * 通过 PLAN_ACCURACY_SAMPLE_DIR 指向本机样本目录后才执行本测试。</p>
 */
@EnabledIfEnvironmentVariable(named = "PLAN_ACCURACY_SAMPLE_DIR", matches = ".+")
class PlanAccuracySampleTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String SAMPLE_DIRECTORY_ENV = "PLAN_ACCURACY_SAMPLE_DIR";
    private final WordDocumentParser parser = new WordDocumentParser();
    private final PlanSegmentService segmentService = new PlanSegmentService(PlanAccuracySampleTest::accuracyRules);

    @TestFactory
    Stream<DynamicTest> recognizesExpectedContentFromRealPlans() throws Exception {
        SampleManifest manifest;
        try (InputStream input = getClass().getResourceAsStream("/accuracy/plan-samples.json")) {
            assertThat(input).as("准确率样本清单").isNotNull();
            manifest = OBJECT_MAPPER.readValue(input, SampleManifest.class);
        }
        return manifest.samples().stream()
                .map(sample -> DynamicTest.dynamicTest(sample.fileName(), () -> verifySample(sample)));
    }

    /** 校验文件身份、解析结果以及该预案实际采用的响应级别。 */
    private void verifySample(SampleDefinition sample) throws Exception {
        Path samplePath = Path.of(System.getenv(SAMPLE_DIRECTORY_ENV)).resolve(sample.fileName());
        assertThat(samplePath).as("本地样本文件").isRegularFile();
        assertThat(sha256(samplePath)).as("样本文件哈希").isEqualToIgnoringCase(sample.sha256());

        DocumentFileType fileType = sample.fileName().toLowerCase().endsWith(".docx")
                ? DocumentFileType.DOCX
                : DocumentFileType.DOC;
        DownloadedDocument downloaded = new DownloadedDocument(
                samplePath,
                sample.fileName(),
                "application/msword",
                Files.size(samplePath),
                fileType);
        ParsedDocument parsed = parser.parse(downloaded);
        assertThat(parsed.blocks()).as("Word 解析后的文本块").isNotEmpty();

        SegmentResult result = segmentService.extract(parsed);
        verifyLevels("应急响应", result.emergencyResponses(), sample.emergencyLevelKeys(),
                sample.requireCompleteContent());
        verifyLevels("预警响应", result.warningResponses(), sample.warningLevelKeys(),
                sample.requireCompleteContent());
    }

    /**
     * 只评估清单声明为适用的级别，避免把三等级预案的第四级误算为漏识别。
     */
    private void verifyLevels(
            String category,
            List<ResponseLevelSegment> actual,
            List<String> expectedKeys,
            boolean requireCompleteContent) {
        for (String key : expectedKeys) {
            ResponseLevelSegment level = actual.stream()
                    .filter(item -> key.equals(item.key()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(category + "缺少级别：" + key));
            assertThat(level.status()).as(category + " " + key + " 状态").isNotEqualTo("MISSING");
            if (requireCompleteContent) {
                assertThat(level.activationConditions())
                        .as(category + " " + key + " 启动条件")
                        .isNotBlank();
                assertThat(firstNonBlank(level.directResponseMeasures(), level.responseMeasures()))
                        .as(category + " " + key + " 响应措施")
                        .isNotBlank();
            }
        }
        if (requireCompleteContent) {
            actual.stream()
                    .filter(level -> !expectedKeys.contains(level.key()))
                    .forEach(level -> assertThat(level.status())
                            .as(category + " 不适用级别 " + level.key())
                            .isEqualTo("MISSING"));
        }
    }

    private String firstNonBlank(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, length);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * 与生产默认规则保持同一语义，同时明确加入本轮样本覆盖到的章节和标记别名。
     */
    private static SegmentRules accuracyRules() {
        Map<String, List<String>> responseAliases = new LinkedHashMap<>();
        responseAliases.put("一级响应", List.of("一级响应", "一级应急响应", "Ⅰ级响应", "I级响应", "特别重大响应"));
        responseAliases.put("二级响应", List.of("二级响应", "二级应急响应", "Ⅱ级响应", "II级响应", "重大响应"));
        responseAliases.put("三级响应", List.of("三级响应", "三级应急响应", "Ⅲ级响应", "III级响应", "较大响应"));
        responseAliases.put("四级响应", List.of("四级响应", "四级应急响应", "Ⅳ级响应", "IV级响应", "一般响应"));

        Map<String, String> responseKeys = new LinkedHashMap<>();
        responseKeys.put("一级响应", "level_1");
        responseKeys.put("二级响应", "level_2");
        responseKeys.put("三级响应", "level_3");
        responseKeys.put("四级响应", "level_4");

        Map<String, List<String>> warningAliases = new LinkedHashMap<>();
        warningAliases.put("一级预警", List.of("一级预警", "Ⅰ级预警", "I级预警", "红色预警"));
        warningAliases.put("二级预警", List.of("二级预警", "Ⅱ级预警", "II级预警", "橙色预警"));
        warningAliases.put("三级预警", List.of("三级预警", "Ⅲ级预警", "III级预警", "黄色预警"));
        warningAliases.put("四级预警", List.of("四级预警", "Ⅳ级预警", "IV级预警", "蓝色预警"));

        Map<String, String> warningKeys = new LinkedHashMap<>();
        warningKeys.put("一级预警", "warning_level_1");
        warningKeys.put("二级预警", "warning_level_2");
        warningKeys.put("三级预警", "warning_level_3");
        warningKeys.put("四级预警", "warning_level_4");

        Map<String, List<String>> sections = Map.of(
                "warning_scope", List.of("监测预警", "预警响应"),
                "emergency_scope", List.of("应急响应", "分级响应", "省级应急响应"),
                "action_group_scope", List.of("工作组", "行动组", "现场指挥部工作组"));
        Map<String, List<String>> markers = Map.of(
                "activation_condition", List.of("启动条件", "响应条件", "发布条件"),
                "response_measure", List.of("响应措施", "处置措施", "协调措施"),
                "inheritance", List.of("在上一级响应措施基础上", "在前一级响应措施基础上"),
                "action_group", List.of("工作组", "行动组"));

        return new SegmentRules(
                "command_system",
                List.of("指挥体系", "组织指挥体系", "应急指挥体系", "指挥机构及职责", "组织机构及职责"),
                responseAliases,
                responseKeys,
                warningAliases,
                warningKeys,
                sections,
                markers,
                List.of("启动条件调整", "响应终止", "综合保障", "后期处置", "附则", "附件"),
                "accuracy-sample-v1");
    }

    private record SampleManifest(List<SampleDefinition> samples) {
    }

    private record SampleDefinition(
            String fileName,
            String sha256,
            List<String> emergencyLevelKeys,
            List<String> warningLevelKeys,
            boolean requireCompleteContent,
            String note) {
    }
}
