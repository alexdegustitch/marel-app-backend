package com.aleksandarparipovic.marel_app.report_worker;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.recalc")
public class RecalcWorkerProperties {

    private int dailyThreads = 2;
    private int monthlyThreads = 1;

    private int dailyBatch = 5;
    private int monthlyBatch = 3;

    private int maxRetry = 5;
    private long baseBackoffMs = 1000;

    private int stuckRecoveryBatch = 20;
    private long stuckTimeoutSeconds = 300;

    private long dailyIdleMinMs = 200;
    private long dailyIdleMaxMs = 2000;
    private long monthlyIdleMinMs = 300;
    private long monthlyIdleMaxMs = 2500;

    private long loopTimeBudgetMs = 5000;

    private long websocketDebounceMs = 300;

    private long metricsLogIntervalMs = 60000;
}

