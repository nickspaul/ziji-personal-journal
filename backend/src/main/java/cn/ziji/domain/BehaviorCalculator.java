package cn.ziji.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Pure calculation boundary. Persisted snapshots, never live catalog values, drive reports. */
public final class BehaviorCalculator {
    private BehaviorCalculator() {}

    public static final String CALCULATION_VERSION = "cost-ethanol-2.0";
    public static final String ALCOHOL_MODEL_VERSION = "watson-scenarios-2.0";
    private static final String RECORDING_POLICY = "UNRECORDED_AS_ZERO";
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal ETHANOL_DENSITY = new BigDecimal("0.789");
    private static final BigDecimal ETHANOL_PER_ML_42 = ETHANOL_DENSITY.multiply(new BigDecimal("0.42"));
    private static final String DISCLAIMER = "这是按已录入数据计算的简化情景范围，不是测量结果或可靠的个人上下界，不能证明酒精已清零、身体已恢复或可以驾驶。";
    private static final List<String> MODEL_SOURCES = List.of(
        "https://pubmed.ncbi.nlm.nih.gov/6986753/",
        "https://pubmed.ncbi.nlm.nih.gov/7289599/",
        "https://pubmed.ncbi.nlm.nih.gov/20304569/",
        "https://dui.tndagc.org/resources/NHTSA%20Drugs%20and%20Human%20Performance%202024/dot_78924_DS1.pdf",
        "https://www.niaaa.nih.gov/publications/alcohol-metabolism");
    private static final List<String> PRICE_SNAPSHOT_KEYS = List.of(
        "snapshotUnitPrice", "snapshotPriceType", "snapshotPriceSourceId",
        "snapshotPriceEffectiveDate", "snapshotPriceCurrency", "snapshotPurchasePayer");
    private static final List<String> SMOKE_SNAPSHOT_KEYS = List.of(
        "snapshotName", "snapshotUnitCount", "snapshotTarMg", "snapshotNicotineMg", "snapshotCoMg",
        "snapshotConstituentMethod", "snapshotSourceUrl", "snapshotSourceDate", "snapshotPackagingVersion");
    private static final List<String> SUM_KEYS = List.of(
        "smokingCount", "smokingCost", "smokingUnknownCostCount", "smokingKnownCostCount",
        "smokingTarMg", "smokingNicotineMg", "smokingCoMg", "smokingUnknownTarCount",
        "smokingUnknownNicotineCount", "smokingUnknownCoCount", "smokingKnownTarCount", "smokingKnownNicotineCount",
        "smokingKnownCoCount", "alcoholSessions", "alcoholBottles", "alcoholOpenedBottles", "alcoholUnknownBottleCount",
        "alcoholKnownBottleCount", "alcoholMl", "alcoholUnknownVolumeCount", "alcoholKnownVolumeCount",
        "ethanolGrams", "alcoholUnknownEthanolCount", "alcoholKnownEthanolCount", "alcoholCost", "alcoholUnknownCostCount",
        "alcoholKnownCostCount", "intimacyCount", "masturbationCount", "sexCount",
        "alcoholRelatedIntimacyCount", "alcoholRelatedSmokingCount", "paidAmount");

