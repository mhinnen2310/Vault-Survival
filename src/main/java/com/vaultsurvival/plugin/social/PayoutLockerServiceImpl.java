package com.vaultsurvival.plugin.social;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import com.vaultsurvival.plugin.currency.CurrencyService;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

public class PayoutLockerServiceImpl implements PayoutLockerService {
    private final VaultSurvivalPlugin plugin;
    private final CurrencyService currency;
    private final ContractAuditService audit;
    private final MessageFormatter fmt;
    private final ConcurrentHashMap<Integer,ContractData.PayoutLockerEntry> cache=new ConcurrentHashMap<>();

    public PayoutLockerServiceImpl(VaultSurvivalPlugin plugin, ContractAuditService audit) {
        this.plugin = plugin;
        this.currency = plugin.getServiceRegistry().get(CurrencyService.class);
        this.audit = audit;
        this.fmt = plugin.getMessageFormatter();
        refreshCache();
    }

    @Override
    public CompletableFuture<Integer> storePayout(UUID playerUuid, long amount, String sourceType, String sourceId, String details) {
        return storePayout(playerUuid,amount,sourceType,sourceId,details,(connection,plan)->{});
    }
    @Override public CompletableFuture<Integer> storePayout(UUID playerUuid,long amount,String sourceType,String sourceId,String details,com.vaultsurvival.plugin.currency.CashBusinessMutation mutation){
        if (amount <= 0) return CompletableFuture.failedFuture(new IllegalArgumentException("Payout must be positive"));
        long now=System.currentTimeMillis();return plugin.getDatabase().write(conn->{int id;try(PreparedStatement ps=conn.prepareStatement("INSERT INTO payout_lockers (player_uuid,amount,source_type,source_id,details,status,created_at) VALUES (?,?,?,?,?,'PENDING',?)",Statement.RETURN_GENERATED_KEYS)){ps.setString(1,playerUuid.toString());ps.setLong(2,amount);ps.setString(3,sourceType);ps.setString(4,sourceId);ps.setString(5,details);ps.setLong(6,now);ps.executeUpdate();try(ResultSet keys=ps.getGeneratedKeys()){if(!keys.next())throw new SQLException("No payout id generated");id=keys.getInt(1);}}mutation.apply(conn,new com.vaultsurvival.plugin.currency.CashPaymentPlan(UUID.randomUUID(),"payout-locker:"+id,playerUuid,List.of(),amount,0,"PAYOUT_LOCKER",sourceId,List.of("PAYOUT_LOCKER_STORE")));return id;}).thenApply(id->{cache.put(id,new ContractData.PayoutLockerEntry(id,playerUuid,amount,sourceType,sourceId,details,ContractData.PayoutStatus.PENDING));audit.log(parseContractId(sourceId),playerUuid,"PAYOUT_LOCKER_STORE",amount,details);scorePayout(id,playerUuid,amount,sourceType,sourceId);return id;});
    }

    @Override
    public List<ContractData.PayoutLockerEntry> getPending(UUID playerUuid) {
        return cache.values().stream().filter(entry->entry.getPlayerUuid().equals(playerUuid)&&entry.getStatus()==ContractData.PayoutStatus.PENDING).sorted(java.util.Comparator.comparingInt(ContractData.PayoutLockerEntry::getId).reversed()).toList();
    }

    @Override
    public List<ContractData.PayoutLockerEntry> getAllPending() {
        return cache.values().stream().filter(entry->entry.getStatus()==ContractData.PayoutStatus.PENDING).toList();
    }

    @Override
    public long getPendingTotal(UUID playerUuid) {
        return getPending(playerUuid).stream().mapToLong(ContractData.PayoutLockerEntry::getAmount).sum();
    }

    @Override
    public boolean claim(Player player) {
        return claimFiltered(player, false);
    }

    @Override
    public boolean claimMerchantShop(Player player) {
        return claimFiltered(player, true);
    }

