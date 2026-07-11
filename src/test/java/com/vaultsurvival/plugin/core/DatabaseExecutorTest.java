package com.vaultsurvival.plugin.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseExecutorTest {
    @TempDir Path directory;
    private DatabaseExecutor executor;

    private DatabaseExecutor create(int capacity) throws Exception {
        Class.forName("org.sqlite.JDBC");
        String url="jdbc:sqlite:"+directory.resolve("test.db");
        try(var connection= DriverManager.getConnection(url);var statement=connection.createStatement()){
            statement.execute("PRAGMA journal_mode=WAL");statement.execute("CREATE TABLE values_table(id INTEGER PRIMARY KEY AUTOINCREMENT,value INTEGER NOT NULL)");
        }
        return executor=new DatabaseExecutor(url, Logger.getLogger("DatabaseExecutorTest"),1000,capacity,2,32);
    }

    @AfterEach void close(){if(executor!=null)executor.shutdown(Duration.ofSeconds(5));}

    @Test void serializesConcurrentWritesAndCommitsEveryValue() throws Exception {
        DatabaseExecutor database=create(256);AtomicInteger active=new AtomicInteger(),maximum=new AtomicInteger();List<CompletableFuture<Integer>> futures=new ArrayList<>();
        for(int value=0;value<200;value++){int captured=value;futures.add(database.write(connection->{int now=active.incrementAndGet();maximum.accumulateAndGet(now,Math::max);try(var statement=connection.prepareStatement("INSERT INTO values_table(value) VALUES(?)")){statement.setInt(1,captured);statement.executeUpdate();}finally{active.decrementAndGet();}return captured;}));}
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(10,TimeUnit.SECONDS);
        assertEquals(1,maximum.get());assertEquals(200,database.read(connection->{try(var rows=connection.createStatement().executeQuery("SELECT COUNT(*) FROM values_table")){return rows.next()?rows.getInt(1):-1;}}).get());
        assertEquals(200,database.metrics().writes().completed());
    }

    @Test void rollsBackFailureWithoutLeakingTransactionState() throws Exception {
        DatabaseExecutor database=create(32);
        assertThrows(Exception.class,()->database.write(connection->{connection.createStatement().executeUpdate("INSERT INTO values_table(value) VALUES(1)");throw new IllegalStateException("rollback");}).get());
        database.write(connection->{connection.createStatement().executeUpdate("INSERT INTO values_table(value) VALUES(2)");return null;}).get();
        List<Integer> values=database.read(connection->{List<Integer> rows=new ArrayList<>();try(var result=connection.createStatement().executeQuery("SELECT value FROM values_table")){while(result.next())rows.add(result.getInt(1));}return rows;}).get();
        assertEquals(List.of(2),values);
    }

    @Test void connectionsAreRealAndCloseNormally() throws Exception {
        DatabaseExecutor database=create(32);var connection=database.openConnection(false);assertFalse(connection.isClosed());connection.close();assertTrue(connection.isClosed());
    }

    @Test void boundedQueueRejectsInsteadOfGrowingWithoutLimit() throws Exception {
        DatabaseExecutor database=create(16);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        CompletableFuture<Void> blocker=database.write(connection->{entered.countDown();release.await(5,TimeUnit.SECONDS);return null;});assertTrue(entered.await(2,TimeUnit.SECONDS));
        List<CompletableFuture<Void>> queued=new ArrayList<>();for(int i=0;i<16;i++)queued.add(database.write(connection->null));
        CompletableFuture<Void> rejected=database.write(connection->null);assertThrows(Exception.class,rejected::get);assertEquals(16,database.metrics().writes().depth());assertEquals(1,database.metrics().writes().rejected());
        release.countDown();blocker.get();CompletableFuture.allOf(queued.toArray(CompletableFuture[]::new)).get();
    }

    @Test void shutdownDrainsCommittedWork() throws Exception {
        DatabaseExecutor database=create(64);for(int i=0;i<40;i++)database.write(connection->{connection.createStatement().executeUpdate("INSERT INTO values_table(value) VALUES(7)");return null;});
        assertTrue(database.shutdown(Duration.ofSeconds(5)));executor=null;
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("test.db"));var rows=connection.createStatement().executeQuery("SELECT COUNT(*) FROM values_table")){assertTrue(rows.next());assertEquals(40,rows.getInt(1));}
    }
}
