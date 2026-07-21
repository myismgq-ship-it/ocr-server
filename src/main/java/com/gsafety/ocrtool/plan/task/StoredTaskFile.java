package com.gsafety.ocrtool.plan.task;

public record StoredTaskFile(
        String fileName,
        String contentType,
        long size,
        String fileType,
        String path) {
}
