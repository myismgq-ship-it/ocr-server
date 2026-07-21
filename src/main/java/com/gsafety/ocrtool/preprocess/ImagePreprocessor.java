package com.gsafety.ocrtool.preprocess;

import org.springframework.web.multipart.MultipartFile;

public interface ImagePreprocessor {

    PreprocessedImage preprocess(MultipartFile file);
}

