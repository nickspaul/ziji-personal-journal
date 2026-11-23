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

    @Test void privateActivityCountsUseEventQuantityAcrossBothTypes() {
        var state=state();
        state.put("intimacy",List.of(
            map("id","legacy","occurredAt","2026-10-24T08:00","count",3),
            map("id","sex","occurredAt","2026-10-24T09:00","activityType","SEX","count",2,"manualAlcohol","YES"),
            map("id","deleted","occurredAt","2026-10-24T10:00","activityType","SEX","count",4,"deleted",true),
            map("id","later","occurredAt","2026-10-25T10:00","activityType","SEX","count",5)));
        var range=BehaviorCalculator.analytics(state,Map.of(),NOW.toLocalDate(),NOW.toLocalDate().plusDays(1),NOW);
        var days=(List<?>)range.get("daily");
        for(var counts:List.of(totals(range),totals(BehaviorCalculator.summary(state,NOW)),(Map<?,?>)days.get(0))) {
            assertNumber("3",counts.get("masturbationCount"));
            assertNumber("2",counts.get("sexCount"));
            assertNumber("5",counts.get("intimacyCount"));
        }
        assertNumber("2",totals(range).get("alcoholRelatedIntimacyCount"));
        assertNull(((Map<?,?>)days.get(1)).get("sexCount"));
    }

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
        assertTrue(trendPoints(result, "day").isEmpty());
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

    @Test void retiredTrackingCannotChangeRealStatisticsOrInventAnEarlierStart() {
        var state = state();
        state.put("smoking", List.of(map("id", "s", "occurredAt", "2026-10-20T10:00:00", "count", 3, "cost", "4.5")));
        var expected = BehaviorCalculator.summary(state, NOW);
        var expectedRange = BehaviorCalculator.analytics(state, Map.of(), LocalDate.of(2026, 10, 20), NOW.toLocalDate(), NOW);
        state.put("completeness", List.of(map("date", "1900-01-01", "kind", "SMOKING", "complete", true)));
        state.put("goals", List.of(map("kind", "SMOKING", "limitValue", 999, "enabled", true)));
        var actual = BehaviorCalculator.summary(state, NOW);
        assertEquals(expected, actual);
        assertEquals(expectedRange, BehaviorCalculator.analytics(state, Map.of(), LocalDate.of(2026, 10, 20), NOW.toLocalDate(), NOW));
        assertEquals("2026-10-20", actual.get("from"));
        assertFalse(totals(actual).containsKey("completeDays"));
        assertFalse(totals(actual).containsKey("completeness"));
    }

    @Test void lifetimeSpanDoesNotBuildDailyRowsOrDependOnDateRangeLimit() {
        var state = state();
        state.put("smoking", List.of(map("occurredAt", "1800-01-01T10:00:00", "count", 1, "cost", 2)));
        var result = BehaviorCalculator.summary(state, NOW);
        assertEquals("1800-01-01", result.get("from"));
        assertNumber("1", totals(result).get("smokingCount"));
        assertFalse(result.containsKey("daily"));
        var points = trendPoints(result, "year");
        assertEquals(227, points.size());
        assertEquals("1800-01-01", points.get(0).get("date"));
        assertNumber("1", points.get(0).get("smokingCount"));
        assertNumber("0", points.get(1).get("smokingCount"));
        assertEquals("2026-01-01", points.get(226).get("date"));
    }

    @Test void dailyTrendIncludesYearBoundaryEmptyDaysAndUnknownAlcohol() {
        var state = state();
        var end = LocalDateTime.of(2026, 1, 3, 12, 0);
        state.put("sessions", List.of(map("id", "empty", "startedAt", "2025-12-31T11:00")));
        state.put("drinks", List.of(
            map("sessionId", "new", "occurredAt", "2026-01-02T11:00", "ethanolGrams", "0.33138"),
            map("sessionId", "new", "occurredAt", "2026-01-02T12:00")));
        state.put("intimacy", List.of(map("occurredAt", "2026-01-03T08:00", "activityType", "SEX", "count", 2)));
        state.put("smoking", List.of(map("occurredAt", "2026-01-03T18:00", "count", 7)));
        var report = BehaviorCalculator.summary(state, end);
        var points = trendPoints(report, "day");
        assertEquals(List.of("2025-12-31", "2026-01-01", "2026-01-02", "2026-01-03"),
            points.stream().map(p -> p.get("date")).toList());
        assertNull(points.get(0).get("ethanolEquivalent42Ml"));
        assertNumber("0", points.get(1).get("ethanolEquivalent42Ml"));
        assertNumber("1", points.get(2).get("ethanolEquivalent42Ml"));
        assertNumber("2", points.get(3).get("intimacyCount"));
        assertNumber("0", points.get(3).get("smokingCount"));
        assertNumber("1", totals(report).get("ethanolEquivalent42Ml"));
    }

    @Test void monthlyTrendCombinesEventsAndLabelsPartialMonthsWithinRange() {
        var state = state();
        state.put("smoking", List.of(map("occurredAt", "2024-10-23T12:00", "count", 2)));
        state.put("drinks", List.of(
            map("sessionId", "a", "occurredAt", "2025-01-02T11:00", "ethanolGrams", "0.33138"),
            map("sessionId", "b", "occurredAt", "2025-01-29T12:00", "ethanolGrams", "0.66276")));
        state.put("intimacy", List.of(map("occurredAt", "2026-10-24T09:00")));
        var points = trendPoints(BehaviorCalculator.summary(state, NOW), "month");
        assertEquals(25, points.size());
        assertEquals("2024-10-23", points.get(0).get("date"));
        assertNumber("2", points.get(0).get("smokingCount"));
        assertEquals("2025-01-01", points.get(3).get("date"));
        assertNumber("3", points.get(3).get("ethanolEquivalent42Ml"));
        assertEquals("2026-10-01", points.get(24).get("date"));
        assertNumber("1", points.get(24).get("intimacyCount"));
    }

    @Test void switchesUnitsOnlyAfter731DaysOrAfterTheThirtiethAnniversary() {
        var state = state();
        state.put("smoking", List.of(map("occurredAt", "2024-10-24T09:00", "count", 1)));
        assertEquals(731, trendPoints(BehaviorCalculator.summary(state, NOW), "day").size());
        state.put("smoking", List.of(map("occurredAt", "1996-10-24T09:00", "count", 1)));
        assertEquals(361, trendPoints(BehaviorCalculator.summary(state, NOW), "month").size());
        assertEquals("1996-10-24", trendPoints(BehaviorCalculator.summary(state, NOW.plusDays(1)), "year").get(0).get("date"));
    }

    private static Map<String, List<Map<String, Object>>> state() { return new LinkedHashMap<>(); }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> trendPoints(Map<String, Object> report, String expectedUnit) {
        Map<?, ?> trend = (Map<?, ?>) report.get("trend");
        assertEquals(expectedUnit, trend.get("unit"));
        return (List<Map<String, Object>>) trend.get("points");
    }
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
