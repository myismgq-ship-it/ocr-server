# OCR Server

独立 OCR HTTP 工具服务，基于 RapidOCR 提供图片文字识别和模板字段抽取能力。

## 功能

生产部署假定服务位于统一网关之后，由网关负责身份认证、租户限流和阻止绕过访问；服务会透传并记录 `X-Request-ID` 与 `X-Caller-ID`。

- 单张图片识别：返回完整文本、文本块坐标、平均置信度、图片尺寸、预处理步骤和告警信息。
- 批量图片识别：同步处理多张图片，单张失败不会影响其他图片。
- 模板字段抽取：按 `application.yml` 中的 `ocr.templates` 配置抽取结构化字段。
- 预案数字化：从 Word/PDF 提取指挥体系、四级预警、四级应急响应和行动组，并兼容 Word 生成的 MHTML `.doc` 网页归档。
- 异步任务：按预案 ID 后台解析文档，支持状态查询、阶段进度、历史记录、失败重试、取消和结果过期。
- 图片预处理：使用 Bytedeco OpenCV 做灰度化、轻量去噪、对比度增强、小图放大和轻微倾斜矫正。
- 混合 PDF：逐页判断文本质量，仅对扫描页执行预处理和 OCR，结果模式为 `HYBRID`。
- 统一异常：返回错误码、HTTP 状态、中文错误信息和请求路径。

## 接口

- `POST /api/ocr/recognize`：上传单张图片，返回产品化识别结果。
- `POST /api/ocr/batch`：上传多张图片，返回逐张识别结果。
- `POST /api/ocr/image`：兼容接口，返回原始 OCR 文本块结果。
- `POST /api/ocr/extract?templateCode=safety_license`：按模板抽取结构化字段。
- `GET /api/ocr/templates`：查看已配置模板编码。
- `GET /api/plans`：查询工作台预案目录；真实前端只使用该接口数据。
- `POST /api/plans`：登记新的预案目录和展示元数据。
- `PUT /api/plans/{planId}`：修改或补登记预案名称、编码、分类、主管部门和版本。
- `POST /api/plans/digitize`：按文档 URL 解析预案关键内容。
- `POST /api/plans/digitize/upload`：手动上传 Word/PDF 并解析预案关键内容。
- `POST /api/plans/{planId}/digitize/tasks`：按文档 URL 创建异步数字化任务。
- `POST /api/plans/{planId}/digitize/tasks/upload`：上传文档并创建异步数字化任务。
- `GET /api/plans/{planId}/digitize/tasks/{taskId}`：查询任务状态和完成结果。
- `GET /api/plans/{planId}/digitize/tasks/latest`：查询预案最新任务。
- `GET /api/plans/{planId}/digitize/tasks?page=0&size=20`：分页查询任务历史。
- `POST /api/plans/{planId}/digitize/tasks/{taskId}/retry`：重试失败任务。
- `POST /api/plans/{planId}/digitize/tasks/{taskId}/cancel`：取消排队中或执行中的任务。
- `GET /actuator/health`：健康检查。
- `GET /api/plans/{planId}/digitize/compare?fromTaskId=...&toTaskId=...`：比较两次数字化结果。
- `GET /api/plans/{planId}/digitize/compare/export?fromTaskId=...&toTaskId=...`：导出 Excel 差异表。
- `POST /api/plans/{planId}/digitize/tasks/{taskId}/reviews`：提交人工复核修订并保留审计记录。
- `GET /api/plans/{planId}/digitize/tasks/{taskId}/reviews`：查询人工复核历史。
- `POST /api/admin/ocr/templates/{templateCode}/drafts`：创建 OCR 模板草稿。
- `GET /api/admin/ocr/templates/{templateCode}/revisions`：查询模板版本历史。
- `POST /api/admin/ocr/templates/revisions/{revisionId}/{validate|publish|rollback|test}`：校验、测试、发布或回滚模板。
- `POST /api/admin/plan/rules/drafts` 与 `/revisions/{revisionId}/{validate|test|publish|rollback}`：管理规则版本，并可用上传文档隔离测试草稿。

预案解析结果中的节点使用稳定 ASCII key。`warningResponses` 和 `emergencyResponses` 固定返回四级，状态为 `EXTRACTED`、`PARTIAL` 或 `MISSING`；旧 `responseLevels` 继续只返回已识别的应急响应节点。

异步任务状态为 `QUEUED`、`RUNNING`、`COMPLETED`、`FAILED`、`CANCELLED`，同时返回中文 `statusName`。响应还包含 `stage`、`progressPercent`、`attempt`、`updatedAt` 和 `ruleVersion`。同一预案只保留一个活动任务，重复提交会返回当前任务并将 `reused` 置为 `true`。

多实例部署时，`plan.task.storage-directory` 应配置为所有实例可访问的共享目录。任务领取使用独立 `claimToken` 隔离每次尝试；心跳、完成和失败更新都必须同时匹配任务、执行者与领取令牌。

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
- `management`：模板/规则版本、人工复核和预案版本比较。
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
spring:
  servlet:
    multipart:
      max-file-size: 50MB
      max-request-size: 50MB

ocr:
  image:
    max-size: 10485760
    max-pixels: 40000000
  preprocess:
    enabled: true
    min-readable-side: 1200
    max-deskew-angle: 12
    multi-pass: true
    multi-pass-min-confidence: 0.75
    multi-pass-min-text-chars: 8
  rapid:
    enabled: true
    model: ONNX_PPOCR_V4
    max-concurrency: 1
  fallback:
    enabled: false
    min-confidence: 0.55

plan:
  document:
    max-size: 50MB
    allowed-hosts: [downloads.example.com]
    allowed-ports: [80, 443]
    max-redirects: 5
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
    heartbeat-interval: 30s
    lease: 5m
    failed-file-retention: 7d
    orphan-file-retention: 1h
    result-retention: 30d
```

数据库结构和初始规则由 Flyway 的 `db/migration/V1__ocr_server_baseline.sql` 管理。生产启动必须显式设置以下环境变量，应用不再内置任何数据库地址或账号：

- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`

旧环境仍可用 `src/main/resources/db/postgresql/migration/upgrade_plan_digitize_v2.sql` 做一次性升级；完成后应由 Flyway 接管后续版本。V2 迁移增加模板/规则修订和人工复核审计表，V4 新增预案目录表，V5 增加准确率样本表，V6 增加罗马数字响应别名，V7 增加多格式预案兼容规则。若历史库曾以 `roman_emergency_response_aliases` 作为 V5 执行，先运行 `db/postgresql/repair_v5_roman_history.sql`，再执行 Flyway repair 和后续迁移。规则默认缓存一分钟，每份文档只读取一次不可变规则快照，结果中的 `ruleVersion` 用于复现。

URL 下载默认拒绝回环、私网、链路本地、组播、CGNAT 地址和非法端口，并会对每次重定向重新验证。生产环境必须设置 `PLAN_DOCUMENT_ALLOWED_HOSTS` 为逗号分隔的业务域名白名单；不要把服务端口直接暴露到网关之外。

新增证照类型时，在 `ocr.templates` 下增加模板；许可证专用修正规则只在 `safety-license` 处理器中启用。字段状态区分 `MISSING`、`LOW_CONFIDENCE`、`INVALID` 和 `EXTRACTED`，并返回命中的标签、来源文本与坐标。PDF 逐页选择文本抽取或 OCR，成功源文件立即清理，失败文件默认保留 7 天，结构化结果默认保留 30 天。
