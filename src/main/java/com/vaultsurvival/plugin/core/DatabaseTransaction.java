package com.vaultsurvival.plugin.core;

import java.sql.Connection;

/** One explicit database unit of work. Implementations must not access Bukkit state. */
@FunctionalInterface
public interface DatabaseTransaction<T> {
    T execute(Connection connection) throws Exception;
}
