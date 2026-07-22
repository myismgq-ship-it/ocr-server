# OCR Server

独立 OCR HTTP 工具服务，基于 RapidOCR 提供图片文字识别和模板字段抽取能力。

## 功能

- 单张图片识别：返回完整文本、文本块坐标、平均置信度、图片尺寸、预处理步骤和告警信息。
- 批量图片识别：同步处理多张图片，单张失败不会影响其他图片。
- 模板字段抽取：按 `application.yml` 中的 `ocr.templates` 配置抽取结构化字段。
- 预案数字化：从 Word/PDF 提取指挥体系、四级预警、四级应急响应和行动组。
- 异步任务：按预案 ID 后台解析文档，支持状态查询、历史记录、失败重试和结果持久化。
- 图片预处理：使用 Bytedeco OpenCV 做灰度化、轻量去噪、对比度增强、小图放大和轻微倾斜矫正。
- 统一异常：返回错误码、HTTP 状态、中文错误信息和请求路径。

## 接口

- `POST /api/ocr/recognize`：上传单张图片，返回产品化识别结果。
- `POST /api/ocr/batch`：上传多张图片，返回逐张识别结果。
- `POST /api/ocr/image`：兼容接口，返回原始 OCR 文本块结果。
- `POST /api/ocr/extract?templateCode=safety_license`：按模板抽取结构化字段。
- `GET /api/ocr/templates`：查看已配置模板编码。
- `POST /api/plans/digitize`：按文档 URL 解析预案关键内容。
- `POST /api/plans/digitize/upload`：手动上传 Word/PDF 并解析预案关键内容。
- `POST /api/plans/{planId}/digitize/tasks`：按文档 URL 创建异步数字化任务。
- `POST /api/plans/{planId}/digitize/tasks/upload`：上传文档并创建异步数字化任务。
- `GET /api/plans/{planId}/digitize/tasks/{taskId}`：查询任务状态和完成结果。
- `GET /api/plans/{planId}/digitize/tasks/latest`：查询预案最新任务。
- `GET /api/plans/{planId}/digitize/tasks?page=0&size=20`：分页查询任务历史。
- `POST /api/plans/{planId}/digitize/tasks/{taskId}/retry`：重试失败任务。
- `GET /actuator/health`：健康检查。

预案解析结果中的节点使用稳定 ASCII key。`warningResponses` 和 `emergencyResponses` 固定返回四级，状态为 `EXTRACTED`、`PARTIAL` 或 `MISSING`；旧 `responseLevels` 继续只返回已识别的应急响应节点。

异步任务状态为 `QUEUED`、`RUNNING`、`COMPLETED`、`FAILED`，同时返回中文 `statusName`。同一预案只保留一个活动任务，重复提交会返回当前任务并将 `reused` 置为 `true`。前端可每 2 至 3 秒轮询任务详情，页面重新进入后通过 `latest` 接口恢复状态。

多实例部署时，`plan.task.storage-directory` 应配置为所有实例可访问的共享目录；数据库的 `FOR UPDATE SKIP LOCKED` 保证任务只被一个实例领取。

## 包结构

- `web`：HTTP 入口，只放 Controller 和全局异常处理。
- `request`：接口请求 DTO。
- `response`：接口响应 DTO。
- `recognition`：通用 OCR 识别服务和识别结果模型。
- `extraction`：模板字段抽取服务和抽取结果模型。
- `plan`：预案数字化编排服务。
- `document`：文档下载、类型识别、Word/PDF 解析。
- `segment`：预案章节识别和关键块抽取。
- `engine`：OCR 引擎抽象和 RapidOCR 适配。
- `preprocess`：图片校验和 Bytedeco OpenCV 预处理。
- `config`：OCR 配置属性。
- `common`：通用异常等基础类型。

## 请求示例

```bash
curl -F "file=@license.jpeg" "http://localhost:38084/api/ocr/recognize"
```

```bash
curl -F "files=@a.jpeg" -F "files=@b.png" "http://localhost:38084/api/ocr/batch"
```

```bash
curl -F "file=@license.jpeg" "http://localhost:38084/api/ocr/extract?templateCode=safety_license"
```

```bash
curl -H "Content-Type: application/json" \
  -d "{\"documentUrl\":\"https://example.com/files/plan.pdf\"}" \
  "http://localhost:38084/api/plans/digitize"
```

```bash
curl -F "file=@plan.pdf" "http://localhost:38084/api/plans/digitize/upload"
```

## 配置

配置文件在 `src/main/resources/application.yml`。

```yaml
ocr:
  image:
    max-size: 10485760
  preprocess:
    enabled: true
    min-readable-side: 1200
    max-deskew-angle: 12
  rapid:
    enabled: true
    model: ONNX_PPOCR_V3

plan:
  document:
    max-size: 50MB
  pdf:
    max-pages: 50
    ocr-dpi: 200
    text-min-chars: 80
    sideways-table-ocr-enabled: true
  segment-rules:
    cache-ttl: 1m
  task:
    storage-directory: ${java.io.tmpdir}/ocr-plan-tasks
    parallelism: 2
    scan-interval: 1s
    heartbeat-interval: 30s
    lease: 5m
    failed-file-retention: 7d
```

预案章节识别规则存储在 PostgreSQL 的 `plan_segment_rule` 表中。首次部署可手动执行：

```bash
psql -d ocr_tool -f src/main/resources/db/postgresql/schema.sql
psql -d ocr_tool -f src/main/resources/db/postgresql/data.sql
```

已有环境升级时先执行：

```bash
psql -d ocr_tool -f src/main/resources/db/postgresql/migration/upgrade_plan_digitize_v2.sql
psql -d ocr_tool -f src/main/resources/db/postgresql/data.sql
```

也可以在首次启动时设置 `SPRING_SQL_INIT_MODE=always`，由 Spring 执行同一组脚本；脚本支持重复执行。数据库连接使用 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD`，规则默认缓存一分钟，数据库短暂不可用时会继续使用上一版缓存。

新增证照类型时，在 `ocr.templates` 下增加模板即可。预案数字化支持 `http/https` 文档 URL 或手动上传文件，支持 `.docx`、`.doc`、`.pdf`。PDF 会先尝试直接抽取文本，扫描件会渲染页面后复用 OCR 识别。同步接口仍只在请求内返回结果；异步接口将任务和 JSONB 结果长期保存，成功源文件立即清理，失败源文件默认保留 7 天。
