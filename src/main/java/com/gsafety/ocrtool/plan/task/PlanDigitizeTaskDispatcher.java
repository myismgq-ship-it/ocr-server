package com.gsafety.ocrtool.plan.task;

import com.gsafety.ocrtool.config.PlanProperties;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PlanDigitizeTaskDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PlanDigitizeTaskDispatcher.class);

    private final PlanDigitizeTaskRepository repository;
    private final PlanDigitizeTaskWorker worker;
    private final PlanTaskStorageService storageService;
    private final ExecutorService executor;
    private final PlanProperties properties;
    private final Semaphore permits;
    private final String workerId;

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
        String host = System.getenv().getOrDefault("COMPUTERNAME", "ocr-tool-service");
        this.workerId = host + "-" + UUID.randomUUID();
    }

    @Scheduled(fixedDelayString = "${plan.task.scan-interval:1s}")
    public void dispatch() {
        requeueStaleTasks();
        while (permits.tryAcquire()) {
            PlanDigitizeTask task = repository.claimNext(workerId, OffsetDateTime.now()).orElse(null);
            if (task == null) {
                permits.release();
                return;
            }
            try {
                executor.execute(() -> {
                    try {
                        worker.execute(task, workerId);
                    } finally {
                        permits.release();
                    }
                });
            } catch (RejectedExecutionException ex) {
                permits.release();
                repository.requeue(task.taskId(), workerId);
                log.warn("预案数字化线程池拒绝任务，任务已重新排队，taskId={}", task.taskId(), ex);
                return;
            }
        }
    }

    @Scheduled(fixedDelayString = "${plan.task.heartbeat-interval:30s}")
    public void heartbeat() {
        repository.heartbeat(workerId, OffsetDateTime.now());
    }

    @Scheduled(cron = "0 20 3 * * *")
    public void cleanupExpiredFailedFiles() {
        for (PlanDigitizeTask task : repository.findCompletedFilesPendingCleanup()) {
            if (storageService.delete(task.sourcePath())) {
                repository.clearSourcePath(task.taskId());
            }
        }
        OffsetDateTime cutoff = OffsetDateTime.now().minus(properties.getTask().getFailedFileRetention());
        for (PlanDigitizeTask task : repository.findExpiredFailedFiles(cutoff)) {
            if (storageService.delete(task.sourcePath())) {
                repository.clearSourcePath(task.taskId());
            }
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
