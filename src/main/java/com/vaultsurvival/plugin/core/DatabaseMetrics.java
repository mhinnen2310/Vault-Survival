package com.vaultsurvival.plugin.core;

/** Immutable aggregate metrics; safe to display from any thread. */
public record DatabaseMetrics(WriteQueueMetrics writes, int readQueueDepth, int readQueueCapacity,
                              long readsCompleted, long readsFailed, long averageReadMicros,
                              long longestReadMicros, boolean acceptingWork,
                              boolean criticalQueuePressure, String shutdownFlushStatus) { }