    private boolean claimFiltered(Player player, boolean merchantOnly) {
        List<ContractData.PayoutLockerEntry> pending = getPending(player.getUniqueId()).stream()
            .filter(entry -> merchantOnly == "MERCHANT_SHOP".equalsIgnoreCase(entry.getSourceType()))
            .toList();
        if (pending.isEmpty()) {
            player.sendMessage(fmt.info(merchantOnly
                ? "No pending shop earnings at this NPC."
                : "No non-shop payouts. Shop earnings must be collected at your shop NPC."));
            return true;
        }
        long total = pending.stream().mapToLong(ContractData.PayoutLockerEntry::getAmount).sum();
        String ids=pending.stream().map(entry->String.valueOf(entry.getId())).collect(java.util.stream.Collectors.joining(","));
        var delivery=plugin.getServiceRegistry().get(com.vaultsurvival.plugin.currency.PayoutDeliveryService.class);
        delivery.deliver(player,total,null,merchantOnly?"MERCHANT_NPC_PAYOUT":"PAYOUT_LOCKER","payout:"+player.getUniqueId()+":"+ids,(connection,plan)->{try(PreparedStatement update=connection.prepareStatement("UPDATE payout_lockers SET status='CLAIMED',claimed_at=? WHERE id=? AND status='PENDING'")){for(ContractData.PayoutLockerEntry entry:pending){update.setLong(1,System.currentTimeMillis());update.setInt(2,entry.getId());if(update.executeUpdate()!=1)throw new SQLException("Payout changed concurrently");}}}).whenComplete((result,failure)->org.bukkit.Bukkit.getScheduler().runTask(plugin,()->{if(failure!=null){player.sendMessage(fmt.error("Claim failed; payout remains safe: "+rootMessage(failure)));return;}pending.forEach(entry->cache.remove(entry.getId()));audit.log(0,player.getUniqueId(),"PAYOUT_LOCKER_CLAIM",total,"entries="+pending.size()+" transaction="+result.transactionUuid());player.sendMessage(fmt.success("Payout secured: &6"+fmt.formatMoney(total,plugin.getConfigManager().getCurrencyName(),plugin.getConfigManager().getCurrencyNamePlural())+"&a. Full inventories use the recovery locker."));}));return true;
    }

    private static String rootMessage(Throwable failure){Throwable current=failure;while(current.getCause()!=null)current=current.getCause();return current.getMessage()==null?current.getClass().getSimpleName():current.getMessage();}

    private void refreshCache(){plugin.getDatabase().read(conn->{List<ContractData.PayoutLockerEntry> rows=new ArrayList<>();try(PreparedStatement ps=conn.prepareStatement("SELECT * FROM payout_lockers WHERE status='PENDING' ORDER BY created_at DESC");ResultSet rs=ps.executeQuery()){while(rs.next())rows.add(new ContractData.PayoutLockerEntry(rs.getInt("id"),UUID.fromString(rs.getString("player_uuid")),rs.getLong("amount"),rs.getString("source_type"),rs.getString("source_id"),rs.getString("details"),ContractData.PayoutStatus.valueOf(rs.getString("status"))));}return rows;}).thenAccept(rows->{cache.clear();rows.forEach(row->cache.put(row.getId(),row));}).exceptionally(failure->{plugin.getLogger().log(Level.WARNING,"Failed to load payout cache",failure);return null;});}

    private int parseContractId(String sourceId) {
        try { return Integer.parseInt(sourceId); } catch (Exception ignored) { return 0; }
    }

    private void scorePayout(int payoutId, UUID playerUuid, long amount, String sourceType, String sourceId) {
        long threshold = plugin.getConfigManager().getConfig().getLong("security.suspiciousPayout.minimumAmount", 25_000L);
        long window = plugin.getConfigManager().getConfig().getLong("security.suspiciousPayout.windowMillis", 600_000L);
        int burstCount = plugin.getConfigManager().getConfig().getInt("security.suspiciousPayout.burstCount", 5);
        long burstTotal = plugin.getConfigManager().getConfig().getLong("security.suspiciousPayout.burstTotal", 50_000L);
        plugin.getDatabase().read(conn->{try(PreparedStatement ps=conn.prepareStatement("SELECT COUNT(*),COALESCE(SUM(amount),0) FROM payout_lockers WHERE player_uuid=? AND created_at>=?")){ps.setString(1,playerUuid.toString());ps.setLong(2,System.currentTimeMillis()-Math.max(1_000L,window));try(ResultSet result=ps.executeQuery()){return result.next()?new long[]{result.getLong(1),result.getLong(2)}:new long[]{0,0};}}}).thenAccept(recent->{if(amount<threshold&&recent[0]<burstCount&&recent[1]<burstTotal)return;try{plugin.getServiceRegistry().get(com.vaultsurvival.plugin.security.StaffAlertService.class).recordAlert("PAYOUT",amount>=threshold*2?"HIGH":"MEDIUM",playerUuid,playerUuid.toString(),"Payout #"+payoutId+" amount="+amount+" source="+sourceType+"/"+sourceId+" recentCount="+recent[0]+" recentTotal="+recent[1],null);}catch(RuntimeException unavailable){plugin.getLogger().warning("Payout alert service unavailable: "+unavailable.getMessage());}}).exceptionally(failure->{plugin.getLogger().warning("Payout scoring failed: "+failure.getMessage());return null;});
    }
}
