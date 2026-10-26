package cn.ziji.data;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JournalDataValidationTest {
    private final DataValidator validator=new DataValidator(new SchemaRegistry());
    private Map<String,Object> journal(){return new LinkedHashMap<>(Map.of("title","午饭吃牛肉面","startDate","2025-10-20"));}
    private Map<String,Object> validImportedJournal(){var row=journal();row.putAll(Map.of("timeMode","DATE","tags",List.of("出差"),"people",List.of("小王")));return row;}
    @Test void dateOnlyRecordNeverInventsTimeAndRequiresTitle(){
        var row=validator.normalize("journal",journal(),null,false);
        assertEquals("DATE",row.get("timeMode"));assertNull(row.get("startTime"));assertNull(row.get("endTime"));assertNull(row.get("endDate"));assertEquals(List.of(),row.get("tags"));
        assertThrows(IllegalArgumentException.class,()->validator.normalize("journal",Map.of("title","  "),null,false));
    }
    @Test void arraysAreStructuredNormalizedAndBounded(){
        var input=journal();input.put("tags",List.of(" #项目 A ","项目 a"," 出差 ","#出差"," "));input.put("people",List.of(" 小王 ","小王","Alice"));
        var row=validator.normalize("journal",input,null,false);
        assertEquals(List.of("项目 a","出差"),row.get("tags"));assertEquals(List.of("小王","Alice"),row.get("people"));
        for(Object invalid:List.of("出差,朋友",List.of(3),List.of("x".repeat(81)),Collections.nCopies(31,"x"),List.of("a\nb"))) {
            input.put("tags",invalid);assertThrows(IllegalArgumentException.class,()->validator.normalize("journal",input,null,false));
        }
    }
    @Test void periodDoesNotPretendToBePreciseAndChangingModeClearsUnusedFields(){
        var input=journal();input.putAll(Map.of("timeMode","EXACT","startTime","14:00","endTime","15:30"));
        var old=validator.normalize("journal",input,null,false);
        var edited=validator.normalize("journal",Map.of("timeMode","PERIOD","period","AFTERNOON"),old,false);
        assertEquals("AFTERNOON",edited.get("period"));assertNull(edited.get("startTime"));assertNull(edited.get("endTime"));assertEquals("14:00",old.get("startTime"));
        var date=validator.normalize("journal",Map.of("timeMode","DATE"),edited,false);assertNull(date.get("period"));
        assertThrows(IllegalArgumentException.class,()->validator.normalize("journal",Map.of("title","事后补记","timeMode","PERIOD"),null,false));
    }
    @Test void crossMidnightRequiresExplicitEndDateAndFutureFactsAreRejected(){
        var input=journal();input.putAll(Map.of("timeMode","EXACT","startTime","23:30","endTime","01:00"));
        assertThrows(IllegalArgumentException.class,()->validator.normalize("journal",input,null,false));
        input.put("endDate","2025-10-21");assertEquals("2025-10-21",validator.normalize("journal",input,null,false).get("endDate"));
        input.put("endDate","2025-10-19");assertThrows(IllegalArgumentException.class,()->validator.normalize("journal",input,null,false));
        var future=journal();future.put("startDate",LocalDate.now(DataValidator.ZONE).plusDays(1).toString());
        assertThrows(IllegalArgumentException.class,()->validator.normalize("journal",future,null,false));
    }
    @Test void malformedTimeAndUnknownBusinessFieldsAreRejected(){
        for(String time:List.of("24:00","9:30","12:30:20","garbage")) {
            var input=journal();input.putAll(Map.of("timeMode","EXACT","startTime",time));
            assertThrows(IllegalArgumentException.class,()->validator.normalize("journal",input,null,false));
        }
        var input=journal();input.put("cost",50);assertThrows(IllegalArgumentException.class,()->validator.normalize("journal",input,null,false));
        assertThrows(IllegalArgumentException.class,()->validator.normalize("journal",Map.of("title","进展","taskId","invalid"),null,false));
    }
    @Test void completionIsActualDateAndReopeningClearsCurrentCompletion(){
        var task=validator.normalize("tasks",Map.of("title","完成联调"),null,false);
        assertEquals("TODO",task.get("status"));assertEquals(false,task.get("important"));assertNull(task.get("plannedDate"));
        var done=validator.normalize("tasks",Map.of("status","DONE"),task,false);
        assertEquals(LocalDate.now(DataValidator.ZONE).toString(),done.get("completedDate"));
        var reopened=validator.normalize("tasks",Map.of("status","DOING"),done,false);assertNull(reopened.get("completedDate"));
        assertThrows(IllegalArgumentException.class,()->validator.normalize("tasks",Map.of("status","DONE","completedDate",LocalDate.now(DataValidator.ZONE).plusDays(1).toString()),task,false));
        var future=validator.normalize("tasks",Map.of("plannedDate",LocalDate.now(DataValidator.ZONE).plusDays(1).toString()),task,false);assertNotNull(future.get("plannedDate"));
    }
    @Test void importDoesNotFillMissingRequiredFactsOrSilentlyClearConflicts(){
        var imported=validImportedJournal();imported.put("endDate","2025-10-20");
        assertEquals("2025-10-20",validator.normalize("journal",imported,null,true).get("endDate"));
        imported.put("startTime","14:00");assertThrows(IllegalArgumentException.class,()->validator.normalize("journal",imported,null,true));
        assertThrows(IllegalArgumentException.class,()->validator.normalize("journal",journal(),null,true));
        var done=new LinkedHashMap<String,Object>(Map.of("title","完成联调","status","DONE","important",false));
        assertThrows(IllegalArgumentException.class,()->validator.normalize("tasks",done,null,true));
        done.put("completedDate","2025-10-21");assertEquals("2025-10-21",validator.normalize("tasks",done,null,true).get("completedDate"));
    }
    @Test void pinnedQueriesUseBoundedStructuredWhitelist(){
        var pin=Map.<String,Object>of("id",UUID.randomUUID().toString(),"name"," 出差日期 ","query",Map.of("collection","journal","tags",List.of(" #出差 "),"tagMode","all"));
        var normalized=DataService.validateJournalPins(List.of(pin));assertEquals("出差日期",normalized.get(0).get("name"));
        assertThrows(IllegalArgumentException.class,()->DataService.validateJournalPins(Collections.nCopies(13,pin)));
        assertThrows(IllegalArgumentException.class,()->DataService.validateJournalPins(List.of(pin,pin)));
        assertThrows(IllegalArgumentException.class,()->DataService.validateJournalPins(List.of(Map.of("id",UUID.randomUUID().toString(),"name","危险参数","query",Map.of("sql","DROP TABLE")))));
        assertThrows(IllegalArgumentException.class,()->DataService.validateJournalPins(List.of(Map.of("id",UUID.randomUUID().toString(),"name","错误日期","query",Map.of("from","2025-10-21","to","2025-10-20")))));
    }
    @Test void manualDurationPreservesImpreciseTimeWithoutInventingEndpoints(){
        var input=journal();input.put("durationMinutes",90);
        var date=validator.normalize("journal",input,null,false);
        assertEquals(new BigDecimal("90"),date.get("durationMinutes"));assertNull(date.get("startTime"));assertNull(date.get("endTime"));
        input.putAll(Map.of("timeMode","PERIOD","period","AFTERNOON"));
        var period=validator.normalize("journal",input,null,false);
        assertEquals("AFTERNOON",period.get("period"));assertEquals(new BigDecimal("90"),period.get("durationMinutes"));assertNull(period.get("startTime"));
        input.putAll(Map.of("timeMode","EXACT","startTime","14:00"));
        var incomplete=validator.normalize("journal",input,null,false);
        assertEquals("14:00",incomplete.get("startTime"));assertNull(incomplete.get("endTime"));assertEquals(new BigDecimal("90"),incomplete.get("durationMinutes"));
    }
    @Test void manualDurationUsesWholePositiveMinutesWithinTheRecordedDays(){
        for(Object invalid:List.of(0,-1,1.5,1441,10081,"NaN",true)) {
            var input=journal();input.put("durationMinutes",invalid);
            assertThrows(IllegalArgumentException.class,()->validator.normalize("journal",input,null,false),String.valueOf(invalid));
        }
        var input=journal();input.put("durationMinutes",1440);
        assertEquals(new BigDecimal("1440"),validator.normalize("journal",input,null,false).get("durationMinutes"));
        input.put("endDate","2025-10-26");input.put("durationMinutes",10080);
        assertEquals(new BigDecimal("10080"),validator.normalize("journal",input,null,false).get("durationMinutes"));
        input.put("endDate","2025-10-25");assertThrows(IllegalArgumentException.class,()->validator.normalize("journal",input,null,false));
    }
    @Test void completeExactTimesReplaceManualMinutesWithoutChangingPreviousRecord(){
        var input=journal();input.put("durationMinutes",75);var previous=validator.normalize("journal",input,null,false);
        var next=validator.normalize("journal",Map.of("timeMode","EXACT","startTime","14:00","endTime","15:30"),previous,false);
        assertNull(next.get("durationMinutes"));assertEquals("14:00",next.get("startTime"));assertEquals("15:30",next.get("endTime"));
        assertEquals(new BigDecimal("75"),previous.get("durationMinutes"));assertNull(previous.get("startTime"));
    }
    @Test void durationImportPreservesManualValuesAndRejectsConflictingSources(){
        var old=validImportedJournal();assertNull(validator.normalize("journal",old,null,true).get("durationMinutes"));
        old.put("durationMinutes",45);assertEquals(new BigDecimal("45"),validator.normalize("journal",old,null,true).get("durationMinutes"));
        old.putAll(Map.of("timeMode","EXACT","startTime","14:00","endTime","14:45"));
        assertThrows(IllegalArgumentException.class,()->validator.normalize("journal",old,null,true));
        assertEquals(45,old.get("durationMinutes"),"导入校验不应静默修改输入备份");
    }
    @Test void privateRecordDurationRetainsItsPreviousFractionalAndZeroRules(){
        for(Object duration:List.of(0,0.5,1440)) {
            var row=validator.normalize("intimacy",Map.of("occurredAt","2025-10-20T14:00:00","durationMinutes",duration),null,false);
            assertEquals(0,new BigDecimal(duration.toString()).compareTo((BigDecimal)row.get("durationMinutes")));
        }
        assertThrows(IllegalArgumentException.class,()->validator.normalize("intimacy",Map.of("durationMinutes",1441),null,false));
    }
}
