package com.gsafety.ocrtool.web;

import com.gsafety.ocrtool.recognition.OcrRecognitionService;
import com.gsafety.ocrtool.common.OcrException;
import com.gsafety.ocrtool.response.OcrRecognizeResponse;
import com.gsafety.ocrtool.extraction.OcrExtractService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OcrControllerTest {

    @Test
    void recognizeEndpointReturnsProductizedResult() throws Exception {
        OcrRecognitionService recognitionService = mock(OcrRecognitionService.class);
        OcrExtractService extractService = mock(OcrExtractService.class);
        when(recognitionService.recognize(any())).thenReturn(new OcrRecognizeResponse(
                "demo.png",
                "hello",
                List.of(),
                0.98,
                100,
                50,
                "rapidocr",
                List.of("灰度化"),
                List.of(),
                123));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new OcrController(recognitionService, extractService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        MockMultipartFile file = new MockMultipartFile("file", "demo.png", "image/png", new byte[] {1});

        mockMvc.perform(multipart("/api/ocr/recognize").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value("demo.png"))
                .andExpect(jsonPath("$.text").value("hello"))
                .andExpect(jsonPath("$.engine").value("rapidocr"));
    }

    @Test
    void recognizeEndpointUsesUnifiedErrorShape() throws Exception {
        OcrRecognitionService recognitionService = mock(OcrRecognitionService.class);
        OcrExtractService extractService = mock(OcrExtractService.class);
        when(recognitionService.recognize(any()))
                .thenThrow(new OcrException(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "OCR 图片不能为空。"));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new OcrController(recognitionService, extractService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        MockMultipartFile file = new MockMultipartFile("file", "demo.png", "image/png", new byte[0]);

        mockMvc.perform(multipart("/api/ocr/recognize").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMPTY_FILE"))
                .andExpect(jsonPath("$.message").value("OCR 图片不能为空。"));
    }
}

