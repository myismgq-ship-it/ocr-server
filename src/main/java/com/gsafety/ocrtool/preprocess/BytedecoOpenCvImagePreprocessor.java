package com.gsafety.ocrtool.preprocess;

import com.gsafety.ocrtool.common.OcrException;
import com.gsafety.ocrtool.config.OcrProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Point2f;
import org.bytedeco.opencv.opencv_core.Scalar4i;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_core.Size;
import org.bytedeco.opencv.opencv_imgproc.CLAHE;
import org.bytedeco.opencv.opencv_imgproc.Vec4iVector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import static org.bytedeco.opencv.global.opencv_core.BORDER_REPLICATE;
import static org.bytedeco.opencv.global.opencv_imgcodecs.IMREAD_COLOR;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imread;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imwrite;
import static org.bytedeco.opencv.global.opencv_imgproc.Canny;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2GRAY;
import static org.bytedeco.opencv.global.opencv_imgproc.HoughLinesP;
import static org.bytedeco.opencv.global.opencv_imgproc.INTER_CUBIC;
import static org.bytedeco.opencv.global.opencv_imgproc.INTER_LINEAR;
import static org.bytedeco.opencv.global.opencv_imgproc.THRESH_BINARY;
import static org.bytedeco.opencv.global.opencv_imgproc.THRESH_OTSU;
import static org.bytedeco.opencv.global.opencv_imgproc.createCLAHE;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;
import static org.bytedeco.opencv.global.opencv_imgproc.getRotationMatrix2D;
import static org.bytedeco.opencv.global.opencv_imgproc.medianBlur;
import static org.bytedeco.opencv.global.opencv_imgproc.resize;
import static org.bytedeco.opencv.global.opencv_imgproc.threshold;
import static org.bytedeco.opencv.global.opencv_imgproc.warpAffine;

/**
 * 基于 Bytedeco OpenCV 的图片校验和增强实现。
 *
 * <p>除文件大小外还限制解码后的像素总量，并显式关闭所有 {@code Mat}、CLAHE、
 * 几何对象和中间矩阵，避免批量 OCR 时原生内存持续增长。</p>
 */
@Component
public class BytedecoOpenCvImagePreprocessor implements ImagePreprocessor {

    private static final Logger log = LoggerFactory.getLogger(BytedecoOpenCvImagePreprocessor.class);

    private static final Set<String> ALLOWED_SUFFIXES =
            Set.of(".jpg", ".jpeg", ".png", ".bmp", ".tif", ".tiff", ".webp");

    /** 图片大小、像素上限和增强算法参数。 */
    private final OcrProperties properties;

    public BytedecoOpenCvImagePreprocessor(OcrProperties properties) {
        this.properties = properties;
    }

    /**
     * 校验并预处理 multipart 上传图片。
     *
     * @param file 上传图片
     * @return 持有原图、处理图和临时资源清单的可关闭对象
     */
    @Override
    public PreprocessedImage preprocess(MultipartFile file) {
        validateFile(file);
        List<Path> tempFiles = new ArrayList<>();
        Path source = copyToTempFile(file, tempFiles);
        return preprocessSource(
                source,
                safeFileName(file),
                file.getContentType(),
                file.getSize(),
                tempFiles);
    }

    /**
     * 预处理已落盘图片，供 PDF 页面 OCR 等内部流程复用。
     *
     * @param deleteSourceOnClose 是否在结果关闭时一并删除输入文件
     */
    @Override
    public PreprocessedImage preprocess(
            Path sourcePath,
            String fileName,
            String contentType,
            long fileSize,
            boolean deleteSourceOnClose) {
        if (sourcePath == null || !Files.isRegularFile(sourcePath)) {
            throw new OcrException(HttpStatus.BAD_REQUEST, "INVALID_IMAGE", "图片文件不存在或不可读取。");
        }
        if (fileSize > properties.getImage().getMaxSize()) {
            throw new OcrException(HttpStatus.BAD_REQUEST, "FILE_TOO_LARGE", "OCR 图片大小超过限制。");
        }
        List<Path> tempFiles = new ArrayList<>();
        if (deleteSourceOnClose) {
            tempFiles.add(sourcePath);
        }
        return preprocessSource(
                sourcePath,
                StringUtils.hasText(fileName) ? fileName : "image.png",
                contentType,
                fileSize,
                tempFiles);
    }

