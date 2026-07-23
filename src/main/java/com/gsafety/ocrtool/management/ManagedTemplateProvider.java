package com.gsafety.ocrtool.management;

import com.gsafety.ocrtool.config.OcrTemplateProperties;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 查询运行时已发布 OCR 模板，并把 JSONB 定义转换为抽取配置对象。
 */
@Component
public class ManagedTemplateProvider {

    /** 模板修订表查询入口。 */
    private final JdbcTemplate jdbc;
    /** JSONB 模板定义反序列化器。 */
    private final ObjectMapper objectMapper;

    public ManagedTemplateProvider(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询指定编码唯一的已发布模板；不存在时由调用方回退到静态 YAML 模板。
     */
    public Optional<OcrTemplateProperties.Template> findPublished(String templateCode) {
        return jdbc.query(
                        "SELECT definition::text FROM ocr_template_revision "
                                + "WHERE template_code = ? AND status = 'PUBLISHED'",
                        (rs, rowNum) -> readTemplate(rs.getString(1)),
                        templateCode)
                .stream()
                .findFirst();
    }

    /**
     * 返回所有已发布的动态模板编码。
     */
    public Set<String> publishedCodes() {
        return new LinkedHashSet<>(jdbc.queryForList(
                "SELECT template_code FROM ocr_template_revision "
                        + "WHERE status = 'PUBLISHED' ORDER BY template_code",
                String.class));
    }

    OcrTemplateProperties.Template readTemplate(String json) {
        try {
            return objectMapper.readValue(json, OcrTemplateProperties.Template.class);
        } catch (Exception ex) {
            throw new IllegalStateException("已发布 OCR 模板定义无法读取。", ex);
        }
    }
}
