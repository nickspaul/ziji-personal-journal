package cn.ziji.journal;

import cn.ziji.data.DataRepository;
import cn.ziji.data.DataValidator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/journal")
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class JournalRecallController {
    private final JdbcTemplate jdbc;
    private final DataRepository records;

    public JournalRecallController(JdbcTemplate jdbc, DataRepository records) {
        this.jdbc = jdbc;
        this.records = records;
    }

    private static final String WITH_PERSON = """
            FROM zj_journal WHERE deleted = FALSE
            AND JSON_CONTAINS(COALESCE(people, JSON_ARRAY()), JSON_QUOTE(?))
            """;

    @GetMapping("/people/recap")
    public Map<String,Object> recap(@RequestParam String name) {
        List<String> names = DataValidator.normalizeStrings(List.of(name), false);
        if (names.isEmpty()) throw new IllegalArgumentException("请选择同行人");
        String person = names.get(0);
        Long count = jdbc.queryForObject("SELECT COUNT(*) " + WITH_PERSON, Long.class, person);
        var recent = jdbc.query("SELECT * " + WITH_PERSON + """
                ORDER BY COALESCE(endDate, startDate) DESC, startDate DESC, startTime DESC, id DESC LIMIT 6
                """, (rs,n) -> records.mapRow("journal", rs), person);
        var places = jdbc.query("SELECT place, COUNT(*) AS visits " + WITH_PERSON + """
                AND place IS NOT NULL AND TRIM(place) <> ''
                GROUP BY place ORDER BY visits DESC, MAX(startDate) DESC, place LIMIT 5
                """, (rs,n) -> Map.of("name", rs.getString("place"), "count", rs.getLong("visits")), person);
        return Map.of("name", person, "total", count, "recent", recent, "places", places);
    }

    public record Memory(Map<String,Object> row, LocalDate day) {}

    @GetMapping("/memory")
    public Memory memory(@RequestParam(defaultValue = "all") String scope,
                         @RequestParam(required = false) String previousId) {
        LocalDate today = LocalDate.now(DataValidator.ZONE);
        LocalDate anniversary = today.minusYears(1);
        List<Object> args = new ArrayList<>();
        String filter;
        if ("last-year".equals(scope)) {
            filter = "startDate <= ? AND COALESCE(endDate, startDate) >= ?";
            args.add(anniversary);
            args.add(anniversary);
        } else {
            filter = switch (scope) {
                case "all" -> "";
                case "outings" -> "JSON_LENGTH(people) > 0 AND category IN ('MEAL','SOCIAL','TRAVEL') AND ";
                default -> throw new IllegalArgumentException("请选择旧日记范围");
            } + "COALESCE(endDate, startDate) < ?";
            args.add(today);
        }
        String order = "RAND()";
        if (previousId != null && !previousId.isBlank()) {
            order = "(id = ?), RAND()";
            args.add(DataValidator.uuid(previousId));
        }
        var rows = jdbc.query("SELECT * FROM zj_journal WHERE deleted = FALSE AND " + filter
                + " ORDER BY " + order + " LIMIT 1", (rs,n) -> records.mapRow("journal", rs), args.toArray());
        var row = rows.isEmpty() ? null : rows.get(0);
        LocalDate day = "last-year".equals(scope) ? anniversary : row == null ? null : LocalDate.parse(row.get("startDate").toString());
        return new Memory(row, day);
    }
}
