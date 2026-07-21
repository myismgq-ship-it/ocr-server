package com.gsafety.ocrtool.engine;

import com.gsafety.ocrtool.recognition.OcrResult;
import java.nio.file.Path;

public interface OcrEngine {

    String name();

    OcrResult recognize(Path imagePath);
}

