package com.aleksandarparipovic.marel_app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Async executor configuration for event-driven recalculation workers.
 * 
 * Sizing:
 * - Core threads: 2 (one for daily, one for monthly)
 * - Max threads: 5 (allows overflow for concurrent work log edits)
 * - Queue capacity: 100 (buffer for incoming events)
 */
@Configuration
public class AsyncExecutorConfig {

    @Bean(name = "taskExecutor")
    public TaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);           // Minimum live threads
        executor.setMaxPoolSize(5);            // Maximum threads allowed
        executor.setQueueCapacity(100);        // Queue size before rejection
        executor.setThreadNamePrefix("recalc-worker-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}

