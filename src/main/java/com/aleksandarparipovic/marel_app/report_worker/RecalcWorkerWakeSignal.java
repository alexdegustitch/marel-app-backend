package com.aleksandarparipovic.marel_app.report_worker;

import org.springframework.stereotype.Component;

@Component
public class RecalcWorkerWakeSignal {

    private final Object monitor = new Object();

    public void await(long timeoutMs) {
        long boundedTimeout = Math.max(1L, timeoutMs);
        synchronized (monitor) {
            try {
                monitor.wait(boundedTimeout);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void signalAll() {
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }
}

