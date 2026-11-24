package cn.ziji.report;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DayContextControllerTest {
    private final ReportQueries queries=mock(ReportQueries.class);
    private final DayContextController controller=new DayContextController(queries);
    private final LocalDate start=LocalDate.parse("2025-12-31"), end=LocalDate.parse("2026-01-02");

    @Test void preservesWholeCrossYearSpanAndDefaultsMissingEndAndArrays() {
        when(queries.journal(start,end)).thenReturn(List.of(
                Map.of("id","trip","title","出差","category","TRAVEL","startDate","2025-12-29","endDate","2026-01-03","tags",List.of("出差"),"people",List.of("小林")),
                Map.of("id","solo","title","午饭","startDate","2025-12-31")));
        var result=controller.context(start,end);
        assertEquals("2025-12-29",result.get(0).startDate());
        assertEquals("2026-01-03",result.get(0).endDate());
        assertEquals(List.of("小林"),result.get(0).people());
        assertEquals(result.get(1).startDate(),result.get(1).endDate());
        assertTrue(result.get(1).tags().isEmpty());
        verify(queries).journal(start,end);
        verifyNoMoreInteractions(queries);
    }

    @Test void rejectsReversedOrExcessiveRangesAndSkipsEntirelyFutureDates() {
        assertThrows(IllegalArgumentException.class,()->controller.context(end,start));
        assertThrows(IllegalArgumentException.class,()->controller.context(start,start.plusYears(11)));
        assertEquals(List.of(),controller.context(LocalDate.of(2999,1,1),LocalDate.of(2999,1,7)));
        verifyNoInteractions(queries);
    }
}
