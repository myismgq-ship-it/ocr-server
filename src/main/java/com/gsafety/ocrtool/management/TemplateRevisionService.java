package com.gsafety.ocrtool.management;

import com.gsafety.ocrtool.common.OcrException;
import com.gsafety.ocrtool.config.OcrTemplateProperties;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * OCR 模板草稿、校验、发布、回滚和版本历史服务。
 *
 * <p>同一模板编码的版本号分配和发布操作使用 PostgreSQL 事务级 advisory lock 串行化。</p>
 */
@Service
public class TemplateRevisionService {

    /** 模板修订表读写入口。 */
    private final JdbcTemplate jdbc;
    /** JSONB 定义序列化器。 */
    private final ObjectMapper objectMapper;
    /** 已发布模板读取和定义转换入口。 */
    private final ManagedTemplateProvider provider;

    public TemplateRevisionService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            ManagedTemplateProvider provider) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.provider = provider;
    }

    /**
     * 创建下一版本草稿；只保存定义，不自动发布。
     *
     * @param createdBy 来自网关审计头的调用方标识
     */
    @Transactional
    public TemplateRevisionResponse createDraft(
            String templateCode,
            Map<String, Object> definition,
            String createdBy) {
        validateCode(templateCode);
        // 先锁定模板编码，再计算 MAX + 1，避免并发草稿获得相同版本号。
        lock("ocr-template:" + templateCode);
        Map<String, Object> safeDefinition = definition == null
                ? Map.of()
                : new LinkedHashMap<>(definition);
        int next = jdbc.queryForObject(
                "SELECT COALESCE(MAX(revision_number), 0) + 1 "
                        + "FROM ocr_template_revision WHERE template_code = ?",
                Integer.class,
                templateCode);
        UUID revisionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update(
                "INSERT INTO ocr_template_revision "
                        + "(revision_id, template_code, revision_number, status, definition, created_by, created_at) "
                        + "VALUES (?, ?, ?, 'DRAFT', CAST(? AS jsonb), ?, ?)",
                revisionId,
                templateCode,
                next,
                write(safeDefinition),
                caller(createdBy),
                now);
        return get(revisionId);
    }

    /** 按版本号倒序查询指定模板的全部修订。 */
    public List<TemplateRevisionResponse> history(String templateCode) {
        validateCode(templateCode);
        return jdbc.query(
                "SELECT revision_id, template_code, revision_number, status, definition::text, "
                        + "created_by, created_at, published_at FROM ocr_template_revision "
                        + "WHERE template_code = ? ORDER BY revision_number DESC",
                this::mapRow,
                templateCode);
    }

    /** 查询单个模板修订，不存在时返回统一业务异常。 */
    public TemplateRevisionResponse get(UUID revisionId) {
        return jdbc.query(
                        "SELECT revision_id, template_code, revision_number, status, definition::text, "
                                + "created_by, created_at, published_at FROM ocr_template_revision "
                                + "WHERE revision_id = ?",
                        this::mapRow,
                        revisionId)
                .stream()
                .findFirst()
                .orElseThrow(() -> notFound("OCR 模板修订不存在。"));
    }

    /** 校验模板结构、标签、方向、置信度和字段正则。 */
    public ValidationResponse validate(UUID revisionId) {
        TemplateRevisionResponse revision = get(revisionId);
        return validateDefinition(revision.definition());
    }

    /**
     * 发布一个有效草稿，并在同一事务中归档此前发布版本。
     *
     * <p>数据库部分唯一索引保证每个 templateCode 最多一个 PUBLISHED 版本。</p>
     */
    @Transactional
    public TemplateRevisionResponse publish(UUID revisionId) {
        TemplateRevisionResponse revision = get(revisionId);
        lock("ocr-template:" + revision.templateCode());
        ValidationResponse validation = validateDefinition(revision.definition());
        if (!validation.valid()) {
            throw new OcrException(
                    HttpStatus.BAD_REQUEST,
                    "TEMPLATE_INVALID",
                    "OCR 模板校验失败：" + String.join("；", validation.errors()));
        }
        if (!RevisionStatus.DRAFT.name().equals(revision.status())) {
            throw new OcrException(
                    HttpStatus.CONFLICT,
                    "TEMPLATE_NOT_DRAFT",
                    "只有草稿状态的 OCR 模板可以发布。");
        }
        // 归档旧版本和发布新版本必须处于同一事务，不能出现两个已发布版本的窗口。
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update(
                "UPDATE ocr_template_revision SET status = 'ARCHIVED' "
                        + "WHERE template_code = ? AND status = 'PUBLISHED'",
                revision.templateCode());
        int updated = jdbc.update(
                "UPDATE ocr_template_revision SET status = 'PUBLISHED', published_at = ? "
                        + "WHERE revision_id = ? AND status = 'DRAFT'",
                now,
                revisionId);
        if (updated == 0) {
            throw new OcrException(HttpStatus.CONFLICT, "TEMPLATE_PUBLISH_CONFLICT", "OCR 模板发布状态已变化。");
        }
        return get(revisionId);
    }

    /**
     * 复制历史修订为新草稿并立即发布，历史记录本身保持不可变。
     */
    @Transactional
    public TemplateRevisionResponse rollback(UUID sourceRevisionId, String createdBy) {
        TemplateRevisionResponse source = get(sourceRevisionId);
        TemplateRevisionResponse draft = createDraft(
                source.templateCode(), source.definition(), createdBy);
        return publish(draft.revisionId());
    }

    /**
     * 将指定修订转换为可直接用于样本抽取的模板对象。
     */
    public OcrTemplateProperties.Template template(UUID revisionId) {
        return provider.readTemplate(write(get(revisionId).definition()));
    }

    /**
     * 对草稿定义执行纯校验，不产生数据库副作用。
     */
    ValidationResponse validateDefinition(Map<String, Object> definition) {
        List<String> errors = new ArrayList<>();
        OcrTemplateProperties.Template template;
        try {
            template = provider.readTemplate(write(definition));
        } catch (RuntimeException ex) {
            return new ValidationResponse(false, List.of("模板 JSON 结构不正确"));
        }
        Map<String, OcrTemplateProperties.Field> fields = template.getFields();
        if (fields == null || fields.isEmpty()) {
            errors.add("fields 不能为空");
        } else {
            fields.forEach((name, field) -> {
                if (!StringUtils.hasText(name) || field == null) {
                    errors.add("字段名称和定义不能为空");
                    return;
                }
                if (field.getLabels() == null
                        || field.getLabels().stream().noneMatch(StringUtils::hasText)) {
                    errors.add(name + ".labels 不能为空");
                }
                if (!"right".equalsIgnoreCase(field.getDirection())
                        && !"below".equalsIgnoreCase(field.getDirection())) {
                    errors.add(name + ".direction 只支持 right/below");
                }
                if (field.getMinConfidence() < 0 || field.getMinConfidence() > 1) {
                    errors.add(name + ".minConfidence 必须在 0 到 1 之间");
                }
                if (StringUtils.hasText(field.getPattern())) {
                    try {
                        Pattern.compile(field.getPattern());
                    } catch (PatternSyntaxException ex) {
                        errors.add(name + ".pattern 不是有效正则表达式");
                    }
                }
            });
        }
        return new ValidationResponse(errors.isEmpty(), List.copyOf(errors));
    }

    @SuppressWarnings("unchecked")
    private TemplateRevisionResponse mapRow(ResultSet rs, int rowNum) throws SQLException {
        try {
            Map<String, Object> definition = objectMapper.readValue(rs.getString(5), Map.class);
            return new TemplateRevisionResponse(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    rs.getInt(3),
                    rs.getString(4),
                    java.util.Collections.unmodifiableMap(new LinkedHashMap<>(definition)),
                    rs.getString(6),
                    rs.getObject(7, OffsetDateTime.class),
                    rs.getObject(8, OffsetDateTime.class));
        } catch (Exception ex) {
            throw new SQLException("OCR 模板定义读取失败。", ex);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new OcrException(
                    HttpStatus.BAD_REQUEST, "TEMPLATE_INVALID", "OCR 模板定义无法序列化。", ex);
        }
    }

    private void validateCode(String code) {
        if (!StringUtils.hasText(code) || !code.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new OcrException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_TEMPLATE_CODE",
                    "模板编码只允许字母、数字、下划线和短横线，且不能超过 64 个字符。");
        }
    }

    private String caller(String value) {
        return StringUtils.hasText(value) ? value.trim() : "gateway-unknown";
    }
    /**
     * 获取模板编码对应的事务级 advisory lock。
     *
     * <p>锁在当前事务提交或回滚时自动释放，不需要手工解锁。</p>
     */
    private void lock(String resource) {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                (ResultSetExtractor<Void>) resultSet -> null,
                resource);
    }


    private OcrException notFound(String message) {
        return new OcrException(HttpStatus.NOT_FOUND, "TEMPLATE_REVISION_NOT_FOUND", message);
    }
}
