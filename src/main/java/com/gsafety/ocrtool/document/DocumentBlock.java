package com.gsafety.ocrtool.document;

import java.util.List;

public record DocumentBlock(
        String text,
        int page,
        int headingLevel,
        boolean table,
        List<String> cells) {

    public boolean heading() {
        return headingLevel > 0;
    }
}
