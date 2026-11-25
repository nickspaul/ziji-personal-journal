package cn.ziji.ledger;

import cn.ziji.data.DataValidator;
import cn.ziji.data.SchemaRegistry;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DebtAccountingTest {
    private final DataValidator validator = new DataValidator(new SchemaRegistry());
    private Map<String,Object> debt(String platform,String kind,String date,String amount) {
        return new LinkedHashMap<>(Map.of("direction","EXPENSE","category","其他","debtPlatform",platform,
            "debtKind",kind,"date",date,"amount",new BigDecimal(amount)));
    }
    private Map<String,Object> normalize(Map<String,Object> row) { return validator.normalize("ledger",row,null,false); }
    @Test void principalNeverBecomesExpenseOrIncomeAndOpeningHasNoCashEffect() {
        var opening = debt("甲","OPENING","2025-02-01","80.00");
        var purchase = debt("甲","CREDIT","2025-02-07","60.13");
        var payment = debt("甲","REPAYMENT","2025-03-10","101.25");
        payment.put("debtFee",new BigDecimal("1.25"));
        var rows=List.of(opening,purchase,payment);
        assertDoesNotThrow(()->DebtAccounting.validateHistory(rows));
        assertEquals(new BigDecimal("61.38"),rows.stream().map(DebtAccounting::expense).reduce(BigDecimal.ZERO,BigDecimal::add));
        assertEquals(new BigDecimal("101.25"),rows.stream().map(DebtAccounting::cashOutflow).reduce(BigDecimal.ZERO,BigDecimal::add));
        assertEquals(new BigDecimal("40.13"),rows.stream().map(DebtAccounting::principalChange).reduce(BigDecimal.ZERO,BigDecimal::add));
    }
    @Test void paymentsCannotUseAnotherPlatformOrAFuturePurchaseOnTheSamePlatform() {
        var pay=debt("甲","REPAYMENT","2025-02-01","10");
        assertThrows(IllegalArgumentException.class,()->DebtAccounting.validateHistory(List.of(pay,debt("乙","CREDIT","2025-01-01","100"))));
        assertThrows(IllegalArgumentException.class,()->DebtAccounting.validateHistory(List.of(pay,debt("甲","CREDIT","2025-02-02","20"))));
        assertDoesNotThrow(()->DebtAccounting.validateHistory(List.of(pay,debt("甲","CREDIT","2025-02-01","10"))));
        pay.put("debtFee",BigDecimal.TEN);
        assertDoesNotThrow(()->DebtAccounting.validateHistory(List.of(pay)));
    }
    @Test void onlyOneOpeningBalancePerPlatformAtTheStartOfItsHistory() {
        var first=debt("甲","OPENING","2025-02-01","80");
        var second=debt("甲","OPENING","2025-02-02","30");
        assertThrows(IllegalArgumentException.class,()->DebtAccounting.validateHistory(List.of(first,second)));
        assertThrows(IllegalArgumentException.class,()->DebtAccounting.validateHistory(List.of(second,debt("甲","CREDIT","2025-02-01","10"))));
        first.put("deleted",true);
        assertDoesNotThrow(()->DebtAccounting.validateHistory(List.of(first,second)));
    }
    @Test void oldRowsAreOrdinaryAndInconsistentFeeOrTripFieldsAreRejected() {
        var legacy=normalize(new LinkedHashMap<>(Map.of("amount","10.00","category","餐饮")));
        assertNull(legacy.get("debtKind"));
        assertEquals(new BigDecimal("10.00"),DebtAccounting.expense(legacy));
        assertEquals(new BigDecimal("10.00"),DebtAccounting.cashOutflow(legacy));
        var pay=debt("甲","REPAYMENT","2025-02-01","20");
        for(var invalid:List.of("-1","20.01","0.001")) {
            pay.put("debtFee",invalid);
            assertThrows(IllegalArgumentException.class,()->normalize(pay));
        }
        pay.put("debtFee","0");
        assertNull(normalize(pay).get("debtFee"));
        pay.put("tripId",UUID.randomUUID().toString());pay.put("reimbursement","SELF");
        assertThrows(IllegalArgumentException.class,()->normalize(pay));
        var buy=debt("甲","CREDIT","2025-02-01","20");buy.put("direction","INCOME");
        assertThrows(IllegalArgumentException.class,()->normalize(buy));
    }
}