    /**
     * 统一执行解码、像素校验、增强、写盘以及失败回退。
     */
    private PreprocessedImage preprocessSource(
            Path source,
            String fileName,
            String contentType,
            long fileSize,
            List<Path> tempFiles) {
        List<String> steps = new ArrayList<>();
        // imread 会分配堆外 Mat；任何返回或异常路径都必须关闭 original。
        List<String> warnings = new ArrayList<>();
        Mat original = imread(source.toString(), IMREAD_COLOR);
        if (original == null || original.empty()) {
            closeQuietly(original);
            deleteTempFiles(tempFiles);
            throw new OcrException(HttpStatus.BAD_REQUEST, "INVALID_IMAGE", "图片内容不可读取，请上传有效图片。");
        }

        int imageWidth = original.cols();
        int imageHeight = original.rows();
        try {
            validatePixelCount(imageWidth, imageHeight);
            if (!properties.getPreprocess().isEnabled()) {
                steps.add("保留原图");
                return new PreprocessedImage(
                        source, source, fileName, contentType, fileSize, imageWidth, imageHeight,
                        List.copyOf(steps), List.copyOf(warnings), tempFiles);
            }

            try {
                Path output = Files.createTempFile("ocr-preprocessed-", ".png");
                tempFiles.add(output);
                Mat processed = enhance(original, steps, warnings);
                boolean written;
                try {
                    written = imwrite(output.toString(), processed);
                } finally {
                    closeQuietly(processed);
                }
                if (!written) {
                    warnings.add("图片预处理结果写入失败，已回退到原图。");
                    return fallback(
                            fileName, contentType, fileSize, source,
                            imageWidth, imageHeight, steps, warnings, tempFiles);
                }
                steps.add("输出 PNG");
                return new PreprocessedImage(
                        output, source, fileName, contentType, fileSize, imageWidth, imageHeight,
                        List.copyOf(steps), List.copyOf(warnings), tempFiles);
            } catch (RuntimeException | IOException ex) {
                log.warn("OCR 图片预处理失败，已回退到原图，fileName={}", fileName, ex);
                warnings.add("图片预处理失败，已回退到原图。");
                return fallback(
                        fileName, contentType, fileSize, source,
                        imageWidth, imageHeight, steps, warnings, tempFiles);
            }
        } catch (OcrException ex) {
            deleteTempFiles(tempFiles);
            throw ex;
        } finally {
            closeQuietly(original);
        }
    }

    /**
     * 限制解码后像素总量，防止小体积压缩图片导致过大的原生内存分配。
     */
    private void validatePixelCount(int width, int height) {
        long pixels = (long) width * height;
        long maxPixels = properties.getImage().getMaxPixels();
        if (maxPixels > 0 && pixels > maxPixels) {
            throw new OcrException(HttpStatus.BAD_REQUEST, "IMAGE_PIXEL_LIMIT_EXCEEDED", "图片像素总量超过限制。");
        }
    }

    /**
     * 执行放大、灰度、去噪、对比度增强和可选倾斜矫正。
     *
     * @return 由调用方负责关闭的最终 Mat
     */
    private Mat enhance(Mat original, List<String> steps, List<String> warnings) {
        Mat resized = null;
        // 每个原生对象单独持有引用，便于在 finally 中精确释放所有中间资源。
        Mat gray = new Mat();
        Mat denoised = new Mat();
        Mat enhanced = new Mat();
        CLAHE clahe = null;
        Size claheGrid = null;
        try {
            Mat working = original;
            int minSide = Math.min(working.cols(), working.rows());
            int targetMinSide = properties.getPreprocess().getMinReadableSide();
            if (targetMinSide > 0 && minSide > 0 && minSide < targetMinSide) {
                double scale = (double) targetMinSide / minSide;
                resized = new Mat();
                Size outputSize = new Size();
                try {
                    resize(working, resized, outputSize, scale, scale, INTER_CUBIC);
                } finally {
                    closeQuietly(outputSize);
                }
                working = resized;
                steps.add("小图放大");
            }

            cvtColor(working, gray, COLOR_BGR2GRAY);
            steps.add("灰度化");
            medianBlur(gray, denoised, 3);
            steps.add("轻量去噪");

            claheGrid = new Size(8, 8);
            clahe = createCLAHE(2.0, claheGrid);
            clahe.apply(denoised, enhanced);
            steps.add("对比度增强");

            Double angle = estimateDeskewAngle(enhanced);
            if (angle != null && Math.abs(angle) > 0.3) {
                Mat rotated = new Mat();
                Point2f center = new Point2f(enhanced.cols() / 2.0f, enhanced.rows() / 2.0f);
                Mat matrix = null;
                Size outputSize = null;
                Scalar borderValue = null;
                try {
                    matrix = getRotationMatrix2D(center, angle, 1.0);
                    outputSize = enhanced.size();
                    borderValue = new Scalar();
                    warpAffine(enhanced, rotated, matrix, outputSize, INTER_LINEAR, BORDER_REPLICATE, borderValue);
                    steps.add("倾斜矫正");
                    return rotated;
                } catch (RuntimeException ex) {
                    closeQuietly(rotated);
                    throw ex;
                } finally {
                    closeQuietly(borderValue);
                    closeQuietly(outputSize);
                    closeQuietly(matrix);
                    closeQuietly(center);
                }
            // 最终结果转移给调用方；其余中间对象统一在 finally 中释放。
            }
            warnings.add("未检测到需要矫正的明显倾斜。");
            Mat result = enhanced;
            enhanced = null;
            return result;
        } finally {
            closeQuietly(clahe);
            closeQuietly(claheGrid);
            closeQuietly(enhanced);
            closeQuietly(denoised);
            closeQuietly(gray);
            closeQuietly(resized);
        }
    }

