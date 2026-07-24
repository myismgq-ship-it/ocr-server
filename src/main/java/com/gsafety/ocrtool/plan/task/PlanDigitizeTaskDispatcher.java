package com.gsafety.ocrtool.plan.task;

import com.gsafety.ocrtool.common.ProcessingMetrics;
import com.gsafety.ocrtool.config.PlanProperties;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 多实例安全的异步任务调度器。
 *
 * <p>数据库负责跨实例互斥领取，本地信号量负责限制单实例并行度，activeClaims 只保存本实例当前持有的租约。</p>
 */
@Component
public class PlanDigitizeTaskDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PlanDigitizeTaskDispatcher.class);

    private final PlanDigitizeTaskRepository repository;
    private final PlanDigitizeTaskWorker worker;
    private final PlanTaskStorageService storageService;
    private final ExecutorService executor;
    private final PlanProperties properties;
    /** 单实例最大在途任务数。 */
    private final Semaphore permits;
    /** 当前进程唯一执行者标识，用于数据库租约归属。 */
    private final String workerId;
    /** 本实例正在执行的 taskId 到 claimToken 映射，供心跳线程精确续租。 */
    private final Map<UUID, UUID> activeClaims = new ConcurrentHashMap<>();

    public PlanDigitizeTaskDispatcher(
            PlanDigitizeTaskRepository repository,
            PlanDigitizeTaskWorker worker,
            PlanTaskStorageService storageService,
            ExecutorService planDigitizeExecutor,
            PlanProperties properties) {
        this.repository = repository;
        this.worker = worker;
        this.storageService = storageService;
        this.executor = planDigitizeExecutor;
        this.properties = properties;
        this.permits = new Semaphore(Math.max(1, properties.getTask().getParallelism()));
        String host = System.getenv().getOrDefault("COMPUTERNAME", "ocr-server");
        this.workerId = host + "-" + UUID.randomUUID();
    }

    /**
     * 周期扫描队列，在本地仍有并行槽位时原子领取并提交任务。
     */
    @Scheduled(fixedDelayString = "${plan.task.scan-interval:1s}")
    public void dispatch() {
        // 先回收数据库中过期租约，进程异常退出留下的任务才能再次被领取。
        requeueStaleTasks();
        while (permits.tryAcquire()) {
            // 每次领取生成全新令牌；同一任务的不同 attempt 绝不能复用令牌。
            UUID claimToken = UUID.randomUUID();
            long claimStarted = System.nanoTime();
            PlanDigitizeTask task = repository
                    .claimNext(workerId, claimToken, OffsetDateTime.now()).orElse(null);
            ProcessingMetrics.record("database_wait", claimStarted);
            if (task == null) {
                permits.release();
                return;
            }
            // 只有本实例实际领取成功的任务才加入心跳集合。
            activeClaims.put(task.taskId(), claimToken);
            try {
                executor.execute(() -> {
                    try {
                        worker.execute(task, workerId);
                    } finally {
                        activeClaims.remove(task.taskId(), claimToken);
                        permits.release();
                    }
                });
                // 线程池拒绝时按原令牌放回队列，避免任务永久停留在 RUNNING。
            } catch (RejectedExecutionException ex) {
                activeClaims.remove(task.taskId(), claimToken);
                permits.release();
                repository.requeue(task.taskId(), workerId, claimToken);
                log.warn("预案数字化线程池拒绝任务，任务已重新排队，taskId={}", task.taskId(), ex);
                return;
            }
        }
    }

    /**
     * 为本实例仍持有的每个领取令牌刷新心跳。
     */
    @Scheduled(fixedDelayString = "${plan.task.heartbeat-interval:30s}")
    public void heartbeat() {
        OffsetDateTime now = OffsetDateTime.now();
        activeClaims.forEach((taskId, claimToken) ->
                repository.heartbeat(taskId, workerId, claimToken, now));
    }

    /**
     * 每日补偿清理成功/取消/过期失败文件、孤儿文件和过期结果。
     */
    @Scheduled(cron = "0 20 3 * * *")
    public void cleanupExpiredFailedFiles() {
        // 成功和取消任务不应保留源文件；异常退出遗留的文件在这里补偿删除。
        // 成功任务保留源文件一段时间，供人工复核后的回归重跑使用；到期后统一清理。
        OffsetDateTime completedCutoff = OffsetDateTime.now().minus(properties.getTask().getCompletedSourceRetention());
        for (PlanDigitizeTask task : repository.findExpiredCompletedFiles(completedCutoff)) {
            if (storageService.delete(task.sourcePath())) {
                repository.clearSourcePath(task.taskId());
            }
        }
        for (PlanDigitizeTask task : repository.findCancelledFilesPendingCleanup()) {
            if (storageService.delete(task.sourcePath())) {
                repository.clearSourcePath(task.taskId());
            }
        }
        OffsetDateTime cutoff = OffsetDateTime.now().minus(properties.getTask().getFailedFileRetention());
        for (PlanDigitizeTask task : repository.findExpiredFailedFiles(cutoff)) {
            if (storageService.delete(task.sourcePath())) {
        // 孤儿文件是“文件已落盘但任务事务未成功提交”等崩溃窗口留下的产物。
                repository.clearSourcePath(task.taskId());
            }
        }
        OffsetDateTime orphanCutoff = OffsetDateTime.now().minus(properties.getTask().getOrphanFileRetention());
        storageService.cleanupOrphans(repository.findAllSourcePaths(), orphanCutoff);
        int cleared = repository.clearExpiredResults(
                OffsetDateTime.now().minus(properties.getTask().getResultRetention()));
        if (cleared > 0) {
            log.info("已清理过期预案数字化结果，count={}", cleared);
        }
    }

    private void requeueStaleTasks() {
        OffsetDateTime cutoff = OffsetDateTime.now().minus(properties.getTask().getLease());
        int count = repository.requeueStale(cutoff);
        if (count > 0) {
            log.warn("已重新排队失去心跳的预案数字化任务，count={}", count);
        }
    }
}
