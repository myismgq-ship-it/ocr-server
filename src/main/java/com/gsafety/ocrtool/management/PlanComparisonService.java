package com.gsafety.ocrtool.management;

import com.gsafety.ocrtool.common.OcrException;
import com.gsafety.ocrtool.management.PlanComparisonResponse.PlanChange;
import com.gsafety.ocrtool.plan.task.PlanDigitizeTask;
import com.gsafety.ocrtool.plan.task.PlanDigitizeTaskRepository;
import com.gsafety.ocrtool.plan.task.PlanDigitizeTaskStatus;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * 比较两次预案数字化结果并导出差异。
 *
 * <p>Map 递归比较；包含稳定 key 的列表按 key 对齐，避免仅因列表顺序变化产生误报。</p>
 */
@Service
public class PlanComparisonService {

    /** Excel 单元格允许的最大文本长度，超出时安全截断。 */
    private static final int EXCEL_CELL_TEXT_LIMIT = 32_767;

    private final PlanDigitizeTaskRepository taskRepository;
    private final ObjectMapper objectMapper;

    public PlanComparisonService(
            PlanDigitizeTaskRepository taskRepository,
            ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 比较同一预案下两次已完成任务的结构化结果。
     *
     * @param fromTaskId 基准版本任务
     * @param toTaskId 目标版本任务
     */
    public PlanComparisonResponse compare(String planId, UUID fromTaskId, UUID toTaskId) {
        Map<String, Object> before = result(planId, fromTaskId);
        Map<String, Object> after = result(planId, toTaskId);
        List<PlanChange> changes = new ArrayList<>();
        compareValue("", before, after, changes);
        return new PlanComparisonResponse(
                planId, fromTaskId, toTaskId, List.copyOf(changes));
    }

    /**
     * 将差异导出为内存中的 XLSX 文件。
     *
     * @return 可直接作为 HTTP 响应体的工作簿字节
     */
    public byte[] exportExcel(PlanComparisonResponse comparison) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("预案版本差异");
            Font bold = workbook.createFont();
            bold.setBold(true);
            CellStyle header = workbook.createCellStyle();
            header.setFont(bold);
            Row title = sheet.createRow(0);
            String[] names = {"字段路径", "变化类型", "旧版本", "新版本"};
            for (int i = 0; i < names.length; i++) {
                title.createCell(i).setCellValue(names[i]);
                title.getCell(i).setCellStyle(header);
            }
            int rowIndex = 1;
            for (PlanChange change : comparison.changes()) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(change.path());
                row.createCell(1).setCellValue(change.changeType());
                row.createCell(2).setCellValue(display(change.beforeValue()));
                row.createCell(3).setCellValue(display(change.afterValue()));
            }
            sheet.setColumnWidth(0, 45 * 256);
            sheet.setColumnWidth(1, 14 * 256);
            sheet.setColumnWidth(2, 60 * 256);
            sheet.setColumnWidth(3, 60 * 256);
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception ex) {
            throw new OcrException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "PLAN_COMPARE_EXPORT_FAILED",
                    "预案版本差异 Excel 导出失败。",
                    ex);
        }
    }

    @SuppressWarnings("unchecked")
    /**
     * 读取可比较任务结果，并拒绝失败、过期或结果损坏的任务。
     */
    private Map<String, Object> result(String planId, UUID taskId) {
        PlanDigitizeTask task = taskRepository.findByPlanAndTaskId(planId, taskId)
                .orElseThrow(() -> new OcrException(
                        HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "预案数字化任务不存在。"));
        if (task.status() != PlanDigitizeTaskStatus.COMPLETED
                || !StringUtils.hasText(task.resultJson())) {
            throw new OcrException(
                    HttpStatus.CONFLICT,
                    "TASK_RESULT_NOT_COMPARABLE",
                    "只有结果尚未过期的已完成任务可以比较。");
        }
        try {
            return objectMapper.readValue(task.resultJson(), Map.class);
        } catch (Exception ex) {
            throw new OcrException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "TASK_RESULT_INVALID",
                    "预案数字化结果无法读取。",
                    ex);
        }
    }

    @SuppressWarnings("unchecked")
    /**
     * 深度比较任意 JSON 值并追加叶子差异。
     *
     * <p>null 到有值记为 ADDED，有值到 null 记为 REMOVED，其余变化记为 MODIFIED。</p>
     */
    private void compareValue(
            String path,
            Object before,
            Object after,
            List<PlanChange> changes) {
        if (Objects.equals(before, after)) {
            return;
        }
        if (before instanceof Map<?, ?> beforeMap && after instanceof Map<?, ?> afterMap) {
            Set<String> keys = new LinkedHashSet<>();
            beforeMap.keySet().forEach(key -> keys.add(String.valueOf(key)));
            afterMap.keySet().forEach(key -> keys.add(String.valueOf(key)));
            for (String key : keys.stream().sorted().toList()) {
                compareValue(
                        childPath(path, key),
                        beforeMap.get(key),
                        afterMap.get(key),
                        changes);
            }
            return;
        }
        if (before instanceof List<?> beforeList && after instanceof List<?> afterList) {
            Map<String, Object> keyedBefore = keyed(beforeList);
            Map<String, Object> keyedAfter = keyed(afterList);
            if (keyedBefore != null && keyedAfter != null) {
                compareValue(path, keyedBefore, keyedAfter, changes);
                return;
            }
        }
        String type = before == null ? "ADDED" : after == null ? "REMOVED" : "MODIFIED";
        changes.add(new PlanChange(path.isEmpty() ? "$" : path, type, before, after));
    }

    /**
     * 将元素均含唯一 key 的列表转换为 Map，便于按业务身份比较而非按下标比较。
     */
    private Map<String, Object> keyed(List<?> values) {
        Map<String, Object> keyed = new LinkedHashMap<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> map)) {
                return null;
            }
            Object key = map.get("key");
            if (key == null || keyed.put(String.valueOf(key), value) != null) {
                return null;
            }
        }
        return keyed;
    }

    private String childPath(String parent, String child) {
        return parent.isEmpty() ? child : parent + "." + child;
    }

    private String display(Object value) {
        if (value == null) {
            return "";
        }
        String text;
        if (value instanceof String string) {
            text = string;
        } else {
            try {
                text = objectMapper.writeValueAsString(value);
            } catch (Exception ex) {
                text = String.valueOf(value);
            }
        }
        return text.length() <= EXCEL_CELL_TEXT_LIMIT
                ? text
                : text.substring(0, EXCEL_CELL_TEXT_LIMIT - 3) + "...";
    }
}
