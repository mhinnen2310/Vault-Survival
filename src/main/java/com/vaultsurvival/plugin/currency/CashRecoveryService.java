package com.vaultsurvival.plugin.currency;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Deterministically moves committed-but-undelivered change into a recovery locker. */
public final class CashRecoveryService implements Listener {
    public record RecoveryCash(UUID transactionUuid,UUID cashUuid,long amount){}
    private final VaultSurvivalPlugin plugin; private final CurrencyService currency;
    public CashRecoveryService(VaultSurvivalPlugin plugin,CurrencyService currency){this.plugin=plugin;this.currency=currency;}

    public CompletableFuture<Integer> recoverStartupTransactions(){return plugin.getDatabase().write(connection->{
        try(PreparedStatement deliveries=connection.prepareStatement("INSERT OR IGNORE INTO cash_recovery_deliveries(transaction_uuid,cash_uuid,player_uuid,amount,state,created_at,updated_at) SELECT j.transaction_uuid,c.cash_uuid,j.player_uuid,c.amount,'PENDING',?,? FROM cash_items c JOIN cash_transaction_journal j ON j.transaction_uuid=c.location_id WHERE c.location_type='TRANSACTION' AND j.state IN ('LEDGER_COMMITTED','RECOVERY_REQUIRED')")){long now=System.currentTimeMillis();deliveries.setLong(1,now);deliveries.setLong(2,now);deliveries.executeUpdate();}
        int changed;
        try(PreparedStatement cash=connection.prepareStatement("UPDATE cash_items SET location_type='RECOVERY_LOCKER',location_id=(SELECT player_uuid FROM cash_transaction_journal j WHERE j.transaction_uuid=cash_items.location_id),last_seen_at=datetime('now') WHERE location_type='TRANSACTION' AND location_id IN (SELECT transaction_uuid FROM cash_transaction_journal WHERE state IN ('LEDGER_COMMITTED','RECOVERY_REQUIRED'))")){changed=cash.executeUpdate();}
        try(PreparedStatement journal=connection.prepareStatement("UPDATE cash_transaction_journal SET state='RECOVERY_REQUIRED',failure_reason=COALESCE(failure_reason,'Recovered after restart before inventory completion'),updated_at=?,recovery_attempts=recovery_attempts+1 WHERE state='LEDGER_COMMITTED'")){journal.setLong(1,System.currentTimeMillis());journal.executeUpdate();}
        return changed;
    });}

    @EventHandler public void onJoin(PlayerJoinEvent event){UUID player=event.getPlayer().getUniqueId();load(player).whenComplete((cash,failure)->Bukkit.getScheduler().runTask(plugin,()->{Player online=Bukkit.getPlayer(player);if(online==null)return;if(failure!=null){plugin.getLogger().warning("Cash recovery lookup failed for "+player+": "+failure.getMessage());return;}deliver(online,cash);}));}
    public void deliverPending(Player player){UUID id=player.getUniqueId();load(id).whenComplete((cash,failure)->Bukkit.getScheduler().runTask(plugin,()->{Player online=Bukkit.getPlayer(id);if(online==null)return;if(failure!=null){plugin.getLogger().warning("Cash recovery lookup failed for "+id+": "+failure.getMessage());return;}deliver(online,cash);}));}

    public CompletableFuture<List<RecoveryCash>> load(UUID player){return plugin.getDatabase().read(connection->{try(PreparedStatement ps=connection.prepareStatement("SELECT transaction_uuid,cash_uuid,amount FROM cash_recovery_deliveries WHERE player_uuid=? AND state='PENDING' ORDER BY created_at,id")){ps.setString(1,player.toString());try(var rs=ps.executeQuery()){var result=new java.util.ArrayList<RecoveryCash>();while(rs.next())result.add(new RecoveryCash(UUID.fromString(rs.getString(1)),UUID.fromString(rs.getString(2)),rs.getLong(3)));return List.copyOf(result);}}});}

    private void deliver(Player player,List<RecoveryCash> recovery){for(RecoveryCash cash:recovery){int slot=player.getInventory().firstEmpty();if(slot<0){player.sendMessage(plugin.getMessageFormatter().warn("Recovered cash is safe in your recovery locker; free an inventory slot and reconnect."));return;}player.getInventory().setItem(slot,currency.materializePlannedCash(cash.cashUuid(),cash.amount()));complete(player.getUniqueId(),cash).exceptionally(failure->{plugin.getLogger().warning("Cash recovery completion failed: "+failure.getMessage());return null;});player.sendMessage(plugin.getMessageFormatter().success("Recovered "+cash.amount()+" physical cash from an interrupted transaction."));}}

    private CompletableFuture<Void> complete(UUID player,RecoveryCash cash){return plugin.getDatabase().write(connection->{try(PreparedStatement item=connection.prepareStatement("UPDATE cash_items SET location_type='INVENTORY',location_id=?,current_holder=?,owner_uuid=?,last_seen_at=datetime('now') WHERE cash_uuid=? AND location_type='RECOVERY_LOCKER'")){item.setString(1,player.toString());item.setString(2,player.toString());item.setString(3,player.toString());item.setString(4,cash.cashUuid().toString());if(item.executeUpdate()!=1)throw new IllegalStateException("Recovery cash was already moved");}try(PreparedStatement delivery=connection.prepareStatement("UPDATE cash_recovery_deliveries SET state='DELIVERED',updated_at=? WHERE transaction_uuid=? AND cash_uuid=? AND state='PENDING'")){delivery.setLong(1,System.currentTimeMillis());delivery.setString(2,cash.transactionUuid().toString());delivery.setString(3,cash.cashUuid().toString());if(delivery.executeUpdate()!=1)throw new IllegalStateException("Recovery delivery was already completed");}try(PreparedStatement journal=connection.prepareStatement("UPDATE cash_transaction_journal SET state='COMPLETED',failure_reason=NULL,updated_at=? WHERE transaction_uuid=? AND state='RECOVERY_REQUIRED' AND NOT EXISTS(SELECT 1 FROM cash_recovery_deliveries WHERE transaction_uuid=? AND state='PENDING')")){journal.setLong(1,System.currentTimeMillis());journal.setString(2,cash.transactionUuid().toString());journal.setString(3,cash.transactionUuid().toString());journal.executeUpdate();}return null;});}
}
