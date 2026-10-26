package cn.ziji.journal;

import cn.ziji.data.DataService;
import cn.ziji.web.ApiException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskEffortServiceTest {
    private static final String TASK="d4565851-8366-4571-a981-20732656fd3b";
    private static final String OTHER="e535f7d3-1861-4e54-9e72-603f79b31681";

    private static Map<String,Object> entry(Object... values) {
        Map<String,Object> row=new LinkedHashMap<>();
        for(int i=0;i<values.length;i+=2)row.put(values[i].toString(),values[i+1]);
        return row;
    }
    private static Map<String,Object> exact(String day,String start,String end) {
        return entry("taskId",TASK,"startDate",day,"timeMode","EXACT","startTime",start,"endTime",end);
    }

    @Test void noLogsAndLogsWithoutDurationRemainUnknown() {
        var empty=TaskEffortService.summarize(List.of());
        assertNull(empty.get("totalMinutes"));assertEquals(0L,empty.get("linkedCount"));
        var unknown=TaskEffortService.summarize(List.of(entry("timeMode","DATE"),entry("timeMode","PERIOD","period","MORNING"),entry("timeMode","EXACT","startDate","2026-10-01","startTime","09:00")));
        assertNull(unknown.get("totalMinutes"));assertEquals(3L,unknown.get("unmeasuredCount"));
        assertEquals(0L,unknown.get("manualMinutes"));assertEquals(0L,unknown.get("timedMinutes"));
    }

    @Test void overlappingExactLogsCountTheUnionAndReportRemovedOverlap() {
        var first=exact("2026-10-01","09:00","10:00");
        var second=exact("2026-10-01","09:30","11:00");
        var third=exact("2026-10-01","10:30","11:30");
        var summary=TaskEffortService.summarize(List.of(third,first,second));
        assertEquals(150L,summary.get("timedMinutes"));assertEquals(150L,summary.get("totalMinutes"));
        assertEquals(60L,summary.get("overlapMinutes"));assertEquals(3L,summary.get("timedCount"));
        assertEquals(3L,summary.get("linkedCount"));assertEquals(0L,summary.get("unmeasuredCount"));
        assertFalse(first.containsKey("effort"));assertFalse(first.containsKey("durationMinutes"));
    }

    @Test void crossMidnightIntervalsUseTheActualEndDate() {
        var overnight=exact("2026-10-01","23:30","00:30");overnight.put("endDate","2026-10-02");
        var nextDay=exact("2026-10-02","00:00","01:00");
        var summary=TaskEffortService.summarize(List.of(overnight,nextDay));
        assertEquals(90L,summary.get("totalMinutes"));assertEquals(30L,summary.get("overlapMinutes"));
    }

    @Test void adjacentSeparateAndZeroLengthEntriesDoNotInventOverlapOrMissingTime() {
        var summary=TaskEffortService.summarize(List.of(exact("2026-10-01","09:00","09:30"),exact("2026-10-01","09:30","10:00"),exact("2026-10-01","11:00","11:30")));
        assertEquals(90L,summary.get("totalMinutes"));assertEquals(0L,summary.get("overlapMinutes"));
        var zero=TaskEffortService.summarize(List.of(exact("2026-10-01","09:00","09:00")));
        assertEquals(0L,zero.get("totalMinutes"));assertEquals(1L,zero.get("timedCount"));assertEquals(0L,zero.get("unmeasuredCount"));
    }

    @Test void explicitDurationIsSeparateFromTimedIntervalsAndUnknownEntries() {
        var summary=TaskEffortService.summarize(List.of(exact("2026-10-01","09:00","10:00"),entry("timeMode","PERIOD","durationMinutes",45),entry("timeMode","DATE")));
        assertEquals(105L,summary.get("totalMinutes"));assertEquals(60L,summary.get("timedMinutes"));
        assertEquals(45L,summary.get("manualMinutes"));assertEquals(1L,summary.get("manualCount"));
        assertEquals(1L,summary.get("unmeasuredCount"));assertEquals(3L,summary.get("linkedCount"));
    }

    @Test void aCompleteExactIntervalNeverAddsItsManualValueTwice() {
        var row=exact("2026-10-01","09:00","10:00");row.put("durationMinutes",75);
        var summary=TaskEffortService.summarize(List.of(row));
        assertEquals(60L,summary.get("totalMinutes"));assertEquals(0L,summary.get("manualCount"));
        assertEquals(0L,summary.get("manualMinutes"));assertEquals(75,row.get("durationMinutes"));
    }

    @Test void deletedLogsAreExcludedAndRestoringOnlyChangesTheDerivedView() {
        var row=exact("2026-10-01","09:00","10:00");row.put("deleted",true);
        var removed=TaskEffortService.summarize(List.of(row));
        assertNull(removed.get("totalMinutes"));assertEquals(0L,removed.get("linkedCount"));assertEquals(true,row.get("deleted"));
        row.put("deleted",false);
        var restored=TaskEffortService.summarize(List.of(row));
        assertEquals(60L,restored.get("totalMinutes"));assertEquals(1L,restored.get("linkedCount"));
        assertEquals(false,row.get("deleted"));assertFalse(row.containsKey("effort"));
    }

    @Test void malformedLegacyDurationsAreUnknownInsteadOfCrashingOrBecomingZero() {
        var reversed=exact("2026-10-01","23:00","01:00");
        var summary=TaskEffortService.summarize(List.of(reversed,entry("durationMinutes",-1),entry("durationMinutes","1.5"),entry("durationMinutes","not a number")));
        assertNull(summary.get("totalMinutes"));assertEquals(4L,summary.get("unmeasuredCount"));
    }

    @Test void batchReadsOnlyRequestedTasksAndDoesNotWriteDataOrAudit() {
        var jdbc=mock(JdbcTemplate.class);var data=mock(DataService.class);
        var row=exact("2026-10-01","09:00","10:00");
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(row));
        var service=new TaskEffortService(jdbc,data);
        var result=service.forTasks(List.of(TASK,OTHER,TASK));
        assertEquals(Set.of(TASK,OTHER),result.keySet());assertEquals(60L,result.get(TASK).get("totalMinutes"));assertNull(result.get(OTHER).get("totalMinutes"));
        var sql=ArgumentCaptor.forClass(String.class);var args=ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(sql.capture(),args.capture());
        assertTrue(sql.getValue().contains("deleted=FALSE AND taskId IN (?,?)"));
        assertFalse(sql.getValue().contains(TASK));assertEquals(List.of(TASK,OTHER),Arrays.asList(args.getValue()));
        verifyNoMoreInteractions(jdbc);verifyNoInteractions(data);
    }

    @Test void emptyPageDoesNoQueryAndInvalidTaskIdsAreRejected() {
        var jdbc=mock(JdbcTemplate.class);var data=mock(DataService.class);var service=new TaskEffortService(jdbc,data);
        assertEquals(Map.of(),service.forTasks(List.of()));
        assertThrows(IllegalArgumentException.class,()->service.forTasks(List.of("' OR 1=1")));
        verifyNoInteractions(jdbc,data);
    }

    @Test void taskDetailChecksExistenceAndRecomputesWithoutCachingOrWriting() {
        var jdbc=mock(JdbcTemplate.class);var data=mock(DataService.class);var service=new TaskEffortService(jdbc,data);
        when(data.require("tasks",TASK)).thenReturn(Map.of("id",TASK,"deleted",true));
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(exact("2026-10-01","09:00","10:00")),List.of());
        assertEquals(60L,service.forTask(TASK).get("totalMinutes"));assertNull(service.forTask(TASK).get("totalMinutes"));
        verify(data,times(2)).require("tasks",TASK);verifyNoMoreInteractions(data);
        when(data.require("tasks",OTHER)).thenThrow(new ApiException(404,"记录不存在"));
        assertThrows(ApiException.class,()->service.forTask(OTHER));
        verify(jdbc,times(2)).queryForList(anyString(),any(Object[].class));verifyNoMoreInteractions(jdbc);
    }
}
