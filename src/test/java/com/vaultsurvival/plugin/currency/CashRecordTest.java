package com.vaultsurvival.plugin.currency;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CashRecordTest {
    @Test void onlyTransferableMatchingCashPassesValidation(){
        UUID id=UUID.randomUUID();CashSnapshot snapshot=new CashSnapshot(id,1_000);
        assertTrue(new CashRecord(id,1_000,CashItemData.CashState.ACTIVE,"INVENTORY","player",null,null).matches(snapshot));
        assertFalse(new CashRecord(id,999,CashItemData.CashState.ACTIVE,"INVENTORY","player",null,null).matches(snapshot));
        assertFalse(new CashRecord(id,1_000,CashItemData.CashState.IN_VAULT,"VAULT","vault",null,null).matches(snapshot));
        assertFalse(new CashRecord(id,1_000,CashItemData.CashState.SPENT,"SPENT","shop",null,null).matches(snapshot));
    }
}
