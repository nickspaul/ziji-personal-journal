package cn.ziji.ledger;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

public final class DebtAccounting {
    private DebtAccounting() {}

    private static BigDecimal amount(Map<String,Object> row) {
        return new BigDecimal(row.get("amount").toString());
    }

    public static BigDecimal fee(Map<String,Object> row) {
        Object fee = row.get("debtFee");
        return fee == null ? BigDecimal.ZERO : new BigDecimal(fee.toString());
    }

    public static boolean consumption(Map<String,Object> row) {
        return "EXPENSE".equals(row.get("direction")) &&
            (row.get("debtKind") == null || "CREDIT".equals(row.get("debtKind")));
    }

    public static BigDecimal expense(Map<String,Object> row) {
        if (consumption(row)) return amount(row);
        return "REPAYMENT".equals(row.get("debtKind")) ? fee(row) : BigDecimal.ZERO;
    }

    public static BigDecimal cashOutflow(Map<String,Object> row) {
        if (!"EXPENSE".equals(row.get("direction"))) return BigDecimal.ZERO;
        return row.get("debtKind") == null || "REPAYMENT".equals(row.get("debtKind")) ? amount(row) : BigDecimal.ZERO;
    }

    public static BigDecimal principalChange(Map<String,Object> row) {
        if (row.get("debtKind") == null) return BigDecimal.ZERO;
        return "REPAYMENT".equals(row.get("debtKind")) ? fee(row).subtract(amount(row)) : amount(row);
    }

    // A calendar date is the ledger's finest precision. Validate each day's closing balance.
    public static void validateHistory(Collection<Map<String,Object>> rows) {
        var daily = new HashMap<String,TreeMap<String,BigDecimal>>();
        var openings = new HashMap<String,String>();
        for (var row : rows) {
            if (row.get("debtKind") == null || Boolean.TRUE.equals(row.get("deleted"))) continue;
            String platform = row.get("debtPlatform").toString(), date = row.get("date").toString();
            daily.computeIfAbsent(platform, unused -> new TreeMap<>()).merge(date, principalChange(row), BigDecimal::add);
            if ("OPENING".equals(row.get("debtKind")) && openings.put(platform, date) != null)
                throw new IllegalArgumentException(platform + "已有一笔期初欠款，请修改原记录");
        }
        daily.forEach((platform, days) -> {
            String opening = openings.get(platform);
            if (opening != null && opening.compareTo(days.firstKey()) > 0)
                throw new IllegalArgumentException(platform + "的期初日期应在第一笔信用流水当天或之前");
            BigDecimal balance = BigDecimal.ZERO;
            for (var day : days.entrySet()) {
                balance = balance.add(day.getValue());
                if (balance.signum() < 0) throw new IllegalArgumentException(
                    platform + "在 " + day.getKey() + " 的累计还本超过已记欠款 " + balance.abs().toPlainString()
                    + " 元。请核对还款金额，或先补记此前的信用消费、期初欠款");
            }
        });
    }
}
