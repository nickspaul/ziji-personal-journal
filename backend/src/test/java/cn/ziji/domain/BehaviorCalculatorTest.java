package cn.ziji.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BehaviorCalculatorTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 21);
    private static final LocalDateTime NOW = DAY.atTime(23, 0);

    @Test void smokingUsesHistoricallyEffectiveVerifiedPricesAndActualPriority() {
        var state = state();
        state.put("prices", List.of(
            price("reference", "REFERENCE", "2026-10-20", "30", true),
            price("personal", "PERSONAL", "2026-10-20", "28", true),
            price("actual", "ACTUAL", "2026-10-01", "20", true),
            price("future", "ACTUAL", "2026-11-01", "50", true),
            price("unverified", "ACTUAL", "2026-10-21", "1", false)));
        var result = BehaviorCalculator.enrich("smoking", smoke("3"), null, state);
        assertNumber("3.00", result.get("cost"));
        assertEquals("actual", result.get("snapshotPriceSourceId"));
        assertNumber("24", result.get("tarTotalMg"));
        assertNumber("2.4", result.get("nicotineTotalMg"));
        assertEquals("标签检测", result.get("snapshotConstituentMethod"));
    }

    @Test void unknownPriceAndConstituentsRemainNullInsteadOfZero() {
        var state = new LinkedHashMap<String, List<Map<String, Object>>>();
        var input = map("occurredAt", "2026-10-21T19:00:00", "count", 2);
        var result = BehaviorCalculator.enrich("smoking", input, null, state);
        assertNull(result.get("cost"));
        assertNull(result.get("snapshotUnitPrice"));
        assertNull(result.get("tarTotalMg"));
        assertEquals("UNKNOWN", result.get("costType"));
        assertFalse(input.containsKey("cost"), "calculator must not mutate caller inputs");
    }

    @Test void unrelatedEditsAndQuantityChangesDoNotRewriteHistoricalCatalogSnapshots() {
        var state = state();
        state.put("prices", List.of(price("old", "PERSONAL", "2026-10-01", "20", true)));
        var previous = BehaviorCalculator.enrich("smoking", smoke("2"), null, state);
        state.get("products").get(0).put("name", "后来改名");
        state.get("products").get(0).put("unitCount", 10);
        state.get("products").get(0).put("tarMg", 5);
        state.put("prices", List.of(price("changed", "PERSONAL", "2026-10-01", "100", true)));
        var update = map("notes", "只改备注", "snapshotName", "伪造名称", "cost", 999);
        var unchanged = BehaviorCalculator.enrich("smoking", update, previous, state);
        assertNumber("2", unchanged.get("cost"));
        assertEquals("测试烟 · 标准", unchanged.get("snapshotName"));
        var quantity = BehaviorCalculator.enrich("smoking", map("count", 3), previous, state);
        assertNumber("3", quantity.get("cost"));
        assertNumber("24", quantity.get("tarTotalMg"));
        assertNumber("20", quantity.get("snapshotUnitCount"));
    }

    @Test void priceOverrideHasPriorityAndDateCorrectionSelectsEarlierPrice() {
        var state = state();
        state.put("prices", List.of(price("new", "ACTUAL", "2026-10-20", "20", true)));
        var input = smoke("2");
        input.put("unitPrice", new BigDecimal("1.75"));
        var overridden = BehaviorCalculator.enrich("smoking", input, null, state);
        assertNumber("3.50", overridden.get("cost"));
        assertEquals("OVERRIDE", overridden.get("costType"));
        var normal = BehaviorCalculator.enrich("smoking", smoke("2"), null, state);
        var earlier = BehaviorCalculator.enrich("smoking", map("occurredAt", "2026-10-19T20:00:00"), normal, state);
        assertNull(earlier.get("cost"));
    }

    @Test void catalogUnitPriceIgnoresPurchaseAmountsAndWhoPaid() {
        var state = state();
        state.get("products").get(0).putAll(map("packagePrice", 20, "priceConfirmed", true, "priceType", "PERSONAL"));
        var purchase = map("id", "buy", "productId", "smoke-product", "quantity", 3, "amount", new BigDecimal("50"),
            "occurredAt", "2026-10-20T12:00:00", "payer", "SELF");
        state.put("purchases", List.of(purchase));
        var input = smoke("3"); input.put("purchaseId", "buy");
        var previous = BehaviorCalculator.enrich("smoking", input, null, state);
        assertNumber("3", previous.get("cost"));
        purchase.put("amount", 100); purchase.put("payer", "OTHER");
        var edited = BehaviorCalculator.enrich("smoking", map("notes", "备注"), previous, state);
        assertNumber("3", edited.get("cost"));
        assertNull(edited.get("snapshotPurchasePayer"));
        assertNumber("3", BehaviorCalculator.enrich("smoking", input, null, state).get("cost"));
        var gift = BehaviorCalculator.enrich("smoking", map("source", "GIFT"), previous, state);
        assertNumber("0", gift.get("cost"));
        assertEquals("GIFT", gift.get("costType"));
    }

    @Test void unitOverrideDoesNotDependOnLegacyPurchaseMetadata() {
        var state = state();
        state.put("purchases", List.of(map("id", "buy", "productId", "drink-product", "quantity", 1, "amount", 10,
            "occurredAt", "2026-10-20T12:00:00", "payer", "SELF")));
        var input = smoke("1"); input.put("purchaseId", "buy"); input.put("unitPrice", 1);
        assertNumber("1", BehaviorCalculator.enrich("smoking", input, null, state).get("cost"));
        state.get("purchases").get(0).put("productId", "smoke-product");
        state.get("purchases").get(0).put("occurredAt", "2026-10-22T12:00:00");
        assertNumber("1", BehaviorCalculator.enrich("smoking", input, null, state).get("cost"));
    }

    @Test void confirmedCatalogPriceBeatsHistoryAndRecordOverrideBeatsCatalog() {
        var state = state();
        state.get("products").get(0).putAll(map("packagePrice", 30, "priceConfirmed", true, "priceType", "REFERENCE"));
        state.put("prices", List.of(price("old-actual", "ACTUAL", "2026-10-01", "10", true)));
        var result = BehaviorCalculator.enrich("smoking", smoke("2"), null, state);
        assertNumber("3", result.get("cost"));
        assertEquals("REFERENCE", result.get("costType"));
        assertEquals("smoke-product", result.get("snapshotPriceSourceId"));
        var override = smoke("2"); override.put("unitPrice", new BigDecimal("0.75"));
        var overridden = BehaviorCalculator.enrich("smoking", override, null, state);
        assertNumber("1.5", overridden.get("cost"));
        assertEquals("OVERRIDE", overridden.get("costType"));
    }

    @Test void unconfirmedCatalogPriceDoesNotResurrectLegacyConfirmedPrice() {
        var state = state();
        state.get("products").get(0).putAll(map("packagePrice", 30, "priceConfirmed", false));
        state.put("prices", List.of(price("old-actual", "ACTUAL", "2026-10-01", "10", true)));
        var result = BehaviorCalculator.enrich("smoking", smoke("2"), null, state);
        assertNull(result.get("cost"));
        assertEquals("UNKNOWN", result.get("costType"));
        state.get("products").get(0).remove("packagePrice");
        assertNull(BehaviorCalculator.enrich("smoking", smoke("2"), null, state).get("cost"));
        state.get("products").get(0).put("priceConfirmed", null);
        assertNumber("1", BehaviorCalculator.enrich("smoking", smoke("2"), null, state).get("cost"));
        state.get("products").get(0).putAll(map("packagePrice", 0, "priceConfirmed", true));
        assertNumber("0", BehaviorCalculator.enrich("smoking", smoke("2"), null, state).get("cost"));
    }

    @Test void clearingOrRevokingCatalogPriceCannotReactivateHistoryButKeepsSavedSnapshots() {
        var state = state();
        var product = state.get("products").get(0);
        product.putAll(map("packagePrice", 30, "priceConfirmed", true));
        state.put("prices", List.of(price("historic", "ACTUAL", "2026-10-01", "20", true)));
        var saved = BehaviorCalculator.enrich("smoking", smoke("2"), null, state);
        assertNumber("3", saved.get("cost"));
        product.put("priceConfirmed", false);
        assertNull(BehaviorCalculator.enrich("smoking", smoke("2"), null, state).get("cost"));
        product.put("packagePrice", null);
        assertNull(BehaviorCalculator.enrich("smoking", smoke("2"), null, state).get("cost"));
        assertNull(BehaviorCalculator.enrich("smoking", saved, null, state).get("cost"));
        assertNumber("3", BehaviorCalculator.enrich("smoking", map("notes", "保留当时单价"), saved, state).get("cost"));
        var override = smoke("2"); override.put("unitPrice", new BigDecimal("0.8"));
        assertNumber("1.6", BehaviorCalculator.enrich("smoking", override, null, state).get("cost"));
    }

    @Test void catalogChangesOnlyAffectNewOrExplicitlyRecalculatedRecords() {
        var state = state();
        state.get("products").get(0).putAll(map("packagePrice", 20, "priceConfirmed", true, "priceType", "PERSONAL"));
        var original = BehaviorCalculator.enrich("smoking", smoke("2"), null, state);
        state.get("products").get(0).put("packagePrice", 40);
        assertNumber("2", BehaviorCalculator.enrich("smoking", map("notes", "只改备注"), original, state).get("cost"));
        assertNumber("3", BehaviorCalculator.enrich("smoking", map("count", 3), original, state).get("cost"));
        assertNumber("4", BehaviorCalculator.enrich("smoking", original, null, state).get("cost"));
        // Imported snapshots from the former purchase model retain their meaning until explicit recalculation.
        original.put("snapshotPurchasePayer", "OTHER"); original.put("cost", 0);
        assertNumber("0", BehaviorCalculator.enrich("smoking", map("notes", "保留旧快照"), original, state).get("cost"));
        var recalculated = BehaviorCalculator.enrich("smoking", original, null, state);
        assertNumber("4", recalculated.get("cost"));
        assertNull(recalculated.get("snapshotPurchasePayer"));
    }

    @Test void addingConfirmedPriceFillsMissingSmokingCostWithoutRefreshingOtherSnapshots() {
        var state = state();
        var original = BehaviorCalculator.enrich("smoking", smoke("3"), null, state);
        var product = state.get("products").get(0);
        product.putAll(map("packagePrice", 53, "priceConfirmed", true, "name", "后来改名", "tarMg", 1));
        var filled = BehaviorCalculator.fillMissingCost("smoking", original, state);
        assertNumber("7.95", filled.get("cost"));
        assertNumber("2.65", filled.get("snapshotUnitPrice"));
        assertEquals(original.get("snapshotName"), filled.get("snapshotName"));
        assertEquals(original.get("snapshotTarMg"), filled.get("snapshotTarMg"));
        assertEquals(original.get("tarTotalMg"), filled.get("tarTotalMg"));
        assertEquals(original.get("occurredAt"), filled.get("occurredAt"));
        assertNull(original.get("cost"), "the input must remain unchanged");
        assertEquals(filled, BehaviorCalculator.fillMissingCost("smoking", filled, state));
        assertNumber("7.95", BehaviorCalculator.enrich("smoking", map("notes", "补记备注"), original, state).get("cost"));
    }

    @Test void missingDrinkCostUsesPersonalAmountAndDoesNotRefreshAlcoholStrength() {
        var state = state();
        var input = drink("2026-10-21T20:00:00", "3");
        input.putAll(map("inputMode", "ML", "volumeMl", 1500));
        var original = BehaviorCalculator.enrich("drinks", input, null, state);
        state.get("products").get(1).putAll(map("packagePrice", 6, "priceConfirmed", true, "abv", 42));
        var filled = BehaviorCalculator.fillMissingCost("drinks", original, state);
        assertNumber("18", filled.get("cost"));
        assertEquals(original.get("snapshotAbv"), filled.get("snapshotAbv"));
        assertEquals(original.get("ethanolGrams"), filled.get("ethanolGrams"));
        assertEquals(original.get("actualMl"), filled.get("actualMl"));
        assertNumber("18", BehaviorCalculator.enrich("drinks", map("notes", "补记备注"), original, state).get("cost"));
        var shared = new LinkedHashMap<>(original);
        shared.putAll(map("inputMode", "BOTTLES", "bottles", 2, "share", new BigDecimal("0.5")));
        assertNumber("6", BehaviorCalculator.fillMissingCost("drinks", shared, state).get("cost"));
    }

    @Test void missingCostRepairKeepsKnownFeesIncludingZeroAndPrefersSavedUnitPrices() {
        var state = state();
        state.get("products").get(0).putAll(map("packagePrice", 20, "priceConfirmed", true));
        var original = BehaviorCalculator.enrich("smoking", smoke("2"), null, state);
        state.get("products").get(0).putAll(map("packagePrice", 100, "unitCount", 10));
        assertEquals(original, BehaviorCalculator.fillMissingCost("smoking", original, state));
        var free = new LinkedHashMap<>(original); free.put("cost", ZERO());
        assertEquals(free, BehaviorCalculator.fillMissingCost("smoking", free, state));
        var missingTotal = new LinkedHashMap<>(original); missingTotal.put("cost", null);
        var filled = BehaviorCalculator.fillMissingCost("smoking", missingTotal, state);
        assertNumber("2", filled.get("cost"));
        assertEquals(original.get("snapshotUnitPrice"), filled.get("snapshotUnitPrice"));
        assertEquals(original.get("snapshotUnitCount"), filled.get("snapshotUnitCount"));
    }

    @Test void missingCostRepairPreservesManualPricesGiftsAndLegacyOtherPayers() {
        var state = state();
        var missing = BehaviorCalculator.enrich("smoking", smoke("2"), null, state);
        missing.putAll(map("unitPrice", new BigDecimal("0.75"), "productId", null));
        var filled = BehaviorCalculator.fillMissingCost("smoking", missing, state);
        assertNumber("1.5", filled.get("cost"));
        assertNumber("0.75", filled.get("unitPrice"));
        assertEquals("OVERRIDE", filled.get("costType"));
        missing.put("unitPrice", 0);
        assertNumber("0", BehaviorCalculator.fillMissingCost("smoking", missing, state).get("cost"));
        var gift = new LinkedHashMap<>(missing); gift.put("source", "GIFT");
        assertEquals(gift, BehaviorCalculator.fillMissingCost("smoking", gift, state));
        var other = new LinkedHashMap<>(missing); other.put("snapshotPurchasePayer", "OTHER");
        assertEquals(other, BehaviorCalculator.fillMissingCost("smoking", other, state));
    }

    @Test void currentPackagePriceDoesNotFillIncompatibleHistoricalSpecifications() {
        var state = state();
        var smoke = BehaviorCalculator.enrich("smoking", smoke("2"), null, state);
        var drink = BehaviorCalculator.enrich("drinks", drink("2026-10-21T20:00:00", "1"), null, state);
        state.get("products").get(0).putAll(map("packagePrice", 20, "priceConfirmed", true, "unitCount", 10));
        state.get("products").get(1).putAll(map("packagePrice", 6, "priceConfirmed", true, "bottleMl", 330));
        assertEquals(smoke, BehaviorCalculator.fillMissingCost("smoking", smoke, state));
        assertEquals(drink, BehaviorCalculator.fillMissingCost("drinks", drink, state));
        assertNull(BehaviorCalculator.enrich("smoking", map("notes", "旧规格"), smoke, state).get("cost"));
        assertNull(BehaviorCalculator.enrich("drinks", map("notes", "旧规格"), drink, state).get("cost"));
        drink.putAll(map("inputMode", "ML", "volumeMl", 500));
        assertEquals(drink, BehaviorCalculator.fillMissingCost("drinks", drink, state));
    }

    @Test void missingCostCanCompleteOnlyNecessaryMissingSpecifications() {
        var state = state();
        state.get("products").get(0).put("unitCount", null);
        state.get("products").get(1).putAll(map("bottleMl", null, "abv", null));
        var smoke = BehaviorCalculator.enrich("smoking", smoke("1"), null, state);
        var input = drink("2026-10-21T20:00:00", "1");
        input.putAll(map("inputMode", "ML", "volumeMl", 250));
        var drink = BehaviorCalculator.enrich("drinks", input, null, state);
        state.get("products").get(0).putAll(map("unitCount", 20, "packagePrice", 20, "priceConfirmed", true));
        state.get("products").get(1).putAll(map("bottleMl", 500, "packagePrice", 6, "priceConfirmed", true, "abv", 5));
        var smokeFilled = BehaviorCalculator.fillMissingCost("smoking", smoke, state);
        assertNumber("1", smokeFilled.get("cost"));
        assertNumber("20", smokeFilled.get("snapshotUnitCount"));
        var drinkFilled = BehaviorCalculator.fillMissingCost("drinks", drink, state);
        assertNumber("3", drinkFilled.get("cost"));
        assertNumber("500", drinkFilled.get("snapshotBottleMl"));
        assertNull(drinkFilled.get("snapshotAbv"));
        assertNull(drinkFilled.get("ethanolGrams"));
        assertEquals(drink.get("actualMl"), drinkFilled.get("actualMl"));
    }

    @Test void missingCostDoesNotGuessAProductOrUseUnconfirmedPrice() {
        var state = state();
        var original = BehaviorCalculator.enrich("smoking", smoke("2"), null, state);
        state.get("products").get(0).putAll(map("packagePrice", 20, "priceConfirmed", false));
        assertEquals(original, BehaviorCalculator.fillMissingCost("smoking", original, state));
        state.get("products").get(0).put("priceConfirmed", true);
        original.put("productId", null);
        assertEquals(original, BehaviorCalculator.fillMissingCost("smoking", original, state));
        original.putAll(map("productId", "smoke-product", "deleted", true));
        assertEquals(original, BehaviorCalculator.fillMissingCost("smoking", original, state));
    }

    @Test void newProductNameSnapshotsDeduplicateContainedNamesWithoutChangingExistingNames() {
        var state = state();
        var product = state.get("products").get(0);
        product.putAll(map("brand", "南京", "name", "南京(雨花石)", "variant", "南京(雨花石)"));
        var saved = BehaviorCalculator.enrich("smoking", smoke("1"), null, state);
        assertEquals("南京(雨花石)", saved.get("snapshotName"));
        product.putAll(map("brand", "青岛", "name", "经典", "variant", "500 ml"));
        assertEquals("青岛 · 经典 · 500 ml", BehaviorCalculator.enrich("smoking", smoke("1"), null, state).get("snapshotName"));
        assertEquals("南京(雨花石)", BehaviorCalculator.enrich("smoking", map("notes", "保留旧名"), saved, state).get("snapshotName"));
    }

    @Test void perCigaretteAllocationIsRoundedOnlyAfterAggregation() {
        var state = state();
        state.put("prices", List.of(price("pack", "PERSONAL", "2026-10-01", "9.90", true)));
        List<Map<String, Object>> cigarettes = new ArrayList<>();
        for (int i = 0; i < 20; i++) cigarettes.add(BehaviorCalculator.enrich("smoking", smoke("1"), null, state));
        assertNumber("0.495", cigarettes.get(0).get("cost"));
        state.put("smoking", cigarettes);
        var totals = nested(BehaviorCalculator.analytics(state, Map.of(), DAY, DAY, NOW), "totals");
        assertNumber("9.90", totals.get("smokingCost"));
    }

    @Test void bottlesAndMillilitresNeverDoubleCountAndShareIsPersonalIntake() {
        var state = state();
        var bottles = drink("2026-10-21T20:00:00", "2");
        bottles.put("share", new BigDecimal("0.5")); bottles.put("volumeMl", 9999); bottles.put("unitPrice", 10);
        var result = BehaviorCalculator.enrich("drinks", bottles, null, state);
        assertNumber("500", result.get("actualMl"));
        assertNumber("19.725", result.get("ethanolGrams"));
        assertNumber("59.5238", result.get("ethanolEquivalent42Ml"));
        assertNumber("10", result.get("cost"));
        assertNumber("1", result.get("equivalentBottles"));
        var ml = map("inputMode", "ML", "volumeMl", 150);
        var changed = BehaviorCalculator.enrich("drinks", ml, result, state);
        assertNumber("150", changed.get("actualMl"));
        assertNumber("5.9175", changed.get("ethanolGrams"));
        assertNumber("17.8571", changed.get("ethanolEquivalent42Ml"));
        assertNumber("3", changed.get("cost"));
    }

    @Test void zeroAlcoholIsKnownButUnknownStrengthIsNotInvented() {
        var state = state();
        var zero = drink("2026-10-21T20:00:00", "1"); zero.put("abv", ZERO());
        var nonAlcoholic = BehaviorCalculator.enrich("drinks", zero, null, state);
        assertNumber("0", nonAlcoholic.get("ethanolGrams"));
        assertNumber("0", nonAlcoholic.get("ethanolEquivalent42Ml"));
        var unknown = drink("2026-10-21T20:00:00", "1"); unknown.remove("productId");
        var result = BehaviorCalculator.enrich("drinks", unknown, null, state);
        assertNull(result.get("actualMl")); assertNull(result.get("ethanolGrams")); assertNull(result.get("cost"));
        assertNull(result.get("ethanolEquivalent42Ml"));
    }

    @Test void drinksUseCatalogBottlePriceForPersonalMillilitresAndRetainPriceSnapshots() {
        var state = state();
        state.get("products").get(1).putAll(map("packagePrice", 12, "priceConfirmed", true, "priceType", "PERSONAL"));
        var input = drink("2026-10-21T20:00:00", "1");
        input.putAll(map("inputMode", "ML", "volumeMl", 125));
        var original = BehaviorCalculator.enrich("drinks", input, null, state);
        assertNumber("3", original.get("cost"));
        state.get("products").get(1).put("packagePrice", 20);
        assertNumber("6", BehaviorCalculator.enrich("drinks", map("volumeMl", 250), original, state).get("cost"));
        assertNumber("5", BehaviorCalculator.enrich("drinks", original, null, state).get("cost"));
        assertNumber("2", BehaviorCalculator.enrich("drinks", map("unitPrice", 8), original, state).get("cost"));
    }

    @Test void fortyTwoDegreeEquivalentIsComputedFromAggregatedKnownEthanol() {
        var state = state();
        var spirit = drink("2026-10-21T20:00:00", "1");
        spirit.putAll(map("inputMode", "ML", "volumeMl", 100, "abv", 42));
        var row = BehaviorCalculator.enrich("drinks", spirit, null, state);
        assertNumber("33.138", row.get("ethanolGrams"));
        assertNumber("100", row.get("ethanolEquivalent42Ml"));
        var beer = BehaviorCalculator.enrich("drinks", drink("2026-10-21T21:00:00", "1"), null, state);
        state.put("drinks", List.of(row, beer));
        var report = BehaviorCalculator.analytics(state, Map.of(), DAY, DAY, NOW);
        assertNumber("159.5238", nested(report, "totals").get("ethanolEquivalent42Ml"));
        assertNumber("159.5238", list(report.get("daily")).get(0).get("ethanolEquivalent42Ml"));
        var unknown = drink("2026-10-21T22:00:00", "1"); unknown.remove("productId");
        state.put("drinks", List.of(row, BehaviorCalculator.enrich("drinks", unknown, null, state)));
        var partial = nested(BehaviorCalculator.analytics(state, Map.of(), DAY, DAY, NOW), "totals");
        assertNumber("100", partial.get("ethanolEquivalent42Ml"));
        assertNumber("1", partial.get("alcoholUnknownEthanolCount"));
    }

    @Test void emptyAlcoholDaysDefaultToZeroWithoutRequiringConfirmation() {
        var state = state();
        var empty = BehaviorCalculator.analytics(state, Map.of(), DAY, DAY, NOW);
        assertNumber("0", nested(empty, "totals").get("ethanolEquivalent42Ml"));
        assertNumber("0", list(empty.get("daily")).get(0).get("ethanolEquivalent42Ml"));
    }

    @Test void drinkCatalogChangesDoNotRewriteOldStrengthOrCapacityOnQuantityEdit() {
        var state = state();
        var original = BehaviorCalculator.enrich("drinks", drink("2026-10-21T20:00:00", "1"), null, state);
        state.get("products").get(1).put("abv", 50);
        state.get("products").get(1).put("bottleMl", 1000);
        var edited = BehaviorCalculator.enrich("drinks", map("bottles", 2), original, state);
        assertNumber("1000", edited.get("actualMl"));
        assertNumber("39.45", edited.get("ethanolGrams"));
        assertNumber("5", edited.get("snapshotAbv"));
    }

    @Test void invalidQuantitiesAndAlcoholStrengthFailClearly() {
        var state = state();
        assertThrows(IllegalArgumentException.class, () -> BehaviorCalculator.enrich("smoking", smoke("1.5"), null, state));
        assertThrows(IllegalArgumentException.class, () -> BehaviorCalculator.enrich("smoking", smoke("-1"), null, state));
        var input = drink("2026-10-21T20:00:00", "1"); input.put("share", 2);
        assertThrows(IllegalArgumentException.class, () -> BehaviorCalculator.enrich("drinks", input, null, state));
        input.put("share", 1); input.put("abv", 101);
        assertThrows(IllegalArgumentException.class, () -> BehaviorCalculator.enrich("drinks", input, null, state));
    }

    @Test void unrecordedPastAndCurrentDaysAreZeroWithoutInventingConfirmations() {
        var state = state();
        var report = BehaviorCalculator.analytics(state, Map.of(), DAY.minusDays(1), DAY, NOW);
        var daily = list(report.get("daily"));
        for (var day : daily) {
            for (String field : List.of("smokingCount", "alcoholSessions", "alcoholMl", "intimacyCount", "ethanolEquivalent42Ml", "consumptionCost"))
                assertNumber("0", day.get(field));
            assertEquals(false, day.get("isFuture"));
        }
        assertFalse(daily.get(0).containsKey("completeness"));
        assertFalse(nested(report, "totals").containsKey("completeDays"));
        assertFalse(state.containsKey("completeness"));
        assertFalse(state.containsKey("smoking"));
        assertFalse(report.get("warnings").toString().contains("尚未确认"));
        assertEquals("UNRECORDED_AS_ZERO", report.get("recordingPolicy"));
    }

    @Test void backfilledBehaviorReplacesDefaultZeroButMissingPriceRemainsUnknown() {
        var state = state();
        var before = BehaviorCalculator.analytics(state, Map.of(), DAY.minusDays(1), DAY, NOW);
        assertNumber("0", list(before.get("daily")).get(1).get("smokingCount"));
        var unknownPrice = smoke("3"); unknownPrice.remove("productId");
        state.put("smoking", List.of(BehaviorCalculator.enrich("smoking", unknownPrice, null, state)));
        var after = BehaviorCalculator.analytics(state, Map.of(), DAY.minusDays(1), DAY, NOW);
        var daily = list(after.get("daily"));
        assertNumber("0", daily.get(0).get("smokingCount"));
        assertNumber("0", daily.get(0).get("consumptionCost"));
        assertNumber("3", daily.get(1).get("smokingCount"));
        assertNull(daily.get(1).get("smokingCost"));
        assertNull(daily.get(1).get("consumptionCost"));
        assertNumber("1", daily.get(1).get("smokingUnknownCostCount"));
        assertNumber("0", daily.get(1).get("alcoholMl"));
    }

    @Test void futureDatesStayUnfilledAndFutureRecordsDoNotEnterOccurredTotals() {
        var state = state();
        state.put("smoking", List.of(
            map("id", "past", "occurredAt", "2026-10-21T11:00:00", "count", 2, "cost", 3),
            map("id", "future-time", "occurredAt", "2026-10-21T23:30:00", "count", 100, "cost", 100),
            map("id", "future-day", "occurredAt", "2026-10-22T11:00:00", "count", 100, "cost", 100)));
        state.put("sessions", List.of(map("id", "future-session", "startedAt", "2026-10-22T11:00:00")));
        var report = BehaviorCalculator.analytics(state, Map.of(), DAY, DAY.plusDays(1), NOW);
        var today = list(report.get("daily")).get(0);
        var future = list(report.get("daily")).get(1);
        assertEquals(true, future.get("isFuture"));
        for (String field : List.of("smokingCount", "alcoholSessions", "alcoholMl", "intimacyCount", "ethanolEquivalent42Ml", "consumptionCost"))
            assertNull(future.get(field), field);
        assertNumber("2", today.get("smokingCount"));
        assertNumber("0", today.get("alcoholMl"));
        assertNumber("2", nested(report, "totals").get("smokingCount"));
        assertNumber("3", nested(report, "totals").get("consumptionCost"));
        assertNumber("1", nested(report, "totals").get("elapsedDays"));
        var onlyFuture = BehaviorCalculator.analytics(state, Map.of(), DAY.plusDays(1), DAY.plusDays(1), NOW);
        assertNull(nested(onlyFuture, "totals").get("smokingCount"));
        assertNull(nested(onlyFuture, "totals").get("consumptionCost"));
        assertNumber("0", nested(onlyFuture, "totals").get("elapsedDays"));
    }

    @Test void knownSubtotalsIncludeMissingCountsAndDoNotMixPaymentWithConsumption() {
        var state = state();
        var known = smoke("2"); known.put("unitPrice", new BigDecimal("1.25"));
        var unknown = smoke("3"); unknown.remove("productId");
        state.put("smoking", List.of(BehaviorCalculator.enrich("smoking", known, null, state),
            BehaviorCalculator.enrich("smoking", unknown, null, state)));
        state.put("purchases", List.of(map("occurredAt", "2026-10-21T10:00:00", "amount", 100, "payer", "SELF"),
            map("occurredAt", "2026-10-21T11:00:00", "amount", 200, "payer", "OTHER")));
        var totals = nested(BehaviorCalculator.analytics(state, Map.of(), DAY, DAY, NOW), "totals");
        assertNumber("5", totals.get("smokingCount"));
        assertNumber("2.50", totals.get("smokingCost"));
        assertNumber("1", totals.get("smokingUnknownCostCount"));
        assertNumber("100", totals.get("paidAmount"));
        assertNumber("2.50", totals.get("consumptionCost"));
    }

    @Test void entirelyUnknownAlcoholAmountStaysNullInTotals() {
        var state = state();
        var unknown = drink("2026-10-21T20:00:00", "1"); unknown.remove("productId");
        state.put("drinks", List.of(BehaviorCalculator.enrich("drinks", unknown, null, state)));
        var totals = nested(BehaviorCalculator.analytics(state, Map.of(), DAY, DAY, NOW), "totals");
        assertNull(totals.get("ethanolGrams"));
        assertNull(totals.get("ethanolEquivalent42Ml"));
        assertNull(totals.get("alcoholMl"));
        assertNull(totals.get("consumptionCost"));
        assertNumber("1", totals.get("alcoholUnknownEthanolCount"));
    }

    @Test void differentConstituentMethodsAreSeparatedAndMissingCoverageRemainsVisible() {
        var state = state();
        var first = BehaviorCalculator.enrich("smoking", smoke("2"), null, state);
        state.get("products").get(0).put("constituentMethod", "另一检测口径");
        state.get("products").get(0).put("tarMg", 5);
        state.get("products").get(0).put("nicotineMg", null);
        var second = BehaviorCalculator.enrich("smoking", smoke("3"), null, state);
        state.put("smoking", List.of(first, second));
        var totals = nested(BehaviorCalculator.analytics(state, Map.of(), DAY, DAY, NOW), "totals");
        assertEquals(true, totals.get("constituentMixedMethods"));
        assertNull(totals.get("smokingTarMg"));
        var groups = list(totals.get("tobaccoByMethod"));
        assertEquals(2, groups.size());
        assertNumber("16", groups.get(0).get("tarMg"));
        assertNumber("15", groups.get(1).get("tarMg"));
        assertNull(groups.get(1).get("nicotineMg"));
        assertNumber("0", groups.get(1).get("nicotineCoveredCount"));
    }

    @Test void overlappingSessionsShareOneEliminationTimeline() {
        var combined = stateWithBody();
        var first = drink("2026-10-21T20:00:00", "4"); first.put("id", "first"); first.put("sessionId", "s1");
        var second = drink("2026-10-21T21:00:00", "2"); second.put("id", "second"); second.put("sessionId", "s2");
        combined.put("drinks", List.of(BehaviorCalculator.enrich("drinks", first, null, combined),
            BehaviorCalculator.enrich("drinks", second, null, combined)));
        var both = nested(BehaviorCalculator.analytics(combined, Map.of(), DAY, DAY, NOW), "alcohol");
        var onlyLater = stateWithBody(); onlyLater.put("drinks", List.of(combined.get("drinks").get(1)));
        var later = nested(BehaviorCalculator.analytics(onlyLater, Map.of(), DAY, DAY, NOW), "alcohol");
        assertEquals("ESTIMATE", both.get("status"));
        assertTrue(number(both.get("estimatedBacHighGPerL")) > number(later.get("estimatedBacHighGPerL")));
        assertTrue(LocalDateTime.parse(both.get("estimatedNearZeroTo").toString())
            .isAfter(LocalDateTime.parse(later.get("estimatedNearZeroTo").toString())));
        assertTrue(both.get("disclaimer").toString().contains("不能证明"));
    }

    @Test void unknownBodyNeedsMeasurementButDateOnlyRecordsProduceExplicitlyCoarseScenarios() {
        var state = state();
        state.put("drinks", List.of(BehaviorCalculator.enrich("drinks", drink("2026-10-21T20:00:00", "1"), null, state)));
        var noBody = nested(BehaviorCalculator.analytics(state, Map.of(), DAY, DAY, NOW), "alcohol");
        assertEquals("INSUFFICIENT_DATA", noBody.get("status")); assertNull(noBody.get("estimatedNearZeroTo"));
        state.put("body", stateWithBody().get("body"));
        state.get("drinks").get(0).put("timePrecision", "DATE");
        var dateOnly = nested(BehaviorCalculator.analytics(state, Map.of(), DAY, DAY, NOW), "alcohol");
        assertEquals("ESTIMATE", dateOnly.get("status"));
        assertEquals("DATE", dateOnly.get("timePrecision"));
        assertTrue(dateOnly.get("warnings").toString().contains("范围会更宽"));
        assertTrue(number(dateOnly.get("estimatedBacHighGPerL")) > 0);
        assertTrue(number(dateOnly.get("durationHoursHigh")) >= number(dateOnly.get("durationHoursLow")));
        assertEquals(0, LocalDateTime.parse(dateOnly.get("estimatedNearZeroTo").toString()).getMinute());
    }

    @Test void crossMidnightBackfillRecomputesLinksAndManualOverrideKeepsAutoEvidence() {
        var state = stateWithBody();
        var event = map("id", "intimacy", "occurredAt", "2026-10-22T00:30:00", "timePrecision", "EXACT", "count", 1, "manualAlcohol", "AUTO");
        state.put("intimacy", List.of(event));
        var initial = link(state, DAY.plusDays(1));
        assertEquals("NO_RECORDED_RECENT_DRINKING", initial.get("status"));
        var late = drink("2026-10-21T23:00:00", "3"); late.put("sessionId", "party");
        state.put("drinks", List.of(BehaviorCalculator.enrich("drinks", late, null, state)));
        var backfilled = link(state, DAY.plusDays(1));
        assertEquals("POSSIBLE_AFTER_DRINKING", backfilled.get("status"));
        assertEquals("YES", backfilled.get("effective"));
        event.put("manualAlcohol", "NO"); event.put("overrideReason", "人工修正");
        var manual = link(state, DAY.plusDays(1));
        assertEquals("POSSIBLE_AFTER_DRINKING", manual.get("status"));
        assertEquals("YES", manual.get("autoEffective"));
        assertEquals("NO", manual.get("effective"));
        assertEquals("MANUAL", manual.get("source"));
        assertEquals("人工修正", manual.get("overrideReason"));
    }

    @Test void drinkingAfterIntimacyDoesNotRetroactivelyMakeItAlcoholRelated() {
        var state = stateWithBody();
        state.put("intimacy", List.of(map("id", "intimacy", "occurredAt", "2026-10-21T19:00:00", "count", 1)));
        state.put("drinks", List.of(BehaviorCalculator.enrich("drinks", drink("2026-10-21T20:00:00", "3"), null, state)));
        assertEquals("NO_RECORDED_RECENT_DRINKING", link(state, DAY).get("status"));
    }

    @Test void dateOnlyDrinkOnSameDayCannotBeOrderedUsingItsPlaceholderClock() {
        var state = stateWithBody();
        state.put("intimacy", List.of(map("id", "intimacy", "occurredAt", "2026-10-21T19:00:00", "count", 1)));
        var input = drink("2026-10-21T23:59:00", "1"); input.put("timePrecision", "DATE");
        state.put("drinks", List.of(BehaviorCalculator.enrich("drinks", input, null, state)));
        var association = link(state, DAY);
        assertEquals("INSUFFICIENT_DATA", association.get("status"));
        assertEquals("UNKNOWN", association.get("effective"));
        var atSeven = nested(BehaviorCalculator.analytics(state, Map.of(), DAY, DAY, DAY.atTime(19, 0)), "alcohol");
        assertEquals("ESTIMATE", atSeven.get("status"));
        assertEquals("DATE", atSeven.get("timePrecision"));
        assertTrue(atSeven.get("warnings").toString().contains("不能据此判断"));
    }

    @Test void emptyDrinkingSessionIsMissingInformationNotAZeroDose() {
        var state = stateWithBody();
        state.put("sessions", List.of(map("id", "unfinished", "startedAt", "2026-10-21T20:00:00")));
        var report = BehaviorCalculator.analytics(state, Map.of(), DAY, DAY, NOW);
        var alcohol = nested(report, "alcohol");
        assertEquals("INSUFFICIENT_DATA", alcohol.get("status"));
        assertTrue(alcohol.get("missing").toString().contains("饮用明细"));
        assertNull(nested(report, "totals").get("ethanolEquivalent42Ml"));
        assertNumber("1", nested(report, "totals").get("alcoholUnknownEthanolCount"));
        assertNull(list(report.get("daily")).get(0).get("ethanolEquivalent42Ml"));
    }

    @Test void onlyNonAlcoholicDrinksDoNotProducePositiveAlcoholLink() {
        var state = stateWithBody();
        state.put("sessions", List.of(map("id", "session", "startedAt", "2026-10-21T18:00:00", "endedAt", "2026-10-21T21:00:00")));
        state.put("intimacy", List.of(map("id", "intimacy", "occurredAt", "2026-10-21T19:00:00", "count", 1)));
        var input = drink("2026-10-21T18:30:00", "1"); input.put("abv", 0);
        state.put("drinks", List.of(BehaviorCalculator.enrich("drinks", input, null, state)));
        assertNotEquals("YES", link(state, DAY).get("effective"));
    }

    @Test void missingBodyStillReportsRecentDrinkWithoutPretendingToKnowItsClearance() {
        var state = state();
        state.put("intimacy", List.of(map("id", "intimacy", "occurredAt", "2026-10-21T22:00:00", "count", 1)));
        state.put("drinks", List.of(BehaviorCalculator.enrich("drinks", drink("2026-10-21T20:00:00", "1"), null, state)));
        var link = link(state, DAY);
        assertEquals("RECENT_RECORDED_DRINKING", link.get("status"));
        assertEquals("UNKNOWN", link.get("effective"));
        assertTrue(link.get("reason").toString().contains("资料不足"));
    }

    @Test void oldBodyMeasurementIsUsedInsteadOfLaterWeightAndDeletedRecordsAreExcluded() {
        var state = stateWithBody();
        var drink = BehaviorCalculator.enrich("drinks", drink("2026-10-21T20:00:00", "3"), null, state);
        state.put("drinks", new ArrayList<>(List.of(drink)));
        var before = nested(BehaviorCalculator.analytics(state, Map.of(), DAY, DAY, NOW), "alcohol");
        var laterBody = map("measuredAt", "2026-10-22T12:00:00", "heightCm", 180, "weightKg", 120, "age", 30, "sex", "MALE");
        state.get("body").add(laterBody);
        var after = nested(BehaviorCalculator.analytics(state, Map.of(), DAY, DAY, NOW), "alcohol");
        assertEquals(before.get("estimatedBacHighGPerL"), after.get("estimatedBacHighGPerL"));
        drink.put("deleted", true);
        var deleted = nested(BehaviorCalculator.analytics(state, Map.of(), DAY, DAY, NOW), "totals");
        assertNumber("0", deleted.get("ethanolGrams"));
    }

    @Test void noAlcoholOrOnlyZeroAlcoholReturnsNoInventedEstimate() {
        var state = stateWithBody();
        var input = drink("2026-10-21T20:00:00", "1"); input.put("abv", 0);
        state.put("drinks", List.of(BehaviorCalculator.enrich("drinks", input, null, state)));
        var alcohol = nested(BehaviorCalculator.analytics(state, Map.of(), DAY, DAY, NOW), "alcohol");
        assertEquals("NO_RECENT_RECORDS", alcohol.get("status"));
        assertNull(alcohol.get("estimatedNearZeroTo"));
    }

    @Test void bodyMeasuredAfterBackfilledDrinkingCanBeUsedWithAnExplicitApproximationNotice() {
        var state = state();
        state.put("drinks", List.of(BehaviorCalculator.enrich("drinks", drink("2026-10-21T20:00:00", "2"), null, state)));
        state.put("body", List.of(map("id", "later-body", "measuredAt", "2026-10-23T12:00:00",
            "heightCm", 175, "weightKg", 70, "age", 30, "sex", "MALE")));
        var alcohol = nested(BehaviorCalculator.analytics(state, Map.of(), DAY, DAY, DAY.plusDays(3).atTime(12, 0)), "alcohol");
        assertEquals("ESTIMATE", alcohol.get("status"));
        assertTrue(alcohol.get("warnings").toString().contains("2026-10-23"));
        assertTrue(alcohol.get("warnings").toString().contains("不能代表"));
        var measurement = list(alcohol.get("bodyMeasurements")).get(0);
        assertEquals("LATER_APPROXIMATION", measurement.get("selection"));
        assertEquals("later-body", measurement.get("id"));
        assertEquals("2026-10-23T12:00:00", state.get("body").get(0).get("measuredAt"));
    }

    @Test void futureMeasurementsOrMeasurementsMoreThanThirtyDaysLaterAreNotUsed() {
        var state = state();
        state.put("drinks", List.of(BehaviorCalculator.enrich("drinks", drink("2026-10-21T20:00:00", "2"), null, state)));
        state.put("body", List.of(map("id", "future-body", "measuredAt", "2026-10-23T12:00:00",
            "heightCm", 175, "weightKg", 70, "age", 30, "sex", "MALE")));
        assertEquals("INSUFFICIENT_DATA", nested(BehaviorCalculator.analytics(state, Map.of(), DAY, DAY, NOW), "alcohol").get("status"));
        state.get("body").get(0).put("measuredAt", "2026-11-22T12:00:00");
        var alcohol = nested(BehaviorCalculator.analytics(state, Map.of(), DAY, DAY, DAY.plusDays(40).atTime(12, 0)), "alcohol");
        assertEquals("INSUFFICIENT_DATA", alcohol.get("status"));
        assertEquals("BODY_PARAMETERS", list(alcohol.get("missingDetails")).get(0).get("code"));
    }

    @Test void newestEntryOnTheSameMeasurementDateWinsWithoutUsingCreatedAtAsMeasurementTime() {
        var state = state();
        var oldTimed = map("id", "old-timed", "measuredAt", "2026-10-21T08:00:00", "createdAt", "2026-10-21T08:30:00",
            "heightCm", 175, "weightKg", 70, "age", 30, "sex", "MALE");
        var newDaily = map("id", "new-daily", "measuredAt", "2026-10-21T00:00:00", "createdAt", "2026-10-21T22:30:00",
            "heightCm", 175, "weightKg", 80, "age", 30, "sex", "MALE");
        var future = map("id", "future", "measuredAt", "2026-10-21T23:30:00", "createdAt", "2026-10-21T22:45:00",
            "heightCm", 175, "weightKg", 90, "age", 30, "sex", "MALE");
        state.put("body", List.of(oldTimed, newDaily, future));
        state.put("drinks", List.of(BehaviorCalculator.enrich("drinks", drink("2026-10-21T20:00:00", "2"), null, state)));
        var prior = nested(BehaviorCalculator.analytics(state, Map.of(), DAY, DAY, NOW), "alcohol");
        var priorMeasurement = list(prior.get("bodyMeasurements")).get(0);
        assertEquals("new-daily", priorMeasurement.get("id"));
        assertEquals("AT_OR_BEFORE", priorMeasurement.get("selection"));
        assertEquals("2026-10-21T00:00:00", priorMeasurement.get("measuredAt"));

        state.put("drinks", List.of(BehaviorCalculator.enrich("drinks", drink("2026-10-20T20:00:00", "2"), null, state)));
        var later = nested(BehaviorCalculator.analytics(state, Map.of(), DAY.minusDays(1), DAY, NOW), "alcohol");
        var laterMeasurement = list(later.get("bodyMeasurements")).get(0);
        assertEquals("new-daily", laterMeasurement.get("id"));
        assertEquals("LATER_APPROXIMATION", laterMeasurement.get("selection"));
        assertEquals("2026-10-21T00:00:00", newDaily.get("measuredAt"));
        assertEquals("2026-10-21T08:00:00", oldTimed.get("measuredAt"));
    }

    @Test void approximateDrinkingTimeWidensScenariosWithoutRequiringMinutePrecision() {
        var state = stateWithBody();
        var exact = drink("2026-10-21T20:00:00", "3");
        state.put("drinks", List.of(BehaviorCalculator.enrich("drinks", exact, null, state)));
        var precise = nested(BehaviorCalculator.analytics(state, Map.of(), DAY, DAY, NOW), "alcohol");
        state.get("drinks").get(0).put("timePrecision", "APPROX");
        var approximate = nested(BehaviorCalculator.analytics(state, Map.of(), DAY, DAY, NOW), "alcohol");
        assertEquals("ESTIMATE", approximate.get("status"));
        assertEquals("APPROX", approximate.get("timePrecision"));
        assertTrue(approximate.get("warnings").toString().contains("不需要回忆到分钟"));
        assertTrue(LocalDateTime.parse(approximate.get("estimatedNearZeroFrom").toString())
            .isBefore(LocalDateTime.parse(precise.get("estimatedNearZeroFrom").toString())));
        assertTrue(LocalDateTime.parse(approximate.get("estimatedNearZeroTo").toString())
            .isAfter(LocalDateTime.parse(precise.get("estimatedNearZeroTo").toString())));
    }

    @Test void approximateTimesThatOverlapDoNotBecomeDefiniteAlcoholRelatedEvents() {
        var state = stateWithBody();
        var approximate = drink("2026-10-21T20:00:00", "3"); approximate.put("timePrecision", "APPROX");
        state.put("drinks", List.of(BehaviorCalculator.enrich("drinks", approximate, null, state)));
        state.put("intimacy", List.of(map("id", "intimacy", "occurredAt", "2026-10-21T19:30:00", "timePrecision", "EXACT", "count", 1)));
        var link = link(state, DAY);
        assertEquals("RECENT_RECORDED_DRINKING", link.get("status"));
        assertEquals("UNKNOWN", link.get("effective"));
        assertTrue(link.get("reason").toString().contains("无法确定先后"));
        state.get("intimacy").get(0).put("occurredAt", "2026-10-21T22:30:00");
        assertEquals("POSSIBLE_AFTER_DRINKING", link(state, DAY).get("status"));
    }

    @Test void remoteEmptySessionIsListedForRepairWithoutBlockingLatestDrinkingEstimate() {
        var state = stateWithBody();
        state.put("sessions", List.of(map("id", "old-empty", "startedAt", "2026-09-01T20:00:00")));
        state.put("drinks", List.of(BehaviorCalculator.enrich("drinks", drink("2026-10-21T20:00:00", "3"), null, state)));
        var alcohol = nested(BehaviorCalculator.analytics(state, Map.of(), DAY, DAY, NOW), "alcohol");
        assertEquals("ESTIMATE", alcohol.get("status"));
        assertNumber("1", alcohol.get("historicalMissingCount"));
        var issue = list(alcohol.get("historicalMissingDetails")).get(0);
        assertEquals("SESSION_DRINKS", issue.get("code"));
        assertEquals("sessions", issue.get("collection"));
        assertEquals("old-empty", issue.get("sessionId"));
        assertTrue(alcohol.get("warnings").toString().contains("未假定为零"));
        assertTrue(nested(alcohol, "scope").get("notice").toString().contains("不是代谢界限"));
    }

    @Test void nearbyEmptySessionStillBlocksAndIdentifiesTheExactRecordToRepair() {
        var state = stateWithBody();
        state.put("sessions", List.of(map("id", "unfinished", "startedAt", "2026-10-21T19:00:00")));
        state.put("drinks", List.of(BehaviorCalculator.enrich("drinks", drink("2026-10-21T20:00:00", "3"), null, state)));
        var alcohol = nested(BehaviorCalculator.analytics(state, Map.of(), DAY, DAY, NOW), "alcohol");
        assertEquals("INSUFFICIENT_DATA", alcohol.get("status"));
        assertEquals("unfinished", list(alcohol.get("missingDetails")).get(0).get("id"));
        assertNull(alcohol.get("estimatedNearZeroTo"));
    }

    @Test void fillingMissingBottleSizeAndStrengthRebuildsTheMissingDoseAndClearsItsRepairIssue() {
        var state = stateWithBody();
        state.get("products").get(1).put("bottleMl", null);
        state.get("products").get(1).put("abv", null);
        var input = drink("2026-10-21T20:00:00", "2"); input.put("id", "repair-dose");
        var incomplete = BehaviorCalculator.enrich("drinks", input, null, state);
        state.put("drinks", List.of(incomplete));
        var before = nested(BehaviorCalculator.analytics(state, Map.of(), DAY, DAY, NOW), "alcohol");
        assertEquals("INSUFFICIENT_DATA", before.get("status"));
        assertEquals("DRINK_AMOUNT", list(before.get("missingDetails")).get(0).get("code"));
        assertNull(incomplete.get("ethanolGrams"));
        var repair = new LinkedHashMap<>(incomplete);
        repair.put("bottleMl", 500); repair.put("abv", 5);
        var repaired = BehaviorCalculator.enrich("drinks", repair, incomplete, state);
        state.put("drinks", List.of(repaired));
        assertNumber("1000", repaired.get("actualMl"));
        assertNumber("39.45", repaired.get("ethanolGrams"));
        var after = nested(BehaviorCalculator.analytics(state, Map.of(), DAY, DAY, NOW), "alcohol");
        assertEquals("ESTIMATE", after.get("status"));
        assertTrue(list(after.get("missingDetails")).isEmpty());
    }

    @Test void futureDatedDetailCannotTurnAnAlreadyStartedSessionIntoAZeroDose() {
        var state = stateWithBody();
        state.put("sessions", List.of(map("id", "session", "startedAt", "2026-10-21T19:00:00")));
        var input = drink("2026-10-22T20:00:00", "3"); input.put("id", "wrong-date");
        state.put("drinks", List.of(BehaviorCalculator.enrich("drinks", input, null, state)));
        var alcohol = nested(BehaviorCalculator.analytics(state, Map.of(), DAY, DAY, NOW), "alcohol");
        assertEquals("INSUFFICIENT_DATA", alcohol.get("status"));
        var issue = list(alcohol.get("missingDetails")).get(0);
        assertEquals("wrong-date", issue.get("id"));
        assertEquals("DRINK_TIME", issue.get("code"));
    }

    @Test void knownEarlierLargeDosesStillCarryThroughTheSharedModelBeyondTheDisplayScope() {
        var state = stateWithBody();
        var previous = drink("2026-10-17T20:00:00", "30"); previous.put("id", "old-dose");
        var current = drink("2026-10-21T20:00:00", "1"); current.put("id", "current-dose");
        var earlierDose = BehaviorCalculator.enrich("drinks", previous, null, state);
        var currentDose = BehaviorCalculator.enrich("drinks", current, null, state);
        state.put("drinks", List.of(earlierDose, currentDose));
        var combined = nested(BehaviorCalculator.analytics(state, Map.of(), DAY, DAY, NOW), "alcohol");
        state.put("drinks", List.of(currentDose));
        var onlyCurrent = nested(BehaviorCalculator.analytics(state, Map.of(), DAY, DAY, NOW), "alcohol");
        assertNumber("1", combined.get("historicalKnownDoseCount"));
        assertTrue(number(combined.get("estimatedBacHighGPerL")) > number(onlyCurrent.get("estimatedBacHighGPerL")));
    }

    @Test void historicalAssociationsCanUseAlreadyMeasuredLaterBodyDataButStillShowTheModelAsPossible() {
        var state = state();
        state.put("body", List.of(map("id", "body", "measuredAt", "2026-10-22T12:00:00",
            "heightCm", 175, "weightKg", 70, "age", 30, "sex", "MALE")));
        state.put("drinks", List.of(BehaviorCalculator.enrich("drinks", drink("2026-10-21T20:00:00", "3"), null, state)));
        state.put("intimacy", List.of(map("id", "intimacy", "occurredAt", "2026-10-21T22:00:00", "count", 1)));
        var report = BehaviorCalculator.analytics(state, Map.of(), DAY, DAY, DAY.plusDays(2).atTime(12, 0));
        assertEquals("POSSIBLE_AFTER_DRINKING", nested(nested(report, "links"), "intimacy").get("status"));
    }

    @Test void smokingAssociationUsesRecordedEventsAsDenominatorAndReportsQuantitySeparately() {
        var state = stateWithBody();
        state.put("sessions", List.of(map("id", "session", "startedAt", "2026-10-21T20:00:00", "endedAt", "2026-10-21T21:00:00")));
        state.put("drinks", List.of(BehaviorCalculator.enrich("drinks", drink("2026-10-21T20:00:00", "3"), null, state)));
        var before = smoke("1"); before.put("id", "before");
        var during = smoke("3"); during.putAll(map("id", "during", "occurredAt", "2026-10-21T20:30:00"));
        var deleted = smoke("99"); deleted.putAll(map("id", "deleted", "deleted", true));
        var outside = smoke("99"); outside.putAll(map("id", "outside", "occurredAt", "2026-10-20T19:00:00"));
        state.put("smoking", List.of(before, during, deleted, outside));
        var report = BehaviorCalculator.analytics(state, Map.of(), DAY, DAY, NOW);
        var summary = nested(nested(report, "associations"), "smoking");
        assertNumber("2", summary.get("totalEvents"));
        assertNumber("4", summary.get("totalQuantity"));
        assertNumber("1", summary.get("relatedEvents"));
        assertNumber("3", summary.get("relatedQuantity"));
        assertNumber("50", summary.get("relatedPercent"));
        assertNumber("75", summary.get("relatedQuantityPercent"));
        assertNumber("1", summary.get("uncertainEvents"));
        assertNumber("1", summary.get("noRecordedRecentEvents"));
        assertNumber("1", summary.get("duringEvents"));
        assertNumber("3", nested(report, "totals").get("alcoholRelatedSmokingCount"));
        assertEquals("UNKNOWN", nested(nested(report, "smokingLinks"), "before").get("effective"));
        assertEquals("DURING_DRINKING", nested(nested(report, "smokingLinks"), "during").get("status"));
        assertEquals(2, nested(report, "smokingLinks").size());
        assertTrue(summary.get("notice").toString().contains("不是因果关系"));
    }

    @Test void smokingLinksUseEarlierCrossMidnightDrinksButCannotOrderDateOnlyRecords() {
        var state = stateWithBody();
        var drink = drink("2026-10-21T23:00:00", "3"); drink.put("sessionId", "party");
        state.put("drinks", List.of(BehaviorCalculator.enrich("drinks", drink, null, state)));
        var exact = smoke("2"); exact.putAll(map("id", "exact", "occurredAt", "2026-10-22T00:30:00"));
        var imprecise = smoke("4"); imprecise.putAll(map("id", "date", "occurredAt", "2026-10-22T12:00:00", "timePrecision", "DATE"));
        state.put("smoking", List.of(exact, imprecise));
        var report = BehaviorCalculator.analytics(state, Map.of(), DAY.plusDays(1), DAY.plusDays(1), DAY.plusDays(1).atTime(23, 0));
        var links = nested(report, "smokingLinks");
        assertEquals("POSSIBLE_AFTER_DRINKING", nested(links, "exact").get("status"));
        assertEquals(List.of("party"), nested(links, "exact").get("sessionIds"));
        assertEquals("INSUFFICIENT_DATA", nested(links, "date").get("status"));
        var summary = nested(nested(report, "associations"), "smoking");
        assertNumber("1", summary.get("modelWindowEvents"));
        assertNumber("1", summary.get("insufficientEvents"));
        assertNumber("1", summary.get("relatedEvents"));
        assertNumber("1", summary.get("uncertainEvents"));
    }

    @Test void associationSummaryRespectsManualOverridesWithoutDiscardingAutomaticEvidence() {
        var state = stateWithBody();
        state.put("drinks", List.of(BehaviorCalculator.enrich("drinks", drink("2026-10-21T20:00:00", "3"), null, state)));
        state.put("intimacy", List.of(
            map("id", "manual-no", "occurredAt", "2026-10-21T21:00:00", "count", 2, "manualAlcohol", "NO"),
            map("id", "manual-yes", "occurredAt", "2026-10-21T18:00:00", "count", 1, "manualAlcohol", "YES"),
            map("id", "unconfirmed", "occurredAt", "2026-10-21T19:00:00", "count", 1)));
        var report = BehaviorCalculator.analytics(state, Map.of(), DAY, DAY, NOW);
        var summary = nested(nested(report, "associations"), "intimacy");
        assertNumber("3", summary.get("totalEvents"));
        assertNumber("1", summary.get("relatedEvents"));
        assertNumber("1", summary.get("relatedQuantity"));
        assertNumber("33.3", summary.get("relatedPercent"));
        assertNumber("25", summary.get("relatedQuantityPercent"));
        assertNumber("1", summary.get("uncertainEvents"));
        assertNumber("1", summary.get("manualYesEvents"));
        assertNumber("1", summary.get("manualNoEvents"));
        var overridden = nested(nested(report, "links"), "manual-no");
        assertEquals("YES", overridden.get("autoEffective"));
        assertEquals("NO", overridden.get("effective"));
        assertNumber("1", nested(report, "totals").get("alcoholRelatedIntimacyCount"));
    }

    @Test void emptyAssociationHasNoPercentageAndMissingBodyLeavesRecentLinkUncertain() {
        var state = state();
        var empty = nested(nested(BehaviorCalculator.analytics(state, Map.of(), DAY, DAY, NOW), "associations"), "smoking");
        assertNumber("0", empty.get("totalEvents"));
        assertNull(empty.get("relatedPercent"));
        state.put("drinks", List.of(BehaviorCalculator.enrich("drinks", drink("2026-10-21T20:00:00", "1"), null, state)));
        var event = smoke("2"); event.putAll(map("id", "recent", "occurredAt", "2026-10-21T22:00:00"));
        state.put("smoking", List.of(event));
        var report = BehaviorCalculator.analytics(state, Map.of(), DAY, DAY, NOW);
        assertEquals("RECENT_RECORDED_DRINKING", nested(nested(report, "smokingLinks"), "recent").get("status"));
        var summary = nested(nested(report, "associations"), "smoking");
        assertNumber("0", summary.get("relatedEvents"));
        assertNumber("1", summary.get("uncertainEvents"));
        assertNumber("1", summary.get("recentEvents"));
    }

    private static Map<String, Object> link(Map<String, List<Map<String, Object>>> state, LocalDate day) {
        return nested(nested(BehaviorCalculator.analytics(state, Map.of(), day, day, day.atTime(23, 0)), "links"), "intimacy");
    }
    private static Map<String, List<Map<String, Object>>> stateWithBody() {
        var state = state();
        state.put("body", new ArrayList<>(List.of(map("measuredAt", "2026-10-01T00:00:00", "heightCm", 175,
            "weightKg", 70, "age", 30, "sex", "MALE"))));
        return state;
    }
    private static Map<String, List<Map<String, Object>>> state() {
        var state = new LinkedHashMap<String, List<Map<String, Object>>>();
        state.put("products", List.of(
            map("id", "smoke-product", "kind", "SMOKING", "name", "测试烟", "variant", "标准", "unitCount", 20,
                "tarMg", 8, "nicotineMg", new BigDecimal("0.8"), "coMg", 10, "constituentMethod", "标签检测"),
            map("id", "drink-product", "kind", "ALCOHOL", "name", "测试酒", "bottleMl", 500, "abv", 5)));
        return state;
    }
    private static Map<String, Object> price(String id, String type, String date, String amount, boolean verified) {
        return map("id", id, "productId", "smoke-product", "priceType", type, "effectiveDate", date,
            "amount", new BigDecimal(amount), "currency", "CNY", "verified", verified);
    }
    private static Map<String, Object> smoke(String count) {
        return map("occurredAt", "2026-10-21T19:00:00", "productId", "smoke-product", "count", new BigDecimal(count),
            "source", "SELF", "timePrecision", "EXACT");
    }
    private static Map<String, Object> drink(String time, String bottles) {
        return map("occurredAt", time, "productId", "drink-product", "sessionId", "session", "inputMode", "BOTTLES",
            "bottles", new BigDecimal(bottles), "share", ONE(), "timePrecision", "EXACT");
    }
    private static BigDecimal ZERO() { return BigDecimal.ZERO; }
    private static BigDecimal ONE() { return BigDecimal.ONE; }
    private static double number(Object value) { return new BigDecimal(value.toString()).doubleValue(); }
    private static void assertNumber(String expected, Object actual) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual.toString())), expected + " versus " + actual);
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> nested(Map<String, Object> map, String key) { return (Map<String, Object>) map.get(key); }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> list(Object value) { return (List<Map<String, Object>>) value; }
    private static Map<String, Object> map(Object... entries) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], entries[i + 1]);
        return result;
    }
}
