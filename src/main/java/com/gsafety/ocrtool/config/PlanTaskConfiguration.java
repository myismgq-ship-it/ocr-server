package com.gsafety.ocrtool.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PlanTaskConfiguration {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService planDigitizeExecutor(PlanProperties properties) {
        int parallelism = Math.max(1, properties.getTask().getParallelism());
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "plan-digitize-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(
                parallelism,
                parallelism,
                0L,
                TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                factory,
                new ThreadPoolExecutor.AbortPolicy());
    }
}
