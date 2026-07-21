package com.gsafety.ocrtool.extraction;

import com.gsafety.ocrtool.config.OcrTemplateProperties;
import com.gsafety.ocrtool.recognition.OcrLine;
import com.gsafety.ocrtool.recognition.OcrPoint;
import com.gsafety.ocrtool.recognition.OcrRecognitionService;
import com.gsafety.ocrtool.recognition.OcrResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OcrExtractServiceTest {

    @Test
    void extractsDateRangeAndRepairsSplitIssueDate() {
        OcrRecognitionService recognitionService = mock(OcrRecognitionService.class);
        when(recognitionService.recognizeLegacy(any())).thenReturn(new OcrResult("", List.of(
                line("（闽）FM安许证字（2024）E01号", 686, 465, 1051, 499),
                line("编号", 618, 480, 682, 512),
                line("新保林矿区建筑用砂岩矿（机制砂用）", 316, 577, 808, 611),
                line("主要负责人 高伟", 131, 613, 385, 677),
                line("有效期2024年02月01日", 133, 876, 510, 915),
                line("至2027年01月31日", 577, 872, 848, 918),
                line("应急您", 1247, 857, 1343, 904),
                line("发证日期2024", 977, 985, 1247, 1029),
                line("第2", 1250, 981, 1326, 1019),
                line("月01日", 1348, 983, 1473, 1025)
        )));
        OcrExtractService service = new OcrExtractService(recognitionService, templateProperties());

        OcrExtractResult result = service.extract("safety_license", null);

        assertThat(result.fields().get("enterpriseName").value())
                .isEqualTo("新保林矿区建筑用砂岩矿（机制砂用）");
        assertThat(result.fields().get("licenseNumber").value())
                .isEqualTo("（闽）FM安许证字（2024）E01号");
        assertThat(result.fields().get("validPeriod").value())
                .isEqualTo("2024年02月01日至2027年01月31日");
        assertThat(result.fields().get("issueDate").value())
                .isEqualTo("2024年02月01日");
    }

    private OcrTemplateProperties templateProperties() {
        OcrTemplateProperties properties = new OcrTemplateProperties();
        OcrTemplateProperties.Template template = new OcrTemplateProperties.Template();
        Map<String, OcrTemplateProperties.Field> fields = new LinkedHashMap<>();
        fields.put("enterpriseName", field(List.of("企业名称"), List.of("许可范围", "主要负责人"), true, 0));
        fields.put("licenseNumber", field(List.of("编号", "许可证编号", "证书编号"), List.of("企业名称", "许可范围"), false, 1));
        fields.put("validPeriod", field(List.of("有效期"), List.of("发证机关", "发证日期"), false, 2));
        fields.put("issueDate", field(List.of("发证日期"), List.of("MEM"), false, 1));
        template.setFields(fields);
        properties.setTemplates(Map.of("safety_license", template));
        return properties;
    }

    private OcrTemplateProperties.Field field(
            List<String> labels,
            List<String> stopLabels,
            boolean mergeWrappedLines,
            int maxLines) {
        OcrTemplateProperties.Field field = new OcrTemplateProperties.Field();
        field.setLabels(labels);
        field.setStopLabels(stopLabels);
        field.setDirection("right");
        field.setMergeWrappedLines(mergeWrappedLines);
        field.setMaxLines(maxLines);
        return field;
    }

    private OcrLine line(String text, int xMin, int yMin, int xMax, int yMax) {
        return new OcrLine(text, 0.99, List.of(
                new OcrPoint(xMin, yMin),
                new OcrPoint(xMax, yMin),
                new OcrPoint(xMax, yMax),
                new OcrPoint(xMin, yMax)));
    }

    @Test
    void trimsOverCollectedSafetyLicenseFields() {
        OcrRecognitionService recognitionService = mock(OcrRecognitionService.class);
        when(recognitionService.recognizeLegacy(any())).thenReturn(new OcrResult("", List.of(
                line("企业名称 中电建六局（漳州）环保新材料有限公可范围建筑用砂岩露天开采***", 80, 320, 760, 360),
                line("编号", 618, 480, 682, 512),
                line("主要负责人 高伟", 131, 613, 385, 677),
                line("有效期2024年02月01日", 133, 876, 510, 915),
                line("至2027年01月31日", 577, 872, 848, 918),
                line("福建省应急营理厅", 850, 872, 1050, 918),
                line("发证日期2024", 977, 985, 1247, 1029),
                line("行政审批专用章", 1227, 963, 1354, 995),
                line("每", 1250, 981, 1326, 1019),
                line("月01日", 1348, 983, 1473, 1025)
        )));
        OcrExtractService service = new OcrExtractService(recognitionService, templateProperties());

        OcrExtractResult result = service.extract("safety_license", null);

        assertThat(result.fields().get("enterpriseName").value())
                .isEqualTo("中电建六局（漳州）环保新材料有限公司");
        assertThat(result.fields().get("validPeriod").value())
                .isEqualTo("2024年02月01日至2027年01月31日");
        assertThat(result.fields().get("issueDate").value())
                .isEqualTo("2024年02月01日");
    }

    @Test
    void extractsLicenseNumberFromWholePageWhenLabelMissesValue() {
        OcrRecognitionService recognitionService = mock(OcrRecognitionService.class);
        when(recognitionService.recognizeLegacy(any())).thenReturn(new OcrResult("", List.of(
                line("安全生产许可证", 407, 344, 1204, 466),
                line("编号", 618, 480, 682, 512),
                line("（闽）FM安许证字", 686, 465, 920, 499),
                line("（2024）E01号", 921, 468, 1051, 499),
                line("企业名称 中电建六局（漳州）环保新材料有限公司", 80, 520, 760, 560),
                line("主要负责人 高伟", 131, 613, 385, 677)
        )));
        OcrExtractService service = new OcrExtractService(recognitionService, templateProperties());

        OcrExtractResult result = service.extract("safety_license", null);

        assertThat(result.fields().get("licenseNumber").value())
                .isEqualTo("（闽）FM安许证字（2024）E01号");
    }

    @Test
    void extractsLicenseNumberWhenValueIsAboveLabelBaseline() {
        OcrRecognitionService recognitionService = mock(OcrRecognitionService.class);
        when(recognitionService.recognizeLegacy(any())).thenReturn(new OcrResult("", List.of(
                line("（闽）FM安许证字（2024）E01号", 686, 445, 1051, 475),
                line("编号", 618, 520, 682, 550),
                line("企业名称 中电建六局（漳州）环保新材料有限公司", 80, 580, 760, 620),
                line("主要负责人 高伟", 131, 650, 385, 680)
        )));
        OcrExtractService service = new OcrExtractService(recognitionService, templateProperties());

        OcrExtractResult result = service.extract("safety_license", null);

        assertThat(result.fields().get("licenseNumber").value())
                .isEqualTo("（闽）FM安许证字（2024）E01号");
    }
}
