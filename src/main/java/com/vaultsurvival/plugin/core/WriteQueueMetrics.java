package com.vaultsurvival.plugin.core;

/** Immutable snapshot of the serialized SQLite write queue. */
public record WriteQueueMetrics(int depth, int capacity, long submitted, long completed,
                                long failed, long rejected, long averageQueryMicros,
                                long longestQueryMicros) { }
