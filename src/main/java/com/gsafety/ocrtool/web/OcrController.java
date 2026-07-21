package com.gsafety.ocrtool.web;

import com.gsafety.ocrtool.response.OcrBatchResponse;
import com.gsafety.ocrtool.response.OcrRecognizeResponse;
import com.gsafety.ocrtool.extraction.OcrExtractResult;
import com.gsafety.ocrtool.extraction.OcrExtractService;
import com.gsafety.ocrtool.recognition.OcrRecognitionService;
import com.gsafety.ocrtool.recognition.OcrResult;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/ocr")
public class OcrController {

    private final OcrRecognitionService ocrRecognitionService;
    private final OcrExtractService ocrExtractService;

    public OcrController(OcrRecognitionService ocrRecognitionService, OcrExtractService ocrExtractService) {
        this.ocrRecognitionService = ocrRecognitionService;
        this.ocrExtractService = ocrExtractService;
    }

    /**
     * 查询当前服务已配置的 OCR 抽取模板编码。
     *
     * <p>前端可先调用该接口拿到可用的 {@code templateCode}，再调用
     * {@link #extract(MultipartFile, String)} 做结构化字段抽取。</p>
     *
     * @return 已配置的模板编码集合，例如 {@code safety_license}
     */
    @GetMapping("/templates")
    public Set<String> templates() {
        return ocrExtractService.templateCodes();
    }

    /**
     * 兼容旧版单图识别接口。
     *
     * <p>该接口只返回原始 OCR 文本和文本块坐标，适合已有调用方继续使用。
     * 新功能建议优先使用 {@link #recognize(MultipartFile)}，它会返回置信度、
     * 图片尺寸、预处理步骤和告警信息。</p>
     *
     * @param file 待识别图片，字段名固定为 {@code file}
     * @return 原始 OCR 识别结果
     */
    @PostMapping("/image")
    public OcrResult image(@RequestPart("file") MultipartFile file) {
        return ocrRecognitionService.recognizeLegacy(file);
    }

    /**
     * 通用单张图片文字识别接口。
     *
     * <p>该接口会先执行图片校验和 OpenCV 预处理，再调用 OCR 引擎识别。
     * 返回结果包含全文、文本块、平均置信度、图片尺寸、预处理步骤、告警信息和耗时。
     * 适合前端做图片文字预览、复制识别结果和人工核对。</p>
     *
     * @param file 待识别图片，字段名固定为 {@code file}
     * @return 带产品化元数据的单图识别结果
     */
    @PostMapping("/recognize")
    public OcrRecognizeResponse recognize(@RequestPart("file") MultipartFile file) {
        return ocrRecognitionService.recognize(file);
    }

    /**
     * 同步批量图片文字识别接口。
     *
     * <p>按上传顺序逐张识别图片。某一张图片识别失败时，只会在对应 item 中返回失败信息，
     * 不会中断其他图片的识别流程。适合前端一次上传多张图片并展示逐张处理结果。</p>
     *
     * @param files 待识别图片数组，字段名固定为 {@code files}
     * @return 批量识别汇总和逐张识别结果
     */
    @PostMapping("/batch")
    public OcrBatchResponse batch(@RequestPart("files") MultipartFile[] files) {
        return ocrRecognitionService.recognizeBatch(files);
    }

    /**
     * 按模板抽取结构化字段。
     *
     * <p>该接口同样会走图片校验、OpenCV 预处理和 OCR 识别流程，然后根据
     * {@code templateCode} 对应的模板配置，从 OCR 文本块中抽取业务字段。
     * 前端需要“上传证照图片后自动回填表单字段”时，应优先使用该接口。</p>
     *
     * @param file 待识别图片，字段名固定为 {@code file}
     * @param templateCode 模板编码，例如 {@code safety_license}
     * @return 模板字段抽取结果，包含字段值、字段置信度、低置信度字段和原始 OCR 文本
     */
    @PostMapping("/extract")
    public OcrExtractResult extract(
            @RequestPart("file") MultipartFile file,
            @RequestParam("templateCode") String templateCode) {
        return ocrExtractService.extract(templateCode, file);
    }
}
