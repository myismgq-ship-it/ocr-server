package com.gsafety.ocrtool.document;

/** 文档最终采用的解析模式。 */
public enum DocumentParseMode {
    /** Word 文档结构化提取。 */
    WORD,
    /** PDF 全部页面使用文本层。 */
    PDF_TEXT,
    /** PDF 全部有效页面依赖 OCR。 */
    OCR,
    /** PDF 同时包含文本页和 OCR 扫描页。 */
    HYBRID
}
