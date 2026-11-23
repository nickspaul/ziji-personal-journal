package cn.ziji.report;

import cn.ziji.data.DataRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

@Repository
class ReportQueries {
    private final JdbcTemplate jdbc;
    private final DataRepository data;
    ReportQueries(JdbcTemplate jdbc, DataRepository data) { this.jdbc=jdbc; this.data=data; }

    List<Map<String,Object>> range(String name, LocalDate from, LocalDate to) {
        String field = switch(name) {
            case "ledger" -> "`date`";
            case "body" -> "measuredAt";
            case "smoking","intimacy","drinks" -> "occurredAt";
            case "sessions" -> "startedAt";
            default -> throw new IllegalArgumentException("未知的期间记录");
        };
        return query(name, field+" >= ? AND "+field+" <= ?", field+",id", from, endOfDay(to));
    }

    List<Map<String,Object>> journal(LocalDate from, LocalDate to) {
        return query("journal","startDate<=? AND COALESCE(endDate,startDate)>=?", "startDate,startTime,id",to,from);
    }

    List<Map<String,Object>> tasks(LocalDate from, LocalDate to) {
        return query("tasks","""
                (createdAt>=? AND createdAt<=? OR plannedDate BETWEEN ? AND ? OR dueDate BETWEEN ? AND ?
                OR completedDate BETWEEN ? AND ? OR EXISTS (SELECT 1 FROM zj_journal j
                WHERE j.taskId=zj_tasks.id AND j.deleted=FALSE AND j.startDate<=? AND COALESCE(j.endDate,j.startDate)>=?))
                ""","COALESCE(completedDate,dueDate,plannedDate,DATE(createdAt)),id",
                from,endOfDay(to),from,to,from,to,from,to,to,from);
    }

    Set<String> emptySessions(LocalDate from, LocalDate to) {
        return new HashSet<>(jdbc.queryForList("""
                SELECT s.id FROM zj_sessions s WHERE s.deleted=FALSE AND s.startedAt>=? AND s.startedAt<=?
                AND NOT EXISTS (SELECT 1 FROM zj_drinks d WHERE d.deleted=FALSE AND d.sessionId=s.id)
                """, String.class, from, endOfDay(to)));
    }

    // A previous dose can still influence an in-range event. The calculator selects
    // eligible doses and its permitted later body measurement; only results leave this service.
    Map<String,List<Map<String,Object>>> modelContext(LocalDate to) {
        LocalDateTime drinkingEnd=endOfDay(to.plusDays(1)); // approximate timestamps can overlap the preceding day
        return Map.of("sessions", query("sessions","startedAt<=?","startedAt,id",drinkingEnd),
                "drinks", query("drinks","occurredAt<=?","occurredAt,id",drinkingEnd),
                "body", query("body","measuredAt<=?","measuredAt,id",endOfDay(to.plusDays(31))));
    }

    private static LocalDateTime endOfDay(LocalDate date) {
        LocalDate mysqlMax=LocalDate.of(9999,12,31);
        return (date.isAfter(mysqlMax)?mysqlMax:date).atTime(LocalTime.of(23,59,59,999999000));
    }

    private List<Map<String,Object>> query(String name,String predicate,String order,Object...args) {
        return jdbc.query("SELECT * FROM zj_"+name+" WHERE deleted=FALSE AND "+predicate+" ORDER BY "+order,
                (rs,index)->data.mapRow(name,rs),args);
    }
}