    private Double estimateDeskewAngle(Mat image) {
    /**
     * 根据霍夫线段角度中位数估算倾斜角度，所有检测矩阵均在本方法内释放。
     */
        Mat binary = new Mat();
        Mat edges = new Mat();
        Vec4iVector lines = new Vec4iVector();
        try {
            threshold(image, binary, 0, 255, THRESH_BINARY | THRESH_OTSU);
            Canny(binary, edges, 50, 150);
            HoughLinesP(edges, lines, 1, Math.PI / 180, 80, Math.max(80, image.cols() / 4.0), 20);
            if (lines.empty()) {
                return null;
            }

            List<Double> angles = new ArrayList<>();
            for (long i = 0; i < lines.size(); i++) {
                Scalar4i line = lines.get(i);
                int x1 = line.get(0);
                int y1 = line.get(1);
                int x2 = line.get(2);
                int y2 = line.get(3);
                double angle = Math.toDegrees(Math.atan2(y2 - y1, x2 - x1));
                if (angle > 45) {
                    angle -= 90;
                } else if (angle < -45) {
                    angle += 90;
                }
                if (Math.abs(angle) <= properties.getPreprocess().getMaxDeskewAngle()) {
                    angles.add(angle);
                }
            }
            if (angles.isEmpty()) {
                return null;
            }
            angles.sort(Comparator.naturalOrder());
            return -angles.get(angles.size() / 2);
        } finally {
            closeQuietly(lines);
            closeQuietly(edges);
            closeQuietly(binary);
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new OcrException(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "OCR 图片不能为空。");
        }
        if (file.getSize() > properties.getImage().getMaxSize()) {
            throw new OcrException(HttpStatus.BAD_REQUEST, "FILE_TOO_LARGE", "OCR 图片大小超过限制。");
        }
        String contentType = file.getContentType();
        if (!StringUtils.hasText(contentType) || !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            throw new OcrException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_IMAGE_TYPE", "OCR 只支持图片文件。");
        }
        String suffix = suffix(file.getOriginalFilename());
        if (!ALLOWED_SUFFIXES.contains(suffix)) {
            throw new OcrException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_IMAGE_TYPE", "不支持的图片格式：" + suffix);
        }
    }

    private Path copyToTempFile(MultipartFile file, List<Path> tempFiles) {
        try {
            Path tempFile = Files.createTempFile("ocr-upload-", suffix(file.getOriginalFilename()));
            tempFiles.add(tempFile);
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
            return tempFile;
        } catch (IOException ex) {
            throw new OcrException(HttpStatus.INTERNAL_SERVER_ERROR, "TEMP_FILE_FAILED", "OCR 图片临时文件处理失败。", ex);
        }
    }

    private PreprocessedImage fallback(
            String fileName,
            String contentType,
            long fileSize,
            Path source,
            int imageWidth,
            int imageHeight,
            List<String> steps,
            List<String> warnings,
            List<Path> tempFiles) {
        steps.add("使用原图");
        return new PreprocessedImage(
                source,
                source,
                fileName,
                contentType,
                fileSize,
                imageWidth,
                imageHeight,
                List.copyOf(steps),
                List.copyOf(warnings),
                tempFiles);
    }

    private String safeFileName(MultipartFile file) {
        return StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "image";
    }

    private String suffix(String filename) {
        if (!StringUtils.hasText(filename)) {
            return ".png";
        }
        int index = filename.lastIndexOf('.');
        if (index < 0 || index == filename.length() - 1) {
            return ".png";
        }
        String suffix = filename.substring(index).toLowerCase(Locale.ROOT);
        return suffix.length() > 10 ? ".png" : suffix;
    }

    private void deleteTempFiles(List<Path> tempFiles) {
        for (Path tempFile : tempFiles) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ex) {
                log.warn("OCR 校验失败后的临时文件清理失败，path={}", tempFile, ex);
            }
        }
    }

    private void closeQuietly(AutoCloseable closeable) {
    /**
     * 关闭 Bytedeco 原生对象；清理失败只记录日志，不覆盖原始业务结果。
     */
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ex) {
            log.debug("OpenCV 原生资源释放失败。", ex);
        }
    }
}

