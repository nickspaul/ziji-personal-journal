package cn.ziji.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LifetimeSummaryTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 24, 12, 0);

    @Test void noRecordsDefaultToZeroAndHaveNoArtificialStartDate() {
        var result = BehaviorCalculator.summary(Map.of(), NOW);
        var totals = totals(result);
        assertNull(result.get("from"));
        assertEquals("2026-10-24", result.get("to"));
        for (String key : List.of("smokingCount", "alcoholSessions", "alcoholMl", "intimacyCount", "consumptionCost", "ethanolEquivalent42Ml"))
            assertNumber("0", totals.get(key));
        assertEquals("UNRECORDED_AS_ZERO", result.get("recordingPolicy"));
        assertFalse(result.containsKey("daily"));
        assertFalse(result.containsKey("links"));
    }

    @Test void cumulativeAmountsMatchRangeAnalyticsWithoutEvaluatingAssociations() {
        var state = state();
        state.put("smoking", List.of(map("id", "s", "occurredAt", "2026-10-20T10:00:00", "count", 3, "cost", "4.5")));
        state.put("sessions", List.of(map("id", "session", "startedAt", "2026-10-21T10:00:00", "endedAt", "2026-10-21T11:00:00")));
        state.put("drinks", List.of(map("id", "d", "sessionId", "session", "occurredAt", "2026-10-21T10:00:00", "actualMl", 500,
            "equivalentBottles", 1, "bottles", 1, "inputMode", "BOTTLES", "ethanolGrams", "19.725", "cost", 10)));
        state.put("intimacy", List.of(map("id", "i", "occurredAt", "2026-10-22T08:00:00", "count", 2)));
        var result = BehaviorCalculator.summary(state, NOW);
        var totals = totals(result);
        var range = totals(BehaviorCalculator.analytics(state, Map.of(), LocalDate.of(2026, 10, 20), NOW.toLocalDate(), NOW));
        for (String key : List.of("smokingCount", "smokingCost", "alcoholSessions", "alcoholMl", "alcoholCost", "alcoholBottles",
                "intimacyCount", "ethanolGrams", "ethanolEquivalent42Ml", "consumptionCost", "unknownCostCount"))
            assertEquals(new BigDecimal(range.get(key).toString()), new BigDecimal(totals.get(key).toString()), key);
        assertEquals("2026-10-20", result.get("from"));
        assertNumber("14.50", totals.get("consumptionCost"));
        assertNumber("59.5238", totals.get("ethanolEquivalent42Ml"));
        assertNull(totals.get("alcoholRelatedSmokingCount"));
    }

    @Test void excludesDeletedInvalidAndNotYetOccurredRecords() {
        var state = state();
        state.put("smoking", List.of(
            map("id", "valid", "occurredAt", "2026-10-24T11:00:00", "count", 2, "cost", 3),
            map("id", "deleted", "occurredAt", "2020-01-01T10:00:00", "count", 100, "cost", 100, "deleted", true),
            map("id", "future", "occurredAt", "2026-10-24T13:00:00", "count", 100, "cost", 100),
            map("id", "invalid", "occurredAt", "not-a-date", "count", 100, "cost", 100)));
        state.put("completeness", List.of(map("date", "2019-01-01", "kind", "SMOKING", "complete", true, "deleted", true),
            map("date", "2026-10-25", "kind", "SMOKING", "complete", true)));
        var result = BehaviorCalculator.summary(state, NOW);
        assertEquals("2026-10-24", result.get("from"));
        assertNumber("2", totals(result).get("smokingCount"));
        assertNumber("3", totals(result).get("consumptionCost"));
    }

    @Test void emptySessionsAndMissingAmountsRemainUnknownOrPartial() {
        var state = state();
        state.put("sessions", List.of(map("id", "empty", "startedAt", "2026-10-20T10:00:00")));
        var totals = totals(BehaviorCalculator.summary(state, NOW));
        assertNumber("1", totals.get("alcoholSessions"));
        for (String key : List.of("alcoholMl", "alcoholCost", "ethanolGrams", "ethanolEquivalent42Ml", "consumptionCost"))
            assertNull(totals.get(key), key);
        assertNumber("1", totals.get("alcoholUnknownVolumeCount"));
        state.put("smoking", List.of(map("occurredAt", "2026-10-20T11:00:00", "count", 2, "cost", 3),
            map("occurredAt", "2026-10-20T12:00:00", "count", 1)));
        totals = totals(BehaviorCalculator.summary(state, NOW));
        assertNumber("3", totals.get("consumptionCost"));
        assertNumber("2", totals.get("unknownCostCount"));
    }

    @Test void confirmedZeroDaysAreDeduplicatedAndLatestCorrectionWins() {
        var state = state();
        state.put("completeness", List.of(
            map("date", "2026-10-20", "kind", "SMOKING", "complete", true, "updatedAt", "2026-10-20T10:00:00"),
            map("date", "2026-10-20", "kind", "SMOKING", "complete", false, "updatedAt", "2026-10-20T11:00:00"),
            map("date", "2026-10-21", "kind", "ALCOHOL", "complete", true, "updatedAt", "2026-10-21T10:00:00"),
            map("date", "2026-10-21", "kind", "ALCOHOL", "complete", true, "updatedAt", "2026-10-21T11:00:00")));
        var totals = totals(BehaviorCalculator.summary(state, NOW));
        assertNumber("0", totals.get("smokingCount"));
        assertNumber("0", totals.get("intimacyCount"));
        assertNumber("0", totals.get("alcoholMl"));
        assertNumber("0", totals.get("ethanolEquivalent42Ml"));
        assertNumber("0", totals.get("consumptionCost"));
        assertEquals(Map.of("SMOKING", 0, "ALCOHOL", 1, "INTIMACY", 0), totals.get("completeDays"));
    }

    @Test void lifetimeSpanDoesNotBuildDailyRowsOrDependOnDateRangeLimit() {
        var state = state();
        state.put("smoking", List.of(map("occurredAt", "1800-01-01T10:00:00", "count", 1, "cost", 2)));
        var result = BehaviorCalculator.summary(state, NOW);
        assertEquals("1800-01-01", result.get("from"));
        assertNumber("1", totals(result).get("smokingCount"));
        assertFalse(result.containsKey("daily"));
    }

    private static Map<String, List<Map<String, Object>>> state() { return new LinkedHashMap<>(); }
    @SuppressWarnings("unchecked") private static Map<String, Object> totals(Map<String, Object> result) {
        return (Map<String, Object>) result.get("totals");
    }
    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put(pairs[i].toString(), pairs[i + 1]);
        return result;
    }
    private static void assertNumber(String expected, Object actual) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual.toString())));
    }
}
