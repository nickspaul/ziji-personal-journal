package cn.ziji.report;

import cn.ziji.data.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PeriodReportServiceTest {
    final ReportQueries queries=mock(ReportQueries.class);
    final DataRepository repository=mock(DataRepository.class);
    final PeriodReportService service=new PeriodReportService(queries,new SchemaRegistry(),repository);
    final LocalDate from=LocalDate.parse("2026-10-01"),to=LocalDate.parse("2026-10-30");

    @Test void ledgerOnlyReadsSelectedRowsAndTotalsExactCents() {
        var expense=Map.<String,Object>of("amount",new BigDecimal("0.13"),"direction","EXPENSE","category","餐饮","id","private-id");
        var income=Map.<String,Object>of("amount",new BigDecimal("1.30"),"direction","INCOME","category","退款");
        when(queries.range("ledger",from,to)).thenReturn(List.of(expense,income));
        var summary=service.report(from,to,"ledger").sections().get(0);
        assertEquals(new BigDecimal("1.17"),summary.summary().get("期间结余（元）"));
        assertEquals("支出",summary.records().get(0).get("收支类型"));
        assertFalse(summary.records().get(0).containsValue("private-id"));
        verify(queries).range("ledger",from,to);
        verifyNoMoreInteractions(queries,repository);
    }

    @Test void repaymentPrincipalIsSeparateFromExpenseInExports() {
        var credit = Map.<String,Object>of("debtKind","CREDIT","direction","EXPENSE","amount",new BigDecimal("100.00"));
        var payment = Map.<String,Object>of("debtKind","REPAYMENT","direction","EXPENSE","amount",new BigDecimal("52.00"),"debtFee",new BigDecimal("2.00"));
        when(queries.range("ledger",from,to)).thenReturn(List.of(credit,payment));
        var section=service.report(from,to,"ledger").sections().get(0);
        assertEquals(new BigDecimal("102.00"),section.summary().get("支出（元）"));
        assertEquals(new BigDecimal("52.00"),section.summary().get("现金流出（元）"));
        assertEquals(new BigDecimal("50.00"),section.summary().get("期间欠款本金净变动（元）"));
        assertEquals(new BigDecimal("-50.00"),section.records().get(1).get("欠款本金变动（元）"));
    }

    @Test void tasksWithJournalClipAndDeduplicateIntervalsWithoutCountingCrossBoundaryManualMinutes() {
        var workA=Map.<String,Object>of("startDate","2026-09-30","endDate","2026-10-01","startTime","23:00","endTime","01:00","timeMode","EXACT","taskId","t");
        var workB=Map.<String,Object>of("startDate","2026-10-01","startTime","00:15","endTime","01:15","timeMode","EXACT","taskId","t");
        var manual=Map.<String,Object>of("startDate","2026-10-30","endDate","2026-11-02","timeMode","DATE","durationMinutes",300,"taskId","t");
        when(queries.journal(from,to)).thenReturn(List.of(workA,workB,manual));
        when(queries.tasks(from,to)).thenReturn(List.of(Map.of("id","t","title","校对")));
        var journalOnly=service.report(from,to,"journal").sections().get(0);
        assertFalse(journalOnly.records().get(0).containsKey("关联待办"));
        var both=service.report(from,to,"journal,tasks").sections();
        assertEquals("校对",both.get(0).records().get(0).get("关联待办"));
        var task=both.get(1).records().get(0);
        assertEquals(75L,task.get("期间已知投入（分钟）"));
        assertEquals(1L,task.get("未能确定期间时长的日志数"));
    }

    @Test void emptyDrinkSessionPreservesUnknownQuantitiesAndDoesNotGenerateAFakeDrink() {
        when(queries.range("sessions",from,to)).thenReturn(List.of(Map.of("id","s","startedAt","2026-10-05T12:00","timePrecision","DATE")));
        when(queries.emptySessions(from,to)).thenReturn(Set.of("s"));
        var section=service.report(from,to,"alcohol").sections().get(0);
        assertEquals(1,section.records().size());
        assertEquals("2026-10-05",section.records().get(0).get("开始时间"));
        assertEquals(1,section.summary().get("开始的场次数"));
        assertEquals(0,section.summary().get("酒品明细数"));
        assertNull(section.summary().get("已知纯酒精（克）"));
        assertNull(section.summary().get("已知估算费用（元）"));
        assertEquals(1L,section.summary().get("缺费用的项数"));
    }

    @Test void excludesUnselectedAlcoholInformationAndMasksApproximatePrecision() {
        when(queries.range("intimacy",from,to)).thenReturn(List.of(Map.of("count",1,"occurredAt","2026-10-01T20:32","timePrecision","APPROX","manualAlcohol","YES","overrideReason","酒后备注")));
        var row=service.report(from,to,"intimacy").sections().get(0).records().get(0);
        assertEquals("2026-10-01 约 20 时",row.get("发生时间"));
        assertFalse(row.containsValue("酒后备注"));
        verify(queries).range("intimacy",from,to);
        verifyNoMoreInteractions(queries,repository);
    }

    @Test void privateReportExplainsTheTypeOfEachActivityAndCountsOccurrences() {
        when(queries.range("intimacy",from,to)).thenReturn(List.of(
            Map.of("activityType","MASTURBATION","count",3),Map.of("activityType","SEX","count",2)));
        var section=service.report(from,to,"intimacy").sections().get(0);
        assertEquals("自慰",section.records().get(0).get("私人行为类型"));
        assertEquals("性生活",section.records().get(1).get("私人行为类型"));
        assertEquals(new BigDecimal("5"),section.summary().get("次数"));
        assertEquals(new BigDecimal("3"),section.summary().get("自慰次数"));
        assertEquals(new BigDecimal("2"),section.summary().get("性生活次数"));
    }

    @Test void invalidRangesAndSectionNamesAreRejectedBeforeReadingDatabase() {
        for(String value:List.of("","ledger,","backup","ledger,settings","auth"))
            assertThrows(IllegalArgumentException.class,()->service.report(from,to,value));
        assertThrows(IllegalArgumentException.class,()->service.report(to,from,"ledger"));
        verifyNoInteractions(queries,repository);
    }
}
