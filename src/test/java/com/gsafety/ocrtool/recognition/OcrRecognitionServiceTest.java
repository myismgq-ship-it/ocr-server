package com.gsafety.ocrtool.recognition;

import com.gsafety.ocrtool.engine.OcrEngine;
import com.gsafety.ocrtool.common.OcrException;
import com.gsafety.ocrtool.config.OcrProperties;
import com.gsafety.ocrtool.recognition.OcrLine;
import com.gsafety.ocrtool.recognition.OcrPoint;
import com.gsafety.ocrtool.recognition.OcrResult;
import com.gsafety.ocrtool.preprocess.ImagePreprocessor;
import com.gsafety.ocrtool.preprocess.PreprocessedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import static org.assertj.core.api.Assertions.assertThat;

class OcrRecognitionServiceTest {

    @Test
    void recognizeReturnsProductizedMetadata() throws Exception {
        OcrRecognitionService service = new OcrRecognitionService(fakePreprocessor(), fakeEngine(), new OcrProperties());
        MockMultipartFile file = new MockMultipartFile("file", "demo.png", "image/png", new byte[] {1, 2, 3});

        var response = service.recognize(file);

        assertThat(response.fileName()).isEqualTo("demo.png");
        assertThat(response.text()).isEqualTo("企业名称测试公司");
        assertThat(response.confidence()).isEqualTo(0.9);
        assertThat(response.engine()).isEqualTo("fake");
        assertThat(response.preprocessSteps()).contains("灰度化");
        assertThat(response.durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void batchKeepsProcessingWhenOneFileFails() {
        ImagePreprocessor preprocessor = file -> {
            if ("bad.txt".equals(file.getOriginalFilename())) {
                throw new OcrException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_IMAGE_TYPE", "OCR 只支持图片文件。");
            }
            return fakePreprocessor().preprocess(file);
        };
        OcrRecognitionService service = new OcrRecognitionService(preprocessor, fakeEngine(), new OcrProperties());

        var good = new MockMultipartFile("files", "good.png", "image/png", new byte[] {1});
        var bad = new MockMultipartFile("files", "bad.txt", "text/plain", new byte[] {1});
        var response = service.recognizeBatch(new MultipartFile[] {good, bad});

        assertThat(response.total()).isEqualTo(2);
        assertThat(response.successCount()).isEqualTo(1);
        assertThat(response.failureCount()).isEqualTo(1);
        assertThat(response.items()).extracting("success").containsExactly(true, false);
        assertThat(response.items().get(1).errorCode()).isEqualTo("UNSUPPORTED_IMAGE_TYPE");
    }

    @Test
    void recognizeMergesTextFromOriginalAndPreprocessedPasses() throws Exception {
        Path original = Files.createTempFile("ocr-original-", ".jpg");
        Path preprocessed = Files.createTempFile("ocr-preprocessed-", ".png");
        ImagePreprocessor preprocessor = file -> new PreprocessedImage(
                preprocessed,
                original,
                file.getOriginalFilename(),
                file.getContentType(),
                file.getSize(),
                640,
                480,
                List.of("灰度化"),
                List.of(),
                new ArrayList<>(List.of(preprocessed, original)));
        OcrEngine engine = new OcrEngine() {
            @Override
            public String name() {
                return "fake";
            }

            @Override
            public OcrResult recognize(Path imagePath) {
                if (imagePath.equals(preprocessed)) {
                    return new OcrResult("企业名称测试公司", List.of(
                            new OcrLine("企业名称测试公司", 0.9, List.of(new OcrPoint(0, 0))),
                            new OcrLine("主要负责人高伟", 0.9, List.of(new OcrPoint(0, 0)))));
                }
                return new OcrResult("（闽）FM安许证字（2024）E01号", List.of(
                        new OcrLine("（闽）FM安许证字（2024）E01号", 0.95, List.of(new OcrPoint(0, 0)))));
            }
        };
        OcrProperties properties = new OcrProperties();
        properties.getPreprocess().setMultiPassMinConfidence(0.95);
        OcrRecognitionService service = new OcrRecognitionService(preprocessor, engine, properties);
        MockMultipartFile file = new MockMultipartFile("file", "demo.png", "image/png", new byte[] {1, 2, 3});

        var response = service.recognize(file);

        assertThat(response.text()).contains("企业名称测试公司", "主要负责人高伟", "（闽）FM安许证字（2024）E01号");
        assertThat(response.lines()).extracting(OcrLine::text).containsExactly("企业名称测试公司", "主要负责人高伟");
    }

    private ImagePreprocessor fakePreprocessor() {
        return file -> {
            try {
                Path path = Files.createTempFile("ocr-test-", ".png");
                Files.write(path, new byte[] {1});
                return new PreprocessedImage(
                        path,
                        path,
                        file.getOriginalFilename(),
                        file.getContentType(),
                        file.getSize(),
                        640,
                        480,
                        List.of("灰度化"),
                        List.of(),
                        List.of(path));
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        };
    }

    private OcrEngine fakeEngine() {
        return new OcrEngine() {
            @Override
            public String name() {
                return "fake";
            }

            @Override
            public OcrResult recognize(Path imagePath) {
                return new OcrResult(
                        "企业名称测试公司",
                        List.of(new OcrLine(
                                "企业名称测试公司",
                                0.9,
                                List.of(new OcrPoint(0, 0), new OcrPoint(100, 0)))));
            }
        };
    }
}

