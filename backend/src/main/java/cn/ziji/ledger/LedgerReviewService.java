package cn.ziji.ledger;

import cn.ziji.data.*;
import cn.ziji.web.ApiException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class LedgerReviewService {
    private final JdbcTemplate jdbc;
    private final DataService data;
    private final DataRepository repository;
    public LedgerReviewService(JdbcTemplate jdbc,DataService data,DataRepository repository) {
        this.jdbc=jdbc;this.data=data;this.repository=repository;
    }

    private Map<String,Object> active(String collection,String id) {
        var row=data.require(collection,id);
        if(Boolean.TRUE.equals(row.get("deleted"))) throw new ApiException(409,"记录已移入回收站，请先恢复");
        return row;
    }

    public List<Map<String,Object>> options(LocalDate date,String selected) {
        return jdbc.queryForList("""
                SELECT id,title,startDate FROM zj_journal WHERE id=? OR
                  (deleted=FALSE AND startDate<=? AND COALESCE(endDate,startDate)>=?
                   AND (category='TRAVEL' OR JSON_CONTAINS(tags,JSON_QUOTE('出差'))))
                ORDER BY startDate DESC,title,id
                """,selected==null?null:DataValidator.uuid(selected),date,date);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> trip(String id) {
        var trip=active("journal",id);
        String from=trip.get("startDate").toString(),to=Objects.toString(trip.get("endDate"),from);
        var expenses=jdbc.query("""
                SELECT * FROM zj_ledger WHERE deleted=FALSE AND direction='EXPENSE'
                AND (tripId=? OR `date` BETWEEN ? AND ?) ORDER BY `date`,createdAt,id
                """,(rs,n)->repository.mapRow("ledger",rs),id,from,to);
        var logs=jdbc.query("""
                SELECT j.id,j.title,j.category,j.startDate,j.endDate,j.taskId,
                       t.title AS taskTitle,t.status AS taskStatus,t.completedDate,t.deleted AS taskDeleted
                FROM zj_journal j LEFT JOIN zj_tasks t ON j.taskId=t.id
                WHERE j.deleted=FALSE AND j.id<>? AND j.startDate<=? AND COALESCE(j.endDate,j.startDate)>=?
                ORDER BY j.startDate,j.startTime,j.id
                """,(rs,n)->{
                    var row=new LinkedHashMap<String,Object>();
                    for(String key:List.of("id","title","category","startDate","endDate","taskId","taskTitle","taskStatus","completedDate"))row.put(key,rs.getString(key));
                    row.put("taskDeleted",rs.getBoolean("taskDeleted"));
                    return row;
                },id,to,from);
        return Map.of("trip",trip,"expenses",expenses,"logs",logs);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> editTrip(String id,LedgerReviewController.TripEdit input) {
        id=DataValidator.uuid(id);
        jdbc.queryForList("SELECT id FROM zj_journal WHERE id=? FOR UPDATE",String.class,id);
        var trip=active("journal",id);
        if(DataService.version(trip.get("version"))!=input.version())throw ApiException.conflict();
        if(input.changes()==null)throw new IllegalArgumentException("请先勾选实际属于行程的流水");
        String from=(String)trip.get("startDate"),to=Objects.toString(trip.get("endDate"),from);
        var seen=new HashSet<String>();
        for(var change:input.changes()) {
            if(change==null)throw new IllegalArgumentException("流水选择无效");
            String expenseId=DataValidator.uuid(change.id());
            if(!seen.add(expenseId))throw new IllegalArgumentException("请勿重复选择同一笔流水");
            var expense=active("ledger",expenseId);
            String owner=(String)expense.get("tripId"),date=(String)expense.get("date");
            if(!"EXPENSE".equals(expense.get("direction")) || owner!=null && !owner.equals(id))
                throw new ApiException(409,"这笔流水已变化，或已归入其他行程");
            if(owner==null && (date.compareTo(from)<0 || date.compareTo(to)>0))
                throw new ApiException(409,"流水已不在行程日期范围内，请重新核对");
            var patch=new LinkedHashMap<String,Object>();
            patch.put("version",change.version());
            patch.put("tripId",change.reimbursement()==null?null:id);
            patch.put("reimbursement",change.reimbursement());
            data.update("ledger",expenseId,patch);
        }
        return trip(id);
    }

    static LocalDate nextMonth(LocalDate due,int monthDay) {
        YearMonth next=YearMonth.from(due).plusMonths(1);
        return next.atDay(Math.min(next.lengthOfMonth(),monthDay));
    }

    static String paymentId(String ruleId,LocalDate due) {
        return UUID.nameUUIDFromBytes(("ziji/bills/"+ruleId+"/"+due).getBytes(StandardCharsets.UTF_8)).toString();
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> settle(String id,LedgerReviewController.Settlement input) {
        id=DataValidator.uuid(id);
        jdbc.queryForList("SELECT id FROM zj_bills WHERE id=? FOR UPDATE",String.class,id);
        var bill=active("bills",id);
        LocalDate due=input.dueDate(),today=LocalDate.now(DataValidator.ZONE);
        if(due==null || due.isAfter(today.plusDays(6))) throw new IllegalArgumentException("只能确认已到期或未来7天内的账单");
        LocalDate current=LocalDate.parse(bill.get("nextDue").toString());
        String paymentId=paymentId(id,due);
        if(!due.equals(current)) {
            var existing=data.find("ledger",paymentId);
            if(due.isBefore(current) && !input.skip() && existing!=null && !Boolean.TRUE.equals(existing.get("deleted")))
                return Map.of("bill",bill,"ledgerId",paymentId);
            throw ApiException.conflict();
        }
        if(DataService.version(bill.get("version"))!=input.version())throw ApiException.conflict();
        if(!input.skip()) {
            LocalDate paid=input.paidDate();
            if(paid==null || paid.isAfter(today))throw new IllegalArgumentException("请选择今天或更早的实际付款日期");
            data.create("ledger",Map.of("id",paymentId,"direction","EXPENSE","date",paid.toString(),
                    "amount",bill.get("amount"),"category",bill.get("category"),"notes",bill.get("title")));
        }
        int monthDay=((BigDecimal)bill.get("monthDay")).intValueExact();
        var updated=data.update("bills",id,Map.of("version",input.version(),"nextDue",nextMonth(due,monthDay).toString()));
        return input.skip()?Map.of("bill",updated):Map.of("bill",updated,"ledgerId",paymentId);
    }
}