    public static Map<String, Object> enrich(String collection, Map<String, Object> input,
            Map<String, Object> previous, Map<String, List<Map<String, Object>>> state) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (previous != null) result.putAll(previous);
        result.putAll(input);
        if ("smoking".equals(collection)) enrichSmoking(result, previous, state);
        if ("drinks".equals(collection)) enrichDrink(result, previous, state);
        if (previous != null && previous.get("cost") == null && result.get("cost") == null)
            return fillMissingCost(collection, result, state);
        return result;
    }

    /** Completes a missing fee without refreshing unrelated historical snapshots. */
    public static Map<String, Object> fillMissingCost(String collection, Map<String, Object> previous,
            Map<String, List<Map<String, Object>>> state) {
        Map<String, Object> original = new LinkedHashMap<>(previous);
        if (!Set.of("smoking", "drinks").contains(collection) || previous.get("cost") != null
                || Boolean.TRUE.equals(previous.get("deleted")) || payerType(previous) != null) return original;
        Map<String, Object> row = new LinkedHashMap<>(previous);
        boolean smoking = "smoking".equals(collection);
        Map<String, Object> product = byId(state, "products", row.get("productId"));
        BigDecimal unit = nonnegativeOrNull(row.get("snapshotUnitPrice"));
        BigDecimal override = nonnegativeOrNull(row.get("unitPrice"));
        String specificationKey = smoking ? "snapshotUnitCount" : "snapshotBottleMl";
        BigDecimal historicalSpecification = positiveOrNull(row.get(specificationKey));
        if (!smoking && historicalSpecification == null)
            historicalSpecification = positiveOrNull(row.get("bottleMl"));
        BigDecimal currentSpecification = product == null ? null
            : positiveOrNull(product.get(smoking ? "unitCount" : "bottleMl"));
        if (unit == null) {
            if (override == null) {
                if (product == null || !(smoking ? "SMOKING" : "ALCOHOL").equals(product.get("kind"))) return original;
                // A current package price cannot be assigned to an explicitly different historical package.
                if (historicalSpecification != null && currentSpecification != null
                        && historicalSpecification.compareTo(currentSpecification) != 0) return original;
            }
            LocalDateTime occurred = parseTime(row.get("occurredAt"));
            if (occurred == null) return original;
            Price price = price(row, state, occurred);
            if (price.amount == null) return original;
            unit = price.amount;
            if (smoking && override == null) {
                BigDecimal unitCount = historicalSpecification != null ? historicalSpecification : currentSpecification;
                if (unitCount == null) return original;
                unit = divide(unit, unitCount);
                if (row.get(specificationKey) == null) row.put(specificationKey, unitCount);
            }
            putPrice(row, price, unit);
            unit = decimal(row.get("snapshotUnitPrice"));
        }
        BigDecimal quantity;
        if (smoking) {
            quantity = positiveOrNull(row.get("count"));
            if (quantity == null || quantity.stripTrailingZeros().scale() > 0) return original;
        } else if ("BOTTLES".equals(row.get("inputMode"))) {
            BigDecimal bottles = positiveOrNull(row.get("bottles"));
            BigDecimal share = row.get("share") == null ? ONE : decimal(row.get("share"));
            if (bottles == null || share.signum() < 0 || share.compareTo(ONE) > 0) return original;
            quantity = bottles.multiply(share);
        } else if ("ML".equals(row.get("inputMode"))) {
            BigDecimal volume = positiveOrNull(row.get("volumeMl"));
            BigDecimal bottleMl = historicalSpecification != null ? historicalSpecification : currentSpecification;
            if (volume == null || bottleMl == null) return original;
            quantity = divide(volume, bottleMl);
            if (row.get(specificationKey) == null) row.put(specificationKey, bottleMl);
        } else return original;
        row.put("cost", round(quantity.multiply(unit), 8));
        row.put("costType", row.get("snapshotPriceType"));
        return row;
    }

    private static void enrichSmoking(Map<String, Object> row, Map<String, Object> previous,
            Map<String, List<Map<String, Object>>> state) {
        LocalDateTime occurred = requiredTime(row, "occurredAt");
        BigDecimal count = requiredPositive(row, "count");
        requireInteger(count, "根数");
        optionalNonnegative(row, "unitPrice");
        boolean sameProduct = unchanged(row, previous, "productId");
        Map<String, Object> product = product(row, previous, state, "SMOKING");
        if (sameProduct && previous.containsKey("snapshotName")) {
            copy(previous, row, SMOKE_SNAPSHOT_KEYS);
        } else {
            row.put("snapshotName", productName(product, "未指定香烟"));
            row.put("snapshotUnitCount", positiveOrNull(product.get("unitCount")));
            row.put("snapshotTarMg", nonnegativeOrNull(product.get("tarMg")));
            row.put("snapshotNicotineMg", nonnegativeOrNull(product.get("nicotineMg")));
            row.put("snapshotCoMg", nonnegativeOrNull(product.get("coMg")));
            row.put("snapshotConstituentMethod", product.get("constituentMethod"));
            row.put("snapshotSourceUrl", product.get("sourceUrl"));
            row.put("snapshotSourceDate", product.get("sourceDate"));
            row.put("snapshotPackagingVersion", product.get("packagingVersion"));
        }
        boolean samePrice = sameProduct && unchanged(row, previous, "unitPrice", "occurredAt");
        if (samePrice && previous.containsKey("snapshotUnitPrice")) {
            copy(previous, row, PRICE_SNAPSHOT_KEYS);
        } else {
            Price price = price(row, state, occurred);
            BigDecimal unitCount = decimal(row.get("snapshotUnitCount"));
            BigDecimal unit = row.get("unitPrice") != null ? decimal(row.get("unitPrice"))
                : price.amount == null || unitCount == null ? null : divide(price.amount, unitCount);
            putPrice(row, price, unit);
            row.put("snapshotPurchasePayer", null);
        }
        BigDecimal unit = decimal(row.get("snapshotUnitPrice"));
        String payerType = payerType(row);
        BigDecimal cost = unit == null ? null : round(count.multiply(unit), 8);
        if (payerType != null) cost = ZERO.setScale(2);
        row.put("cost", cost);
        row.put("costType", payerType != null ? payerType : cost == null ? "UNKNOWN" : row.get("snapshotPriceType"));
        row.put("tarTotalMg", multiplyOrNull(count, decimal(row.get("snapshotTarMg")), 4));
        row.put("nicotineTotalMg", multiplyOrNull(count, decimal(row.get("snapshotNicotineMg")), 4));
        row.put("coTotalMg", multiplyOrNull(count, decimal(row.get("snapshotCoMg")), 4));
        row.put("constituentNotice", "标示烟气成分乘以根数，仅作标签值汇总，不代表实际吸收量；不同检测方法不宜直接比较。");
        row.put("calculationVersion", CALCULATION_VERSION);
    }

    private static void enrichDrink(Map<String, Object> row, Map<String, Object> previous,
            Map<String, List<Map<String, Object>>> state) {
        LocalDateTime occurred = requiredTime(row, "occurredAt");
        String mode = string(row.get("inputMode"));
        if (!Set.of("BOTTLES", "ML").contains(mode)) throw invalid("饮酒录入方式应为瓶数或毫升");
        optionalNonnegative(row, "unitPrice");
        optionalPositive(row, "bottleMl");
        BigDecimal providedAbv = decimal(row.get("abv"));
        if (providedAbv != null && (providedAbv.signum() < 0 || providedAbv.compareTo(HUNDRED) > 0))
            throw invalid("酒精度必须在 0 到 100 之间");
        Map<String, Object> product = product(row, previous, state, "ALCOHOL");
        boolean sameProduct = unchanged(row, previous, "productId");
        row.put("snapshotName", sameProduct && previous.containsKey("snapshotName")
            ? previous.get("snapshotName") : productName(product, "未指定酒品"));
        if (sameProduct && unchanged(row, previous, "bottleMl") && previous.containsKey("snapshotBottleMl")) {
            row.put("snapshotBottleMl", previous.get("snapshotBottleMl"));
        } else {
            row.put("snapshotBottleMl", row.get("bottleMl") != null ? positiveOrNull(row.get("bottleMl"))
                : positiveOrNull(product.get("bottleMl")));
        }
        if (sameProduct && unchanged(row, previous, "abv") && previous.containsKey("snapshotAbv")) {
            row.put("snapshotAbv", previous.get("snapshotAbv"));
        } else {
            BigDecimal abv = providedAbv != null ? providedAbv : decimal(product.get("abv"));
            if (abv != null && (abv.signum() < 0 || abv.compareTo(HUNDRED) > 0)) throw invalid("商品酒精度不合法");
            row.put("snapshotAbv", abv);
        }
        if (sameProduct && previous.containsKey("snapshotSourceUrl")) {
            row.put("snapshotSourceUrl", previous.get("snapshotSourceUrl"));
            row.put("snapshotSourceDate", previous.get("snapshotSourceDate"));
        } else {
            row.put("snapshotSourceUrl", product.get("sourceUrl"));
            row.put("snapshotSourceDate", product.get("sourceDate"));
        }
        BigDecimal bottleMl = decimal(row.get("snapshotBottleMl"));
        BigDecimal volume;
        BigDecimal equivalentBottles;
        if ("BOTTLES".equals(mode)) {
            BigDecimal bottles = requiredPositive(row, "bottles");
            BigDecimal share = row.get("share") == null ? ONE : decimal(row.get("share"));
            if (share.signum() < 0 || share.compareTo(ONE) > 0) throw invalid("本人分量比例必须在 0 到 1 之间");
            equivalentBottles = bottles.multiply(share);
            volume = equivalentBottles.signum() == 0 ? ZERO
                : bottleMl == null ? null : equivalentBottles.multiply(bottleMl);
        } else {
            volume = requiredPositive(row, "volumeMl");
            equivalentBottles = bottleMl == null ? null : divide(volume, bottleMl);
        }
        row.put("actualMl", volume == null ? null : round(volume, 4));
        row.put("equivalentBottles", equivalentBottles == null ? null : round(equivalentBottles, 6));
        BigDecimal abv = decimal(row.get("snapshotAbv"));
        BigDecimal ethanol = volume == null || abv == null ? null
            : volume.multiply(abv).divide(HUNDRED).multiply(ETHANOL_DENSITY);
        if (volume != null && volume.signum() == 0) ethanol = ZERO;
        row.put("ethanolGrams", ethanol == null ? null : round(ethanol, 4));
        row.put("ethanolEquivalent42Ml", equivalent42(decimal(row.get("ethanolGrams"))));
        boolean samePrice = sameProduct && unchanged(row, previous, "unitPrice", "occurredAt");
        if (samePrice && previous.containsKey("snapshotUnitPrice")) {
            copy(previous, row, PRICE_SNAPSHOT_KEYS);
        } else {
            Price price = price(row, state, occurred);
            putPrice(row, price, price.amount);
            row.put("snapshotPurchasePayer", null);
        }
        BigDecimal unit = decimal(row.get("snapshotUnitPrice"));
        BigDecimal cost = equivalentBottles == null || unit == null ? null : round(equivalentBottles.multiply(unit), 8);
        String payerType = payerType(row);
        if (payerType != null) cost = ZERO.setScale(2);
        row.put("cost", cost);
        row.put("costType", payerType != null ? payerType : cost == null ? "UNKNOWN" : row.get("snapshotPriceType"));
        row.put("calculationVersion", CALCULATION_VERSION);
    }

    private record Price(BigDecimal amount, String type, Object sourceId, Object date, String currency) {}

    private static Price price(Map<String, Object> row, Map<String, List<Map<String, Object>>> state,
            LocalDateTime occurred) {
        if (row.get("unitPrice") != null)
            return new Price(decimal(row.get("unitPrice")), "OVERRIDE", null, occurred.toLocalDate().toString(), "CNY");
        Map<String, Object> product = byId(state, "products", row.get("productId"));
        if (product != null && Boolean.TRUE.equals(product.get("priceConfirmed"))
                && nonnegativeOrNull(product.get("packagePrice")) != null) {
            String type = string(product.get("priceType"));
            if (!Set.of("ACTUAL", "PERSONAL", "REFERENCE").contains(type)) type = "PERSONAL";
            return new Price(decimal(product.get("packagePrice")), type, product.get("id"),
                occurred.toLocalDate().toString(), "CNY");
        }
        // Only old products without confirmation metadata may fall back to price history.
        // Clearing or revoking a catalog price must not silently reactivate an older price.
        if (product != null && (product.get("packagePrice") != null || Boolean.FALSE.equals(product.get("priceConfirmed"))))
            return new Price(null, "UNKNOWN", null, null, "CNY");
        for (String type : List.of("ACTUAL", "PERSONAL", "REFERENCE")) {
            Map<String, Object> selected = rows(state, "prices").stream()
                .filter(p -> !blank(row.get("productId")) && same(p.get("productId"), row.get("productId")))
                .filter(p -> type.equals(string(p.get("priceType"))))
                .filter(p -> "CNY".equals(string(p.getOrDefault("currency", "CNY"))))
                .filter(p -> Boolean.TRUE.equals(p.get("verified")))
                .filter(p -> date(p.get("effectiveDate")) != null && !date(p.get("effectiveDate")).isAfter(occurred.toLocalDate()))
                .filter(p -> nonnegativeOrNull(p.get("amount")) != null)
                .max(Comparator.comparing((Map<String, Object> p) -> date(p.get("effectiveDate")))
                    .thenComparing(p -> string(p.get("updatedAt"))).thenComparing(p -> string(p.get("id"))))
                .orElse(null);
            if (selected != null) return new Price(decimal(selected.get("amount")), type,
                selected.get("id"), selected.get("effectiveDate"), "CNY");
        }
        return new Price(null, "UNKNOWN", null, null, "CNY");
    }

    private static String payerType(Map<String, Object> row) {
        if ("GIFT".equals(string(row.get("source")))) return "GIFT";
        return "OTHER".equals(string(row.get("snapshotPurchasePayer"))) ? "PAID_BY_OTHER" : null;
    }

    private static void putPrice(Map<String, Object> row, Price price, BigDecimal unit) {
        row.put("snapshotUnitPrice", unit == null ? null : round(unit, 8));
        row.put("snapshotPriceType", price.type);
        row.put("snapshotPriceSourceId", price.sourceId);
        row.put("snapshotPriceEffectiveDate", price.date);
        row.put("snapshotPriceCurrency", price.currency);
    }

    public static Map<String, Object> analytics(Map<String, List<Map<String, Object>>> state,
            Map<String, Object> settings, LocalDate from, LocalDate to, LocalDateTime now) {
        if (from == null || to == null || to.isBefore(from)) throw invalid("统计日期范围不合法");
        long dayCount = ChronoUnit.DAYS.between(from, to) + 1;
        if (dayCount > 36600) throw invalid("统计范围不能超过 100 年");
        if (now == null) throw invalid("当前时间不能为空");
        Map<LocalDate, Map<String, Object>> days = new LinkedHashMap<>();
        Map<LocalDate, List<Map<String, Object>>> smokingByDay = new LinkedHashMap<>();
        List<Map<String, Object>> smokingInRange = new ArrayList<>();
        Map<String, Object> smokingLinks = new LinkedHashMap<>();
        Map<String, Object> smokingAssociation = emptyAssociation("根");
        Map<String, Object> intimacyAssociation = emptyAssociation("次");
        List<Map<String, Object>> occurredDrinks = occurredRows(state, "drinks", "occurredAt", now);
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            Map<String, Object> value = emptySums();
            value.put("date", day.toString());
            value.put("isFuture", day.isAfter(now.toLocalDate()));
            value.put("hasSmokingRecords", false);
            value.put("hasAlcoholRecords", false);
            value.put("hasIntimacyRecords", false);
            days.put(day, value);
        }
        for (Map<String, Object> row : occurredRows(state, "smoking", "occurredAt", now)) {
            Map<String, Object> day = day(days, row.get("occurredAt"));
            if (day == null) continue;
            smokingByDay.computeIfAbsent(parseTime(row.get("occurredAt")).toLocalDate(), ignored -> new ArrayList<>()).add(row);
            smokingInRange.add(row);
            day.put("hasSmokingRecords", true);
            accumulateSmoking(day, row);
            Map<String, Object> link = alcoholLink(row, state, settings, now);
            smokingLinks.put(string(row.get("id")), link);
            addAssociation(smokingAssociation, row, link);
            if ("YES".equals(link.get("effective"))) add(day, "alcoholRelatedSmokingCount", row.get("count"));
        }
        for (Map<String, Object> row : occurredRows(state, "sessions", "startedAt", now)) {
            Map<String, Object> day = day(days, row.get("startedAt"));
            if (day != null) {
                add(day, "alcoholSessions", ONE);
                day.put("hasAlcoholRecords", true);
                if (occurredDrinks.stream().noneMatch(drink -> same(drink.get("sessionId"), row.get("id")))) {
                    accumulateEmptySession(day);
                }
            }
        }
        for (Map<String, Object> row : occurredDrinks) {
            Map<String, Object> day = day(days, row.get("occurredAt"));
            if (day == null) continue;
            day.put("hasAlcoholRecords", true);
            accumulateDrink(day, row);
        }
        Map<String, Object> links = new LinkedHashMap<>();
        for (Map<String, Object> row : occurredRows(state, "intimacy", "occurredAt", now)) {
            Map<String, Object> day = day(days, row.get("occurredAt"));
            if (day == null) continue;
            day.put("hasIntimacyRecords", true);
            accumulateIntimacy(day, row);
            Map<String, Object> link = alcoholLink(row, state, settings, now);
            links.put(string(row.get("id")), link);
            addAssociation(intimacyAssociation, row, link);
            if ("YES".equals(link.get("effective"))) add(day, "alcoholRelatedIntimacyCount", row.getOrDefault("count", ONE));
        }
        for (Map<String, Object> row : occurredRows(state, "purchases", "occurredAt", now)) {
            Map<String, Object> day = day(days, row.get("occurredAt"));
            if (day != null && !"OTHER".equals(string(row.get("payer")))) add(day, "paidAmount", row.get("amount"));
        }
        Map<String, Object> totals = emptySums();
        long elapsedDays = 0;
        for (Map<String, Object> day : days.values()) {
            if (Boolean.TRUE.equals(day.get("isFuture"))) {
                finalizeSums(day);
                leaveFutureUnfilled(day);
                continue;
            }
            elapsedDays++;
            for (String key : SUM_KEYS) add(totals, key, day.get(key));
            finalizeSums(day);
            applyConstituentGroups(day, constituentGroups(smokingByDay.getOrDefault(LocalDate.parse(day.get("date").toString()), List.of())));
        }
        finalizeSums(totals);
        applyConstituentGroups(totals, constituentGroups(smokingInRange));
        if (elapsedDays == 0) leaveFutureUnfilled(totals);
        totals.put("days", dayCount);
        totals.put("elapsedDays", elapsedDays);
        totals.put("costNotice", "费用按种类单价与本人用量计算，不依赖购买流水。历史记录保留当时的单价；缺价时显示已知小计与缺失记录数。");
        totals.put("ethanolEquivalent42Notice", "按相同纯酒精量折合 42%vol 白酒的毫升数，仅用于量纲比较，不表示饮用建议或安全量。");
        Map<String, Object> alcohol = alcoholEstimate(state, now, now);
        List<String> warnings = new ArrayList<>();
        if (number(totals.get("unknownCostCount")) > 0) warnings.add("部分记录缺少价格，费用为已知部分小计。");
        if (number(totals.get("alcoholUnknownEthanolCount")) > 0) warnings.add("部分饮酒记录缺少容量或酒精度，纯酒精总量不完整。");
        warnings.add("烟气成分为标签值汇总，不等于人体实际吸收量。");
        if (Boolean.TRUE.equals(totals.get("constituentMixedMethods"))) warnings.add("烟气标示存在多种检测口径，已按口径分别汇总。");
        return map("from", from.toString(), "to", to.toString(), "daily", new ArrayList<>(days.values()),
            "totals", totals, "alcohol", alcohol, "links", links, "smokingLinks", smokingLinks,
            "associations", map("smoking", finishAssociation(smokingAssociation), "intimacy", finishAssociation(intimacyAssociation)),
            "warnings", warnings, "recordingPolicy", RECORDING_POLICY);
    }

    /** Lifetime quantities and a bounded trend, without evaluating per-record alcohol associations. */
    public static Map<String, Object> summary(Map<String, List<Map<String, Object>>> state, LocalDateTime now) {
        if (now == null) throw invalid("当前时间不能为空");
        LocalDate today = now.toLocalDate();
        LocalDate earliest = null;
        Map<String, Object> totals = emptySums();
        List<Map<String, Object>> smoking = occurredRows(state, "smoking", "occurredAt", now);
        List<Map<String, Object>> sessions = occurredRows(state, "sessions", "startedAt", now);
        List<Map<String, Object>> drinks = occurredRows(state, "drinks", "occurredAt", now);
        List<Map<String, Object>> intimacy = occurredRows(state, "intimacy", "occurredAt", now);
        for (List<Map<String, Object>> group : List.of(smoking, sessions, drinks, intimacy)) {
            for (Map<String, Object> row : group) {
                LocalDate recorded = parseTime(row.get(row.containsKey("startedAt") ? "startedAt" : "occurredAt")).toLocalDate();
                if (earliest == null || recorded.isBefore(earliest)) earliest = recorded;
            }
        }
        ChronoUnit unit = ChronoUnit.DAYS;
        if (earliest != null && ChronoUnit.DAYS.between(earliest, today) + 1 > 731) {
            unit = today.isAfter(earliest.plusYears(30)) ? ChronoUnit.YEARS : ChronoUnit.MONTHS;
        }
        Map<LocalDate, Map<String, Object>> periods = new LinkedHashMap<>();
        for (Map<String, Object> row : smoking)
            accumulateSmoking(trendBucket(periods, row.get("occurredAt"), unit), row);
        Set<String> populatedSessions = new LinkedHashSet<>();
        for (Map<String, Object> row : drinks) {
            populatedSessions.add(string(row.get("sessionId")));
            accumulateDrink(trendBucket(periods, row.get("occurredAt"), unit), row);
        }
        for (Map<String, Object> row : sessions) {
            Map<String, Object> bucket = trendBucket(periods, row.get("startedAt"), unit);
            add(bucket, "alcoholSessions", ONE);
            if (!populatedSessions.contains(string(row.get("id")))) accumulateEmptySession(bucket);
        }
        for (Map<String, Object> row : intimacy)
            accumulateIntimacy(trendBucket(periods, row.get("occurredAt"), unit), row);
        for (Map<String, Object> row : occurredRows(state, "purchases", "occurredAt", now))
            if (!"OTHER".equals(string(row.get("payer")))) add(totals, "paidAmount", row.get("amount"));
        for (Map<String, Object> bucket : periods.values()) {
            // Add unrounded values before resolving missing data for the individual period.
            for (String key : SUM_KEYS) add(totals, key, bucket.get(key));
            finalizeSums(bucket);
        }
        finalizeSums(totals);
        totals.put("hasSmokingRecords", !smoking.isEmpty());
        totals.put("hasAlcoholRecords", !sessions.isEmpty() || !drinks.isEmpty());
        totals.put("hasIntimacyRecords", !intimacy.isEmpty());
        applyConstituentGroups(totals, constituentGroups(smoking));
        totals.put("alcoholRelatedSmokingCount", null);
        totals.put("alcoholRelatedIntimacyCount", null);
        totals.put("days", earliest == null ? 0L : ChronoUnit.DAYS.between(earliest, today) + 1);
        totals.put("costNotice", "累计按行为保存时的单价与本人用量汇总；缺价记录不按零元处理，购买流水不参与消耗费用。");
        totals.put("ethanolEquivalent42Notice", "按相同纯酒精量折合 42%vol 白酒的毫升数，仅用于量纲比较，不表示饮用建议或安全量。");
        return map("from", earliest == null ? null : earliest.toString(), "to", today.toString(), "totals", totals,
            "trend", lifetimeTrend(periods, earliest, today, unit), "recordingPolicy", RECORDING_POLICY);
    }

    private static LocalDate periodStart(LocalDate date, ChronoUnit unit) {
        return switch (unit) {
            case MONTHS -> date.withDayOfMonth(1);
            case YEARS -> date.withDayOfYear(1);
            default -> date;
        };
    }

    private static Map<String, Object> trendBucket(Map<LocalDate, Map<String, Object>> periods, Object time, ChronoUnit unit) {
        LocalDate key = periodStart(parseTime(time).toLocalDate(), unit);
        return periods.computeIfAbsent(key, ignored -> emptySums());
    }

    private static Map<String, Object> lifetimeTrend(Map<LocalDate, Map<String, Object>> periods,
            LocalDate earliest, LocalDate today, ChronoUnit unit) {
        String label = switch (unit) {
            case MONTHS -> "month";
            case YEARS -> "year";
            default -> "day";
        };
        List<Map<String, Object>> points = new ArrayList<>();
        if (earliest != null) {
            for (LocalDate start = periodStart(earliest, unit); !start.isAfter(today); start = start.plus(1, unit)) {
                Map<String, Object> bucket = periods.getOrDefault(start, Map.of());
                points.add(map("date", (start.isBefore(earliest) ? earliest : start).toString(),
                    "smokingCount", bucket.getOrDefault("smokingCount", ZERO),
                    "ethanolEquivalent42Ml", bucket.getOrDefault("ethanolEquivalent42Ml", ZERO),
                    "intimacyCount", bucket.getOrDefault("intimacyCount", ZERO)));
            }
        }
        return map("unit", label, "points", points);
    }

    private static List<Map<String, Object>> occurredRows(Map<String, List<Map<String, Object>>> state,
            String collection, String timeKey, LocalDateTime now) {
        return rows(state, collection).stream().filter(row -> {
            LocalDateTime occurred = parseTime(row.get(timeKey));
            return occurred != null && !occurred.isAfter(now);
        }).toList();
    }

    private static void accumulateIntimacy(Map<String, Object> target, Map<String, Object> row) {
        Object count = row.getOrDefault("count", ONE);
        add(target, "intimacyCount", count);
        add(target, "SEX".equals(row.get("activityType")) ? "sexCount" : "masturbationCount", count);
    }

    private static void accumulateSmoking(Map<String, Object> target, Map<String, Object> row) {
        add(target, "smokingCount", row.get("count"));
        addKnown(target, "smokingCost", "smokingUnknownCostCount", "smokingKnownCostCount", row.get("cost"));
        addKnown(target, "smokingTarMg", "smokingUnknownTarCount", "smokingKnownTarCount", row.get("tarTotalMg"));
        addKnown(target, "smokingNicotineMg", "smokingUnknownNicotineCount", "smokingKnownNicotineCount", row.get("nicotineTotalMg"));
        addKnown(target, "smokingCoMg", "smokingUnknownCoCount", "smokingKnownCoCount", row.get("coTotalMg"));
    }

    private static void accumulateDrink(Map<String, Object> target, Map<String, Object> row) {
        addKnown(target, "alcoholBottles", "alcoholUnknownBottleCount", "alcoholKnownBottleCount", row.get("equivalentBottles"));
        if ("BOTTLES".equals(string(row.get("inputMode")))) add(target, "alcoholOpenedBottles", row.get("bottles"));
        addKnown(target, "alcoholMl", "alcoholUnknownVolumeCount", "alcoholKnownVolumeCount", row.get("actualMl"));
        addKnown(target, "ethanolGrams", "alcoholUnknownEthanolCount", "alcoholKnownEthanolCount", row.get("ethanolGrams"));
        addKnown(target, "alcoholCost", "alcoholUnknownCostCount", "alcoholKnownCostCount", row.get("cost"));
    }

    private static void accumulateEmptySession(Map<String, Object> target) {
        for (String key : List.of("alcoholUnknownBottleCount", "alcoholUnknownVolumeCount", "alcoholUnknownEthanolCount", "alcoholUnknownCostCount"))
            add(target, key, ONE);
    }

    private static Map<String, Object> emptyAssociation(String unit) {
        Map<String, Object> result = map("quantityUnit", unit,
            "denominatorLabel", "当前统计范围内的全部已记录事件条数，包括信息不足的记录",
            "notice", "关联表示记录时间落在饮酒场次或模型情景时段，或由本人确认；不是因果关系。没有饮酒记录不能证明没有饮酒。每条批量记录按一次事件计算，数量另行统计。");
        for (String key : List.of("totalEvents", "totalQuantity", "relatedEvents", "relatedQuantity", "uncertainEvents",
                "uncertainQuantity", "duringEvents", "modelWindowEvents", "recentEvents", "insufficientEvents",
                "noRecordedRecentEvents", "manualYesEvents", "manualNoEvents")) result.put(key, ZERO);
        return result;
    }

    private static void addAssociation(Map<String, Object> summary, Map<String, Object> row, Map<String, Object> link) {
        BigDecimal quantity = decimal(row.get("count"));
        if (quantity == null) quantity = ONE;
        add(summary, "totalEvents", ONE);
        add(summary, "totalQuantity", quantity);
        if ("YES".equals(link.get("effective"))) {
            add(summary, "relatedEvents", ONE);
            add(summary, "relatedQuantity", quantity);
        } else if (!"NO".equals(link.get("effective"))) {
            add(summary, "uncertainEvents", ONE);
            add(summary, "uncertainQuantity", quantity);
        }
        String bucket = switch (string(link.get("status"))) {
            case "DURING_DRINKING" -> "duringEvents";
            case "POSSIBLE_AFTER_DRINKING" -> "modelWindowEvents";
            case "RECENT_RECORDED_DRINKING" -> "recentEvents";
            case "INSUFFICIENT_DATA" -> "insufficientEvents";
            default -> "noRecordedRecentEvents";
        };
        add(summary, bucket, ONE);
        if ("MANUAL".equals(link.get("source"))) {
            if ("YES".equals(link.get("effective"))) add(summary, "manualYesEvents", ONE);
            if ("NO".equals(link.get("effective"))) add(summary, "manualNoEvents", ONE);
        }
    }

    private static Map<String, Object> finishAssociation(Map<String, Object> summary) {
        BigDecimal totalEvents = decimal(summary.get("totalEvents"));
        BigDecimal totalQuantity = decimal(summary.get("totalQuantity"));
        summary.put("relatedPercent", totalEvents.signum() == 0 ? null
            : decimal(summary.get("relatedEvents")).multiply(HUNDRED).divide(totalEvents, 1, RoundingMode.HALF_UP));
        summary.put("relatedQuantityPercent", totalQuantity.signum() == 0 ? null
            : decimal(summary.get("relatedQuantity")).multiply(HUNDRED).divide(totalQuantity, 1, RoundingMode.HALF_UP));
        return summary;
    }

    private static List<Map<String, Object>> constituentGroups(List<Map<String, Object>> records) {
        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : records) {
            String method = string(row.get("snapshotConstituentMethod")).strip();
            if (method.isBlank()) method = "未注明检测口径";
            Map<String, Object> group = groups.computeIfAbsent(method, key -> map("method", key, "smokingCount", ZERO,
                "tarMg", ZERO, "nicotineMg", ZERO, "coMg", ZERO,
                "tarCoveredCount", ZERO, "nicotineCoveredCount", ZERO, "coCoveredCount", ZERO));
            add(group, "smokingCount", row.get("count"));
            for (String[] keys : List.of(new String[] {"tarTotalMg", "tarMg", "tarCoveredCount"},
                    new String[] {"nicotineTotalMg", "nicotineMg", "nicotineCoveredCount"},
                    new String[] {"coTotalMg", "coMg", "coCoveredCount"})) {
                if (row.get(keys[0]) != null) {
                    add(group, keys[1], row.get(keys[0]));
                    add(group, keys[2], row.get("count"));
                }
            }
        }
        for (Map<String, Object> group : groups.values()) {
            for (String[] keys : List.of(new String[] {"tarMg", "tarCoveredCount"},
                    new String[] {"nicotineMg", "nicotineCoveredCount"}, new String[] {"coMg", "coCoveredCount"}))
                if (number(group.get(keys[1])) == 0) group.put(keys[0], null);
        }
        return new ArrayList<>(groups.values());
    }

    private static void applyConstituentGroups(Map<String, Object> summary, List<Map<String, Object>> groups) {
        summary.put("tobaccoByMethod", groups);
        summary.put("constituentMixedMethods", groups.size() > 1);
        if (groups.size() > 1) for (String key : List.of("smokingTarMg", "smokingNicotineMg", "smokingCoMg")) summary.put(key, null);
    }

    public static Map<String, Object> alcoholLink(Map<String, Object> intimacy,
            Map<String, List<Map<String, Object>>> state, Map<String, Object> settings, LocalDateTime knownAt) {
        TimeRange event = timeRange(intimacy, "occurredAt", knownAt);
        String status;
        String reason;
        Set<String> sessionIds = new LinkedHashSet<>();
        int recentHours = 24;
        BigDecimal configuredHours = decimal(settings.get("recentAlcoholHours"));
        if (configuredHours != null) recentHours = Math.max(1, Math.min(168, configuredHours.intValue()));
        LocalDateTime recentStart = event == null ? null : event.from.minusHours(recentHours);
        boolean during = false;
        boolean dateUncertain = event == null || "DATE".equals(event.precision);
        boolean uncertainOrder = false;
        boolean approximate = event != null && "APPROX".equals(event.precision);
        boolean recent = false;
        boolean unknownAlcohol = false;
        for (Map<String, Object> session : rows(state, "sessions")) {
            TimeRange start = timeRange(session, "startedAt", knownAt);
            TimeRange end = timeRange(session, "endedAt", knownAt);
            if (start == null || event == null || start.from.isAfter(event.to)) continue;
            List<Map<String, Object>> sessionDrinks = rows(state, "drinks").stream()
                .filter(d -> same(d.get("sessionId"), session.get("id"))).toList();
            if (!sessionDrinks.isEmpty() && sessionDrinks.stream().allMatch(d -> d.get("ethanolGrams") != null
                    && decimal(d.get("ethanolGrams")).signum() == 0)) continue;
            LocalDateTime finalPossible = end == null ? start.to : end.to;
            if (finalPossible.isBefore(recentStart)) continue;
            sessionIds.add(string(session.get("id")));
            recent = true;
            dateUncertain |= "DATE".equals(start.precision);
            approximate |= "APPROX".equals(start.precision);
            if ("EXACT".equals(event.precision) && "EXACT".equals(start.precision)
                    && end != null && !event.to.isAfter(end.from) && !event.from.isBefore(start.to)) during = true;
            if (("APPROX".equals(start.precision) || "APPROX".equals(event.precision))
                    && !start.to.isBefore(event.from) && !start.from.isAfter(event.to)) uncertainOrder = true;
        }
        for (Map<String, Object> drink : rows(state, "drinks")) {
            TimeRange occurred = timeRange(drink, "occurredAt", knownAt);
            if (occurred == null || event == null || occurred.from.isAfter(event.to) || occurred.to.isBefore(recentStart)) continue;
            BigDecimal ethanol = decimal(drink.get("ethanolGrams"));
            if (ethanol != null && ethanol.signum() == 0) continue;
            recent = true;
            if (!blank(drink.get("sessionId"))) sessionIds.add(string(drink.get("sessionId")));
            unknownAlcohol |= ethanol == null;
            dateUncertain |= "DATE".equals(occurred.precision);
            approximate |= "APPROX".equals(occurred.precision);
            if (("APPROX".equals(occurred.precision) || "APPROX".equals(event.precision))
                    && !occurred.to.isBefore(event.from) && !occurred.from.isAfter(event.to)) uncertainOrder = true;
        }
        if (dateUncertain) {
            status = "INSUFFICIENT_DATA";
            reason = "日期级记录可以查看同日共现，但无法确定是否发生在饮酒后；无需猜测分钟，也可自行标记关联。";
        } else if (uncertainOrder) {
            status = "RECENT_RECORDED_DRINKING";
            reason = "大概时间有重叠，可能在同一时段发生，无法确定先后；保留为近期共现，不自动认定酒后。";
        } else if (during) {
            status = "DURING_DRINKING";
            reason = "记录时间落在已记录的饮酒场次起止时间内。";
        } else {
            Map<String, Object> estimate = alcoholEstimate(state, event.to, knownAt);
            Map<String, Object> earliest = event.from.equals(event.to) ? estimate : alcoholEstimate(state, event.from, knownAt);
            boolean possible = List.of(estimate, earliest).stream().anyMatch(value -> "ESTIMATE".equals(value.get("status"))
                && (number(value.get("estimatedBacHighGPerL")) > 0 || Boolean.TRUE.equals(value.get("absorptionMayContinue"))));
            if (possible) {
                status = "POSSIBLE_AFTER_DRINKING";
                reason = approximate ? "按大概时间计算，至少一个简化模型情景可能重叠；这是可能关联，不是实测判断。"
                    : "根据已记录饮酒，发生时间仍处于至少一个简化模型情景时段；这不是实测判断。";
            } else if (unknownAlcohol) {
                status = "INSUFFICIENT_DATA";
                reason = "近期饮酒存在，但容量或酒精度缺失，无法判断代谢时段。";
            } else if (recent) {
                status = "RECENT_RECORDED_DRINKING";
                reason = "此前 " + recentHours + " 小时内有饮酒记录；该时窗是关联设置，不是生理代谢界限。"
                    + ("INSUFFICIENT_DATA".equals(estimate.get("status")) ? "个体模型资料不足，无法判断是否仍处于代谢时段。" : "");
            } else {
                status = "NO_RECORDED_RECENT_DRINKING";
                reason = "未发现相关时窗内的已记录饮酒；这不能证明没有饮酒。";
            }
        }
        String automatic = Set.of("DURING_DRINKING", "POSSIBLE_AFTER_DRINKING").contains(status) ? "YES" : "UNKNOWN";
        String manual = string(intimacy.getOrDefault("manualAlcohol", "AUTO"));
        boolean override = Set.of("YES", "NO", "UNKNOWN").contains(manual);
        return map("status", status, "autoEffective", automatic, "effective", override ? manual : automatic,
            "source", override ? "MANUAL" : "AUTO", "manualAlcohol", manual,
            "overrideReason", intimacy.get("overrideReason"), "sessionIds", new ArrayList<>(sessionIds),
            "reason", reason, "modelVersion", ALCOHOL_MODEL_VERSION);
    }
    private record Dose(LocalDateTime time, double concentration, String sessionId) {}
    private record TimedDose(TimeRange time, double concentration, String sessionId) {}
    private record TimeRange(LocalDateTime from, LocalDateTime to, String precision) {}
    private record BodySelection(Map<String, Object> row, String selection) {}
    private record Scenario(double current, LocalDateTime projectedEnd) {}

    public static Map<String, Object> alcoholEstimate(Map<String, List<Map<String, Object>>> state,
            LocalDateTime at, LocalDateTime knownAt) {
        List<Map<String, Object>> missing = new ArrayList<>();
        List<Map<String, Object>> historicalMissing = new ArrayList<>();
        List<Map<String, Object>> measurements = new ArrayList<>();
        Set<String> warnings = new LinkedHashSet<>();
        Map<String, Object> result = map("status", "NO_RECENT_RECORDS", "estimatedBacLowGPerL", null,
            "estimatedBacHighGPerL", null, "estimatedNearZeroFrom", null, "estimatedNearZeroTo", null,
            "latestDrinkAt", null, "missing", new ArrayList<String>(), "missingDetails", missing,
            "historicalMissingDetails", historicalMissing, "historicalMissingCount", 0,
            "bodyMeasurements", measurements, "warnings", new ArrayList<String>(),
            "timePrecision", "EXACT", "durationHoursLow", null, "durationHoursHigh", null,
            "durationFrom", "从各情景中最后一次饮用时间起算；多次饮用共用一条消除时间线。",
            "disclaimer", DISCLAIMER, "modelVersion", ALCOHOL_MODEL_VERSION, "sources", MODEL_SOURCES,
            "calculationSteps", List.of(
                "本人饮用毫升数 × 酒精度 ÷ 100 × 0.789，得到纯酒精克数。",
                "用测量记录的身高、体重、年龄和模型生理参数代入成人 Watson 方程，估算体水量。",
                "纯酒精克数 × 0.8 ÷ 体水量，结合饮用的大概时段模拟吸收与消除。",
                "多种吸收、消除及饮用时间情景分别计算，时长按整小时向外取整。"),
            "assumptions", List.of(
                "仅使用已经录入的饮酒，不推断漏记；情景范围不是置信区间或实际人体保证上下界。",
                "大概时间按前后各 1 小时，日期记录按当天可能时段；这些是录入规则，不是医学参数。",
                "优先采用饮酒时或之前的有效身体测量；缺少时可采用此后 30 天内、已经测得的最近资料，并明确标注近似。",
                "最近一次记录附近 72 小时用于定位待补资料，不是生理代谢边界；更早的已知剂量仍进入连续计算，较早未知剂量单列提醒。",
                "成人 Watson 体水量方程和 0.8 血液水分系数；不根据酒量大或自觉清醒提高消除速度。",
                "以文献常见 30/90 分钟达峰时间构造均匀吸收情景，线性吸收是工程近似，未经个人校准。",
                "消除情景为 0.10/0.20 g/L/小时，低浓度仍线性外推，因此不能确认实际清零。"));
        if (at == null) return result;
        LocalDateTime anchor = null;
        for (Map<String, Object> row : rows(state, "drinks")) {
            TimeRange time = timeRange(row, "occurredAt", knownAt);
            BigDecimal grams = decimal(row.get("ethanolGrams"));
            if (time != null && !time.from.isAfter(at) && (grams == null || grams.signum() != 0)) {
                LocalDateTime candidate = time.to.isAfter(at) ? at : time.to;
                if (anchor == null || candidate.isAfter(anchor)) anchor = candidate;
            }
        }
        Set<String> populatedSessions = new LinkedHashSet<>();
        rows(state, "drinks").forEach(drink -> {
            TimeRange time = timeRange(drink, "occurredAt", knownAt);
            if (time != null && !time.from.isAfter(at)) populatedSessions.add(string(drink.get("sessionId")));
        });
        for (Map<String, Object> row : rows(state, "sessions")) {
            if (populatedSessions.contains(string(row.get("id")))) continue;
            TimeRange time = timeRange(row, "startedAt", knownAt);
            if (time != null && !time.from.isAfter(at)) {
                LocalDateTime candidate = time.to.isAfter(at) ? at : time.to;
                if (anchor == null || candidate.isAfter(anchor)) anchor = candidate;
            }
        }
        LocalDateTime scopeFrom = anchor == null ? at.minusHours(72) : anchor.minusHours(72);
        result.put("scope", map("from", scopeFrom.toString(), "to", anchor == null ? at.toString() : anchor.toString(),
            "label", "最近一次饮酒附近 3 天", "notice", "这是资料检查的显示范围，不是代谢界限；更早的已知剂量仍参与计算。"));
        List<TimedDose> timedDoses = new ArrayList<>();
        LocalDateTime latest = null;
        LocalDateTime latestPossible = null;
        BigDecimal totalGrams = ZERO;
        int historicalKnownDoseCount = 0;
        String precision = "EXACT";
        for (Map<String, Object> drink : rows(state, "drinks")) {
            TimeRange time = timeRange(drink, "occurredAt", knownAt);
            List<Map<String, Object>> issues = time != null && time.to.isBefore(scopeFrom) ? historicalMissing : missing;
            if (time == null) { addIssue(issues, "DRINK_TIME", "饮酒记录缺少发生日期", "drinks", drink); continue; }
            if (time.from.isAfter(at)) continue;
            BigDecimal grams = decimal(drink.get("ethanolGrams"));
            if (grams != null && grams.signum() == 0) continue;
            if (latest == null || time.from.isAfter(latest)) latest = time.from;
            if (latestPossible == null || time.to.isAfter(latestPossible)) latestPossible = time.to;
            if (!time.to.isBefore(scopeFrom)) {
                if ("DATE".equals(time.precision)) precision = "DATE";
                else if ("APPROX".equals(time.precision) && !"DATE".equals(precision)) precision = "APPROX";
            }
            if (grams == null) { addIssue(issues, "DRINK_AMOUNT", "这条饮酒记录缺少实际容量或酒精度", "drinks", drink); continue; }
            if (grams.signum() < 0) { addIssue(issues, "DRINK_AMOUNT", "饮酒记录的纯酒精量不合法", "drinks", drink); continue; }
            if (!time.to.isBefore(scopeFrom)) totalGrams = totalGrams.add(grams);
            BodySelection selected = selectBody(state, time.from, knownAt);
            List<String> bodyMissing = new ArrayList<>();
            Double water = bodyWater(selected == null ? null : selected.row, bodyMissing);
            if (water == null) {
                for (String message : bodyMissing) {
                    Map<String, Object> issue = map("code", "BODY_PARAMETERS", "message", message,
                        "collection", "body", "id", selected == null ? null : selected.row.get("id"),
                        "sessionId", drink.get("sessionId"), "occurredAt", drink.get("occurredAt"), "drinkId", drink.get("id"));
                    issues.add(issue);
                }
                continue;
            }
            double concentration = grams.doubleValue() * 0.8 / water;
            if (!Double.isFinite(concentration) || concentration > 876) {
                addIssue(issues, "MODEL_DOSE", "输入剂量超出模型可计算范围，请检查容量和单位", "drinks", drink);
                continue;
            }
            timedDoses.add(new TimedDose(time, concentration, string(drink.get("sessionId"))));
            if (time.to.isBefore(scopeFrom)) historicalKnownDoseCount++;
            else {
                measurements.add(map("id", selected.row.get("id"), "measuredAt", selected.row.get("measuredAt"),
                    "selection", selected.selection, "drinkId", drink.get("id"), "drinkAt", drink.get("occurredAt"),
                    "heightCm", selected.row.get("heightCm"), "weightKg", selected.row.get("weightKg"),
                    "age", selected.row.get("age"), "sex", selected.row.get("sex")));
                if ("LATER_APPROXIMATION".equals(selected.selection))
                    warnings.add("采用饮酒之后 " + parseTime(selected.row.get("measuredAt")).toLocalDate()
                        + " 的身体测量作近似，不能代表饮酒当天的实际身体状态。");
            }
        }
        for (Map<String, Object> session : rows(state, "sessions")) {
            if (populatedSessions.contains(string(session.get("id")))) continue;
            TimeRange time = timeRange(session, "startedAt", knownAt);
            if (time != null && time.from.isAfter(at)) continue;
            List<Map<String, Object>> issues = time != null && time.to.isBefore(scopeFrom) ? historicalMissing : missing;
            Map<String, Object> futureDetail = rows(state, "drinks").stream()
                .filter(drink -> same(drink.get("sessionId"), session.get("id")))
                .findFirst().orElse(null);
            if (futureDetail == null)
                addIssue(issues, "SESSION_DRINKS", "这个饮酒场次还缺少饮用明细（酒品和饮用量）", "sessions", session);
            else
                addIssue(issues, "DRINK_TIME", "这个场次的饮用明细时间晚于估算时点，请核对发生日期", "drinks", futureDetail);
        }
        if (!"EXACT".equals(precision)) warnings.add("APPROX".equals(precision)
            ? "饮用时间按前后约 1 小时计算多种情景，不需要回忆到分钟。"
            : "只有日期的记录按当天可能时段计算，时间范围会更宽，不能据此判断同一天行为的先后。");
        if (!historicalMissing.isEmpty()) warnings.add("另有 " + historicalMissing.size()
            + " 项较早记录资料不全，未假定为零；它们不阻止这次展示，但可能存在本模型无法计算的残余影响。");
        result.put("latestDrinkAt", latest == null ? null : latest.toString());
        result.put("latestDrinkPossibleTo", latestPossible == null ? null : latestPossible.toString());
        result.put("knownEthanolGrams", round(totalGrams, 4));
        result.put("historicalKnownDoseCount", historicalKnownDoseCount);
        result.put("historicalMissingCount", historicalMissing.size());
        result.put("timePrecision", precision);
        result.put("warnings", new ArrayList<>(warnings));
        if (!missing.isEmpty()) {
            result.put("status", "INSUFFICIENT_DATA");
            result.put("missing", missing.stream().map(issue -> string(issue.get("message"))).distinct().toList());
            return result;
        }
        if (timedDoses.isEmpty()) return result;
        List<Scenario> scenarios = new ArrayList<>();
        List<Double> durations = new ArrayList<>();
        // These clock choices are illustrative scenarios, not guaranteed extrema over all possible drinking patterns.
        for (double elimination : new double[] {0.10, 0.20}) {
            for (int absorptionMinutes : new int[] {30, 90}) {
                for (int clockChoice = 0; clockChoice < 3; clockChoice++) {
                    List<Dose> doses = new ArrayList<>();
                    for (TimedDose dose : timedDoses) {
                        LocalDateTime time = clockChoice == 0 ? dose.time.from : clockChoice == 1 ? dose.time.to
                            : clampTime(at.minusMinutes(absorptionMinutes), dose.time.from, dose.time.to);
                        doses.add(new Dose(time, dose.concentration, dose.sessionId));
                    }
                    doses.sort(Comparator.comparing(Dose::time));
                    Scenario scenario = simulate(doses, at, elimination, absorptionMinutes);
                    scenarios.add(scenario);
                    durations.add(Math.max(0, Duration.between(doses.get(doses.size() - 1).time, scenario.projectedEnd)
                        .toMillis() / 3600000.0));
                }
            }
        }
        double low = scenarios.stream().mapToDouble(Scenario::current).min().orElse(0);
        double high = scenarios.stream().mapToDouble(Scenario::current).max().orElse(0);
        LocalDateTime early = scenarios.stream().map(Scenario::projectedEnd).min(LocalDateTime::compareTo).orElse(at);
        LocalDateTime late = scenarios.stream().map(Scenario::projectedEnd).max(LocalDateTime::compareTo).orElse(at);
        result.put("status", "ESTIMATE");
        result.put("estimatedBacLowGPerL", round(BigDecimal.valueOf(low), 3));
        result.put("estimatedBacHighGPerL", round(BigDecimal.valueOf(high), 3));
        result.put("estimatedNearZeroFrom", early.truncatedTo(ChronoUnit.HOURS).toString());
        result.put("estimatedNearZeroTo", ceilHour(late).toString());
        result.put("durationHoursLow", (long) Math.floor(durations.stream().mapToDouble(Double::doubleValue).min().orElse(0)));
        result.put("durationHoursHigh", (long) Math.ceil(durations.stream().mapToDouble(Double::doubleValue).max().orElse(0)));
        result.put("absorptionMayContinue", latestPossible.plusMinutes(90).isAfter(at));
        result.put("alreadyBeyondEstimate", !late.isAfter(at));
        result.put("estimatedAt", at.toString());
        result.put("modelParameterRange", map("eliminationLowGPerLPerHour", 0.10, "eliminationHighGPerLPerHour", 0.20,
            "absorptionScenarioMinutes", List.of(30, 90), "bloodWaterFraction", 0.8, "approximateTimeHoursEitherSide", 1));
        return result;
    }

    private static void addIssue(List<Map<String, Object>> issues, String code, String message,
            String collection, Map<String, Object> row) {
        issues.add(map("code", code, "message", message, "collection", collection, "id", row.get("id"),
            "sessionId", "sessions".equals(collection) ? row.get("id") : row.get("sessionId"),
            "occurredAt", row.get("sessions".equals(collection) ? "startedAt" : "occurredAt")));
    }

    private static TimeRange timeRange(Map<String, Object> row, String field, LocalDateTime knownAt) {
        LocalDateTime time = parseTime(row.get(field));
        if (time == null) return null;
        String precision = string(row.get("timePrecision"));
        LocalDateTime from = "DATE".equals(precision) ? time.toLocalDate().atStartOfDay()
            : "APPROX".equals(precision) ? time.minusHours(1) : time;
        LocalDateTime to = "DATE".equals(precision) ? time.toLocalDate().plusDays(1).atStartOfDay().minusSeconds(1)
            : "APPROX".equals(precision) ? time.plusHours(1) : time;
        // A retrospective entry describes an event that has already happened by the observation time.
        if (knownAt != null && !from.isAfter(knownAt) && to.isAfter(knownAt)) to = knownAt;
        return new TimeRange(from, to, Set.of("DATE", "APPROX").contains(precision) ? precision : "EXACT");
    }

    private static BodySelection selectBody(Map<String, List<Map<String, Object>>> state,
            LocalDateTime drinkAt, LocalDateTime knownAt) {
        List<Map<String, Object>> body = rows(state, "body").stream()
            .filter(row -> parseTime(row.get("measuredAt")) != null
                && !parseTime(row.get("measuredAt")).isAfter(knownAt)).toList();
        // The body dashboard is maintained by date. A new date-only entry must not lose to
        // an older timed entry on that day; createdAt is only a tie-breaker after time eligibility.
        Comparator<Map<String, Object>> sameDayOrder = Comparator
            .comparing((Map<String, Object> row) -> string(row.get("createdAt")))
            .thenComparing(row -> parseTime(row.get("measuredAt")));
        Comparator<Map<String, Object>> measurementOrder = Comparator
            .comparing((Map<String, Object> row) -> parseTime(row.get("measuredAt")).toLocalDate())
            .thenComparing(sameDayOrder);
        Map<String, Object> prior = body.stream()
            .filter(row -> !parseTime(row.get("measuredAt")).isAfter(drinkAt))
            .max(measurementOrder).orElse(null);
        // Do not bypass a known ineligible age merely because another row would fit the adult equation.
        if (prior != null && positiveOrNull(prior.get("age")) != null
                && positiveOrNull(prior.get("age")).compareTo(new BigDecimal("18")) < 0)
            return new BodySelection(prior, "AT_OR_BEFORE");
        Map<String, Object> validPrior = body.stream()
            .filter(row -> !parseTime(row.get("measuredAt")).isAfter(drinkAt)
                && bodyWater(row, new ArrayList<>()) != null)
            .max(measurementOrder).orElse(null);
        if (validPrior != null) return new BodySelection(validPrior, "AT_OR_BEFORE");
        Map<String, Object> later = body.stream()
            .filter(row -> parseTime(row.get("measuredAt")).isAfter(drinkAt)
                && !parseTime(row.get("measuredAt")).isAfter(drinkAt.plusDays(30)))
            .sorted(Comparator.comparing((Map<String, Object> row) -> parseTime(row.get("measuredAt")).toLocalDate())
                .thenComparing(sameDayOrder.reversed()))
            .filter(row -> bodyWater(row, new ArrayList<>()) != null).findFirst().orElse(null);
        if (later != null) return new BodySelection(later, "LATER_APPROXIMATION");
        return prior == null ? null : new BodySelection(prior, "AT_OR_BEFORE");
    }

    private static LocalDateTime clampTime(LocalDateTime value, LocalDateTime from, LocalDateTime to) {
        return value.isBefore(from) ? from : value.isAfter(to) ? to : value;
    }

    private static LocalDateTime ceilHour(LocalDateTime value) {
        LocalDateTime floor = value.truncatedTo(ChronoUnit.HOURS);
        return value.equals(floor) ? floor : floor.plusHours(1);
    }
    private static Double bodyWater(Map<String, Object> body, List<String> missing) {
        if (body == null) { missing.add("还没有可用的身体资料：补充身高、体重、年龄及模型生理参数即可；支持饮酒后 30 天内的测量作近似"); return null; }
        BigDecimal height = positiveOrNull(body.get("heightCm"));
        BigDecimal weight = positiveOrNull(body.get("weightKg"));
        BigDecimal age = positiveOrNull(body.get("age"));
        String sex = string(body.get("sex"));
        if (height == null || weight == null || age == null || !Set.of("MALE", "FEMALE").contains(sex)) {
            missing.add("个体化模型需要身高、体重、年龄及模型生理参数");
            return null;
        }
        if (age.compareTo(new BigDecimal("18")) < 0) { missing.add("此模型仅用于成人资料，不对未成年人输出估算"); return null; }
        double water = "MALE".equals(sex)
            ? 2.447 - 0.09516 * age.doubleValue() + 0.1074 * height.doubleValue() + 0.3362 * weight.doubleValue()
            : -2.097 + 0.1069 * height.doubleValue() + 0.2466 * weight.doubleValue();
        if (!Double.isFinite(water) || water <= 0 || water >= weight.doubleValue()) {
            missing.add("身体资料超出模型可用范围"); return null;
        }
        return water;
    }

    /** Exact integration of the explicitly simplified constant-absorption / constant-elimination scenarios. */
    private static Scenario simulate(List<Dose> doses, LocalDateTime at, double elimination, int absorptionMinutes) {
        TreeMap<LocalDateTime, Double> changes = new TreeMap<>();
        changes.put(at, 0.0);
        for (Dose dose : doses) {
            double rate = dose.concentration / (absorptionMinutes / 60.0);
            changes.merge(dose.time, rate, Double::sum);
            changes.merge(dose.time.plusMinutes(absorptionMinutes), -rate, Double::sum);
        }
        List<LocalDateTime> points = new ArrayList<>(changes.keySet());
        double concentration = 0;
        double current = 0;
        double absorptionRate = changes.get(points.get(0));
        LocalDateTime lastZero = points.get(0);
        for (int i = 1; i < points.size(); i++) {
            LocalDateTime left = points.get(i - 1);
            LocalDateTime right = points.get(i);
            double hours = Duration.between(left, right).toMillis() / 3600000.0;
            double slope = absorptionRate - elimination;
            double next = concentration + slope * hours;
            if (next <= 0) {
                if (concentration > 0 && slope < 0) lastZero = left.plusNanos((long) (concentration / -slope * 3_600_000_000_000L));
                else if (absorptionRate > 0) lastZero = right;
                concentration = 0;
            } else concentration = next;
            if (right.equals(at)) current = concentration;
            absorptionRate = Math.max(0, absorptionRate + changes.get(right));
        }
        LocalDateTime end = points.get(points.size() - 1);
        if (concentration > 0) end = end.plusNanos((long) (concentration / elimination * 3_600_000_000_000L));
        else end = lastZero;
        return new Scenario(Math.max(0, current), end);
    }

    private static Map<String, Object> emptySums() {
        Map<String, Object> result = new LinkedHashMap<>();
        SUM_KEYS.forEach(key -> result.put(key, ZERO));
        return result;
    }

    private static void finalizeSums(Map<String, Object> sums) {
        BigDecimal unknown = decimal(sums.get("smokingUnknownCostCount")).add(decimal(sums.get("alcoholUnknownCostCount")));
        BigDecimal known = decimal(sums.get("smokingKnownCostCount")).add(decimal(sums.get("alcoholKnownCostCount")));
        BigDecimal consumption = decimal(sums.get("smokingCost")).add(decimal(sums.get("alcoholCost")));
        sums.put("consumptionCost", known.signum() == 0 && unknown.signum() > 0 ? null : round(consumption, 2));
        sums.put("unknownCostCount", unknown);
        if (number(sums.get("smokingKnownCostCount")) == 0 && number(sums.get("smokingUnknownCostCount")) > 0) sums.put("smokingCost", null);
        if (number(sums.get("alcoholKnownCostCount")) == 0 && number(sums.get("alcoholUnknownCostCount")) > 0) sums.put("alcoholCost", null);
        for (String[] fields : List.of(
                new String[] {"smokingTarMg", "smokingKnownTarCount", "smokingUnknownTarCount"},
                new String[] {"smokingNicotineMg", "smokingKnownNicotineCount", "smokingUnknownNicotineCount"},
                new String[] {"smokingCoMg", "smokingKnownCoCount", "smokingUnknownCoCount"},
                new String[] {"alcoholBottles", "alcoholKnownBottleCount", "alcoholUnknownBottleCount"},
                new String[] {"alcoholMl", "alcoholKnownVolumeCount", "alcoholUnknownVolumeCount"},
                new String[] {"ethanolGrams", "alcoholKnownEthanolCount", "alcoholUnknownEthanolCount"}))
            if (number(sums.get(fields[1])) == 0 && number(sums.get(fields[2])) > 0) sums.put(fields[0], null);
        for (String key : List.of("smokingCost", "alcoholCost", "paidAmount")) if (sums.get(key) != null)
            sums.put(key, round(decimal(sums.get(key)), 2));
        sums.put("ethanolEquivalent42Ml", equivalent42(decimal(sums.get("ethanolGrams"))));
        sums.put("constituentNotice", "标示烟气成分总量，不是人体吸收量；不同检测方法应分别查看。");
    }

    private static void leaveFutureUnfilled(Map<String, Object> values) {
        SUM_KEYS.forEach(key -> values.put(key, null));
        for (String key : List.of("consumptionCost", "unknownCostCount", "ethanolEquivalent42Ml")) values.put(key, null);
    }

    private static Map<String, Object> day(Map<LocalDate, Map<String, Object>> days, Object time) {
        LocalDateTime parsed = parseTime(time);
        return parsed == null ? null : days.get(parsed.toLocalDate());
    }
    private static void addKnown(Map<String, Object> target, String valueKey, String missingKey, String knownKey, Object value) {
        if (value == null) add(target, missingKey, ONE);
        else { add(target, valueKey, value); if (knownKey != null) add(target, knownKey, ONE); }
    }
    private static void add(Map<String, Object> target, String key, Object value) {
        BigDecimal number = decimal(value);
        if (number != null) target.put(key, decimal(target.getOrDefault(key, ZERO)).add(number));
    }
    private static Map<String, Object> product(Map<String, Object> row, Map<String, Object> previous,
            Map<String, List<Map<String, Object>>> state, String kind) {
        if (blank(row.get("productId"))) return Map.of();
        Map<String, Object> product = byId(state, "products", row.get("productId"));
        if (product == null && unchanged(row, previous, "productId") && previous.containsKey("snapshotName")) return Map.of();
        if (product == null) throw invalid("商品不存在");
        if (!kind.equals(string(product.get("kind")))) throw invalid("商品类型与记录类型不一致");
        return product;
    }
    private static String productName(Map<String, Object> product, String fallback) {
        List<String> parts = List.of(string(product.get("brand")).strip(), string(product.get("name")).strip(),
            string(product.get("variant")).strip()).stream().filter(part -> !part.isBlank()).toList();
        List<String> unique = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i); boolean included = false;
            for (int j = 0; j < parts.size(); j++) {
                if (j != i && parts.get(j).contains(part) && (!parts.get(j).equals(part) || j < i)) {
                    included = true; break;
                }
            }
            if (!included) unique.add(part);
        }
        return unique.isEmpty() ? fallback : String.join(" · ", unique);
    }
    private static Map<String, Object> byId(Map<String, List<Map<String, Object>>> state, String collection, Object id) {
        if (blank(id)) return null;
        return rows(state, collection).stream().filter(r -> same(r.get("id"), id)).findFirst().orElse(null);
    }
    private static List<Map<String, Object>> rows(Map<String, List<Map<String, Object>>> state, String collection) {
        if (state == null) return List.of();
        return state.getOrDefault(collection, List.of()).stream().filter(r -> !Boolean.TRUE.equals(r.get("deleted"))).toList();
    }
    private static boolean unchanged(Map<String, Object> row, Map<String, Object> previous, String... keys) {
        if (previous == null) return false;
        for (String key : keys) if (!same(row.get(key), previous.get(key))) return false;
        return true;
    }
    private static boolean same(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) return decimal(a).compareTo(decimal(b)) == 0;
        return Objects.equals(a, b) || blank(a) && blank(b);
    }
    private static void copy(Map<String, Object> from, Map<String, Object> to, Collection<String> keys) {
        for (String key : keys) to.put(key, from.get(key));
    }
    private static LocalDateTime requiredTime(Map<String, Object> row, String key) {
        LocalDateTime time = parseTime(row.get(key));
        if (time == null) throw invalid("缺少有效发生时间");
        return time;
    }
    private static LocalDateTime parseTime(Object value) {
        if (value instanceof LocalDateTime time) return time;
        if (value instanceof LocalDate day) return day.atStartOfDay();
        if (blank(value)) return null;
        try { return LocalDateTime.parse(string(value)); }
        catch (DateTimeParseException ex) {
            try { return LocalDate.parse(string(value)).atStartOfDay(); } catch (DateTimeParseException ignored) { return null; }
        }
    }
    private static LocalDate date(Object value) {
        if (value instanceof LocalDate day) return day;
        if (blank(value)) return null;
        try { return LocalDate.parse(string(value)); } catch (DateTimeParseException ex) { return null; }
    }
    private static BigDecimal requiredPositive(Map<String, Object> row, String key) {
        BigDecimal value = decimal(row.get(key));
        if (value == null || value.signum() <= 0) throw invalid(key + " 必须大于 0");
        return value;
    }
    private static void requireInteger(BigDecimal value, String label) {
        if (value.stripTrailingZeros().scale() > 0) throw invalid(label + " 必须为整数");
    }
    private static void optionalNonnegative(Map<String, Object> row, String key) {
        BigDecimal value = decimal(row.get(key));
        if (value != null && value.signum() < 0) throw invalid(key + " 不能为负数");
    }
    private static void optionalPositive(Map<String, Object> row, String key) {
        BigDecimal value = decimal(row.get(key));
        if (value != null && value.signum() <= 0) throw invalid(key + " 必须大于 0");
    }
    private static BigDecimal positiveOrNull(Object value) {
        BigDecimal number = decimal(value);
        return number != null && number.signum() > 0 ? number : null;
    }
    private static BigDecimal nonnegativeOrNull(Object value) {
        BigDecimal number = decimal(value);
        return number != null && number.signum() >= 0 ? number : null;
    }
    private static BigDecimal decimal(Object value) {
        if (value == null || value instanceof String text && text.isBlank()) return null;
        try { return value instanceof BigDecimal number ? number : new BigDecimal(value.toString()); }
        catch (NumberFormatException ex) { throw invalid("存在不合法的数值"); }
    }
    private static BigDecimal multiplyOrNull(BigDecimal a, BigDecimal b, int scale) {
        return a == null || b == null ? null : round(a.multiply(b), scale);
    }
    public static BigDecimal equivalent42(BigDecimal grams) {
        return grams == null ? null : grams.divide(ETHANOL_PER_ML_42, 4, RoundingMode.HALF_UP);
    }
    private static BigDecimal divide(BigDecimal a, BigDecimal b) { return a.divide(b, 12, RoundingMode.HALF_UP); }
    private static BigDecimal round(BigDecimal value, int scale) { return value.setScale(scale, RoundingMode.HALF_UP); }
    private static double number(Object value) { BigDecimal n = decimal(value); return n == null ? 0 : n.doubleValue(); }
    private static String string(Object value) { return value == null ? "" : value.toString(); }
    private static boolean blank(Object value) { return value == null || string(value).isBlank(); }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
    private static Map<String, Object> map(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) map.put((String) entries[i], entries[i + 1]);
        return map;
    }
}
