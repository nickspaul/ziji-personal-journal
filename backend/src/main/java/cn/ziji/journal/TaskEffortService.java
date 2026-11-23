package cn.ziji.journal;

import cn.ziji.data.DataService;
import cn.ziji.data.DataValidator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Read-only totals derived from the original linked journal records. */
@Service
public class TaskEffortService {
    private final JdbcTemplate jdbc;
    private final DataService data;

    public TaskEffortService(JdbcTemplate jdbc,DataService data) {
        this.jdbc=jdbc;this.data=data;
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> forTask(String id) {
        Map<String,Object> task=data.require("tasks",DataValidator.uuid(id));
        String key=task.get("id").toString();
        return forTasks(List.of(key)).get(key);
    }

    /** One bounded query for the tasks on the current page, across their entire history. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Map<String,Object>> forTasks(Collection<String> taskIds) {
        Set<String> ids=new LinkedHashSet<>(taskIds);
        Map<String,Map<String,Object>> result=new LinkedHashMap<>();
        if(ids.isEmpty())return result;
        if(ids.size()>50)throw new IllegalArgumentException("一次最多查看 50 项待办的投入时间");
        ids.forEach(DataValidator::uuid);
        Map<String,List<Map<String,Object>>> grouped=new LinkedHashMap<>();
        ids.forEach(id->grouped.put(id,new ArrayList<>()));
        String sql="SELECT taskId,startDate,endDate,timeMode,startTime,endTime,durationMinutes FROM zj_journal WHERE deleted=FALSE AND taskId IN ("+
            String.join(",",Collections.nCopies(ids.size(),"?"))+")";
        for(Map<String,Object> row:jdbc.queryForList(sql,ids.toArray())) {
            List<Map<String,Object>> entries=grouped.get(String.valueOf(row.get("taskId")));
            if(entries!=null)entries.add(row);
        }
        grouped.forEach((id,entries)->result.put(id,summarize(entries)));
        return result;
    }

    private record Interval(LocalDateTime start,LocalDateTime end) {}

    /** Input rows overlap the period. Clip exact intervals before de-duplicating;
     * a manually entered duration spanning a boundary cannot be apportioned. */
    public static Map<String,Object> summarizePeriod(List<Map<String,Object>> rows,LocalDate from,LocalDate to) {
        List<Map<String,Object>> clipped=new ArrayList<>();
        for(var original:rows) {
            var row=new LinkedHashMap<>(original);
            LocalDate startDay=LocalDate.parse(original.get("startDate").toString());
            LocalDate endDay=original.get("endDate")==null?startDay:LocalDate.parse(original.get("endDate").toString());
            Interval times=interval(original);
            if(times!=null) {
                LocalDateTime start=times.start.isBefore(from.atStartOfDay())?from.atStartOfDay():times.start;
                LocalDateTime end=times.end.isAfter(to.plusDays(1).atStartOfDay())?to.plusDays(1).atStartOfDay():times.end;
                row.put("startDate",start.toLocalDate());row.put("startTime",start.toLocalTime());
                row.put("endDate",end.toLocalDate());row.put("endTime",end.toLocalTime());
            } else if(startDay.isBefore(from)||endDay.isAfter(to)) row.remove("durationMinutes");
            clipped.add(row);
        }
        return summarize(clipped);
    }

    static Map<String,Object> summarize(List<Map<String,Object>> rows) {
        long linked=0,timed=0,manual=0,unmeasured=0,manualMinutes=0,rawTimedMinutes=0;
        List<Interval> intervals=new ArrayList<>();
        for(Map<String,Object> row:rows) {
            if(Boolean.TRUE.equals(row.get("deleted")))continue;
            linked++;
            Interval interval=interval(row);
            if(interval!=null) {
                timed++;
                rawTimedMinutes+=ChronoUnit.MINUTES.between(interval.start,interval.end);
                intervals.add(interval);
                continue;
            }
            Long entered=enteredMinutes(row.get("durationMinutes"));
            if(entered!=null){manual++;manualMinutes+=entered;}
            else unmeasured++;
        }
        intervals.sort(Comparator.comparing(Interval::start).thenComparing(Interval::end));
        long timedMinutes=0;
        LocalDateTime start=null,end=null;
        for(Interval interval:intervals) {
            if(start==null){start=interval.start;end=interval.end;continue;}
            if(interval.start.isAfter(end)) {
                timedMinutes+=ChronoUnit.MINUTES.between(start,end);
                start=interval.start;end=interval.end;
            } else if(interval.end.isAfter(end))end=interval.end;
        }
        if(start!=null)timedMinutes+=ChronoUnit.MINUTES.between(start,end);
        Map<String,Object> summary=new LinkedHashMap<>();
        summary.put("totalMinutes",timed+manual==0?null:timedMinutes+manualMinutes);
        summary.put("timedMinutes",timedMinutes);summary.put("manualMinutes",manualMinutes);
        summary.put("linkedCount",linked);summary.put("timedCount",timed);summary.put("manualCount",manual);
        summary.put("unmeasuredCount",unmeasured);summary.put("overlapMinutes",rawTimedMinutes-timedMinutes);
        return summary;
    }

    private static Interval interval(Map<String,Object> row) {
        if(!"EXACT".equals(row.get("timeMode"))||row.get("startDate")==null||row.get("startTime")==null||row.get("endTime")==null)return null;
        try {
            LocalDate startDay=LocalDate.parse(row.get("startDate").toString());
            LocalDate endDay=row.get("endDate")==null?startDay:LocalDate.parse(row.get("endDate").toString());
            LocalDateTime start=startDay.atTime(LocalTime.parse(row.get("startTime").toString()));
            LocalDateTime end=endDay.atTime(LocalTime.parse(row.get("endTime").toString()));
            return end.isBefore(start)?null:new Interval(start,end);
        } catch(RuntimeException ignored){return null;}
    }

    private static Long enteredMinutes(Object raw) {
        if(raw==null)return null;
        try {long value=new BigDecimal(raw.toString()).longValueExact();return value<0?null:value;}
        catch(RuntimeException ignored){return null;}
    }
}
