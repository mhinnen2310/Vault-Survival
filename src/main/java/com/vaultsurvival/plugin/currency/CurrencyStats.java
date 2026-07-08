package com.vaultsurvival.plugin.currency;

/**
 * Economy statistics for admin monitoring.
 */
public class CurrencyStats {

    private final long totalCashInCirculation;
    private final long totalCashInVaults;
    private final long totalCashInEscrow;
    private final long totalCashInLockers;
    private final long totalCashInTreasuries;
    private final long totalCashDropped;
    private final long totalCashSpent;
    private final long totalCashInvalidated;
    private final int activeCashItemCount;

    public CurrencyStats(long totalCashInCirculation, long totalCashInVaults,
                         long totalCashInEscrow, long totalCashInLockers,
                         long totalCashInTreasuries, long totalCashDropped,
                         long totalCashSpent, long totalCashInvalidated,
                         int activeCashItemCount) {
        this.totalCashInCirculation = totalCashInCirculation;
        this.totalCashInVaults = totalCashInVaults;
        this.totalCashInEscrow = totalCashInEscrow;
        this.totalCashInLockers = totalCashInLockers;
        this.totalCashInTreasuries = totalCashInTreasuries;
        this.totalCashDropped = totalCashDropped;
        this.totalCashSpent = totalCashSpent;
        this.totalCashInvalidated = totalCashInvalidated;
        this.activeCashItemCount = activeCashItemCount;
    }

    public long getTotalCashInCirculation() { return totalCashInCirculation; }
    public long getTotalCashInVaults() { return totalCashInVaults; }
    public long getTotalCashInEscrow() { return totalCashInEscrow; }
    public long getTotalCashInLockers() { return totalCashInLockers; }
    public long getTotalCashInTreasuries() { return totalCashInTreasuries; }
    public long getTotalCashDropped() { return totalCashDropped; }
    public long getTotalCashSpent() { return totalCashSpent; }
    public long getTotalCashInvalidated() { return totalCashInvalidated; }
    public int getActiveCashItemCount() { return activeCashItemCount; }

    public long getTotalEverCreated() {
        return totalCashInCirculation + totalCashInVaults + totalCashInEscrow
             + totalCashInLockers + totalCashInTreasuries + totalCashDropped
             + totalCashSpent + totalCashInvalidated;
    }
}
