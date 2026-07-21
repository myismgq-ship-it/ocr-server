package com.gsafety.ocrtool.common;

import org.springframework.http.HttpStatus;

public class OcrException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public OcrException(HttpStatus status, String message) {
        this(status, "OCR_ERROR", message);
    }

    public OcrException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public OcrException(HttpStatus status, String message, Throwable cause) {
        this(status, "OCR_ERROR", message, cause);
    }

    public OcrException(HttpStatus status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}


