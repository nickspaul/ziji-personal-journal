package cn.ziji.report;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/export")
class ReportController {
    private final PeriodReportService reports;
    ReportController(PeriodReportService reports) { this.reports=reports; }

    @GetMapping("/report")
    PeriodReportService.Report report(
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam String sections) {
        return reports.report(from,to,sections);
    }
}
