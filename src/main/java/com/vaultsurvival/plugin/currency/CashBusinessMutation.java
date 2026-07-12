package com.vaultsurvival.plugin.currency;

import java.sql.Connection;

/** Service-specific rows committed in the same SQLite transaction as cash ledger changes. */
@FunctionalInterface public interface CashBusinessMutation { void apply(Connection connection,CashPaymentPlan plan)throws Exception; }
