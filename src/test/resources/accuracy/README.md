# 预案准确率样本

仓库只保存样本清单、文件哈希和期望级别，不提交真实预案文档。

## 本地执行

将清单中的 Word 文件放在同一目录，然后在 PowerShell 中执行：

```powershell
$env:PLAN_ACCURACY_SAMPLE_DIR='C:\Users\10634\Downloads'
mvn -q "-Dtest=PlanAccuracySampleTest" test
```

未设置 `PLAN_ACCURACY_SAMPLE_DIR` 时，该测试自动跳过，不影响普通单元测试和 CI。

评测按每份预案实际采用的响应级别进行，不固定要求四级。例如河西区地震预案和天津市粮食预案采用三级响应；唐山市防汛预案以四级预警响应为主要结构。

`天津市森林火灾应急预案 (1).docx` 与无后缀副本的 SHA-256 完全一致，因此清单只保留一份，避免重复计分。
