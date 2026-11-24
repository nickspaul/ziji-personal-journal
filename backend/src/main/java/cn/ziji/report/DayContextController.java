package cn.ziji.report;

import cn.ziji.data.DataValidator;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
public class DayContextController {
    private final ReportQueries queries;

    public DayContextController(ReportQueries queries) {
        this.queries = queries;
    }

    // Behavioral day totals already come from /analytics. Only send the context needed to group those days.
    @GetMapping("/api/analytics/day-context")
    public List<JournalSpan> context(
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 3660)
            throw new IllegalArgumentException("日期范围不正确或超过十年");
        LocalDate through = LocalDate.now(DataValidator.ZONE);
        if (from.isAfter(through)) return List.of();
        return queries.journal(from, to.isAfter(through) ? through : to).stream().map(JournalSpan::from).toList();
    }

    public record JournalSpan(String id, String title, String category, String startDate, String endDate,
                              List<?> people, List<?> tags) {
        static JournalSpan from(Map<String,Object> row) {
            String start = row.get("startDate").toString();
            return new JournalSpan(row.get("id").toString(), Objects.toString(row.get("title"), ""),
                    Objects.toString(row.get("category"), ""), start, Objects.toString(row.get("endDate"), start),
                    row.get("people") instanceof List<?> names ? names : List.of(),
                    row.get("tags") instanceof List<?> labels ? labels : List.of());
        }
    }
}
