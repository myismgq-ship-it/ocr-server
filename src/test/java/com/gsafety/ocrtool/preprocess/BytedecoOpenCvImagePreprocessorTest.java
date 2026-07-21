package com.gsafety.ocrtool.preprocess;

import com.gsafety.ocrtool.common.OcrException;
import com.gsafety.ocrtool.config.OcrProperties;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BytedecoOpenCvImagePreprocessorTest {

    @Test
    void rejectsUnsupportedContentType() {
        BytedecoOpenCvImagePreprocessor preprocessor = new BytedecoOpenCvImagePreprocessor(new OcrProperties());
        MockMultipartFile file = new MockMultipartFile("file", "demo.txt", "text/plain", "hello".getBytes());

        assertThatThrownBy(() -> preprocessor.preprocess(file))
                .isInstanceOf(OcrException.class)
                .extracting("code")
                .isEqualTo("UNSUPPORTED_IMAGE_TYPE");
    }

    @Test
    void preprocessesReadableImage() throws Exception {
        OcrProperties properties = new OcrProperties();
        properties.getPreprocess().setMinReadableSide(200);
        BytedecoOpenCvImagePreprocessor preprocessor = new BytedecoOpenCvImagePreprocessor(properties);
        MockMultipartFile file = new MockMultipartFile("file", "demo.png", "image/png", imageBytes());

        try (PreprocessedImage image = preprocessor.preprocess(file)) {
            assertThat(image.imagePath()).exists();
            assertThat(image.imageWidth()).isEqualTo(160);
            assertThat(image.imageHeight()).isEqualTo(80);
            assertThat(image.steps()).contains("小图放大", "灰度化", "轻量去噪", "对比度增强", "输出 PNG");
        }
    }

    private byte[] imageBytes() throws Exception {
        BufferedImage image = new BufferedImage(160, 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 160, 80);
        graphics.setColor(Color.BLACK);
        graphics.drawString("OCR TEST", 30, 42);
        graphics.dispose();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}

