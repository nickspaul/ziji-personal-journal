package cn.ziji.backup;

import cn.ziji.data.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JournalBackupServiceTest {
    @TempDir Path temporary;
    private final SchemaRegistry registry=new SchemaRegistry();
    private final DataRepository repository=mock(DataRepository.class);
    private final Map<String,Map<String,Map<String,Object>>> store=new LinkedHashMap<>();
    private final Map<String,Object> settings=new LinkedHashMap<>();
    private DataService data;
    private BackupService service;
    @BeforeEach void setup(){
        registry.all().forEach(c->store.put(c.name(),new LinkedHashMap<>()));
        when(repository.find(anyString(),anyString())).thenAnswer(i->store.get(i.getArgument(0)).get(i.getArgument(1)));
        when(repository.list(anyString(),nullable(Boolean.class))).thenAnswer(i->{Boolean deleted=i.getArgument(1);return store.get(i.getArgument(0)).values().stream().filter(r->deleted==null||deleted.equals(r.get("deleted"))).toList();});
        doAnswer(i->{Map<String,Object> row=i.getArgument(1);store.get(i.getArgument(0)).put(row.get("id").toString(),new LinkedHashMap<>(row));return null;}).when(repository).insert(anyString(),anyMap());
        when(repository.settings()).thenAnswer(i->new LinkedHashMap<>(settings));
        doAnswer(i->{settings.clear();settings.putAll(i.getArgument(0));return null;}).when(repository).settings(anyMap());
        var validator=new DataValidator(registry);data=new DataService(repository,registry,validator);
        service=new BackupService(data,repository,registry,validator,new ObjectMapper(),mock(JdbcTemplate.class),temporary.toString());
    }
    private Map<String,Object> row(){return new LinkedHashMap<>(Map.of("id",UUID.randomUUID().toString(),"version",1,"createdAt","2025-10-20T21:00:00","updatedAt","2025-10-20T21:00:00","deleted",false));}
    private Map<String,Object> task(){var row=row();row.putAll(Map.of("title","项目 A 联调","status","DONE","completedDate","2025-10-20","important",true,"tags",List.of("项目 a")));return row;}
    private Map<String,Object> journal(Map<String,Object> task){var row=row();row.putAll(Map.of("title","联调已经完成","startDate","2025-10-20","endDate","2025-10-20","timeMode","EXACT","startTime","14:15","endTime","15:45","people",List.of("Alice","小王"),"tags",List.of("项目 a"),"taskId",task.get("id")));return row;}
    private Map<String,Object> backup(Map<String,Object> collections){return new LinkedHashMap<>(Map.of("format","ziji-backup","schemaVersion",1,"collections",collections));}
    @Test void manualCashflowRoundTripsInBackupAndStaysOutOfBehaviorStatistics() {
        var expense = data.create("ledger", Map.of("date","2025-10-20","category","晚餐","amount","12.30"));
        var income = data.create("ledger", Map.of("date","2025-10-20","direction","INCOME","category","报销","amount","8.25"));
        verify(repository,never()).list(anyString(),any());
        assertFalse(data.state().containsKey("ledger"));
        var exported=service.export();
        store.values().forEach(Map::clear);
        assertEquals(2,service.restore(exported,"merge").get("imported"));
        assertEquals(expense,store.get("ledger").get(expense.get("id")));
        assertEquals(income,store.get("ledger").get(income.get("id")));
        assertEquals(0,service.restore(exported,"merge").get("imported"));
    }
    @Test void tripLinkAndUpcomingRuleRoundTripSeparatelyFromActualExpenses(){
        var trip=data.create("journal",Map.of("title","出差","startDate","2025-10-20","category","TRAVEL"));
        var expense=data.create("ledger",Map.of("date","2025-10-20","category","交通","amount","18.30","tripId",trip.get("id"),"reimbursement","PENDING"));
        var rule=data.create("bills",Map.of("title","月租","amount","39.90","category","通信","monthDay",31,"nextDue","2025-11-30"));
        assertFalse(data.state().containsKey("bills"));
        var exported=service.export();store.values().forEach(Map::clear);
        assertEquals(true,service.preview(exported).get("valid"));assertEquals(3,service.restore(exported,"merge").get("imported"));
        assertEquals(1,store.get("ledger").size());
        assertEquals(trip.get("id"),store.get("ledger").get(expense.get("id")).get("tripId"));
        assertEquals("PENDING",store.get("ledger").get(expense.get("id")).get("reimbursement"));
        assertEquals(rule.get("nextDue"),store.get("bills").get(rule.get("id")).get("nextDue"));
    }
    @Test void legacyBackupWithoutNewCollectionsStillRestores(){
        var old=row();old.putAll(Map.of("measuredAt","2025-10-20T21:00:00","heightCm",172,"weightKg",68.5,"sex","UNSPECIFIED"));
        var backup=backup(Map.of("body",List.of(old)));assertEquals(true,service.preview(backup).get("valid"));
        assertEquals(1,service.restore(backup,"merge").get("imported"));assertEquals(1,store.get("body").size());
        assertTrue(store.get("journal").isEmpty());assertTrue(store.get("tasks").isEmpty());
    }
    @Test void activeJournalCanRestoreItsHistoricalReferenceToDeletedTask(){
        var task=task();task.put("deleted",true);var journal=journal(task);
        var backup=backup(Map.of("journal",List.of(journal),"tasks",List.of(task)));
        var preview=service.preview(backup);assertEquals(true,preview.get("valid"),preview.toString());
        assertEquals(2,service.restore(backup,"merge").get("imported"));
        var restored=store.get("journal").get(journal.get("id"));assertEquals(task.get("id"),restored.get("taskId"));assertEquals(false,restored.get("deleted"));
        assertEquals("2025-10-20",restored.get("endDate"));assertEquals("14:15",restored.get("startTime"));assertEquals(List.of("Alice","小王"),restored.get("people"));assertEquals(List.of("项目 a"),restored.get("tags"));
        var order=inOrder(repository);order.verify(repository).insert(eq("tasks"),anyMap());order.verify(repository).insert(eq("journal"),anyMap());
        assertEquals(0,service.restore(backup,"merge").get("imported"));assertEquals(1,store.get("journal").size());
        assertThrows(IllegalArgumentException.class,()->data.create("journal",Map.of("title","新的关联","taskId",task.get("id"))));
    }
    @Test void journalWithMissingTaskAndMalformedArraysFailsBeforeInsertion(){
        var task=task();var journal=journal(task);var backup=backup(Map.of("journal",List.of(journal)));
        assertEquals(false,service.preview(backup).get("valid"));assertThrows(IllegalArgumentException.class,()->service.restore(backup,"merge"));
        journal.put("tags","项目 a");assertEquals(false,service.preview(backup(Map.of("journal",List.of(journal),"tasks",List.of(task)))).get("valid"));
        verify(repository,never()).insert(anyString(),anyMap());
    }
    @Test void invalidPinnedFilterIsReportedByPreviewRatherThanDuringRestore(){
        var backup=backup(Map.of());backup.put("settings",Map.of("journalPinnedFilters",List.of(Map.of("id",UUID.randomUUID().toString(),"name","项目 A","query",Map.of("sql","SELECT 1")))));
        var preview=service.preview(backup);assertEquals(false,preview.get("valid"));assertTrue(preview.get("errors").toString().contains("生活日志设置"));
        backup.put("settings",Map.of("journalView","unknown"));assertEquals(false,service.preview(backup).get("valid"));
    }
    @Test void vocabularySurvivesBackupIncludingUnusedAndRetiredNames(){
        var people=java.util.stream.IntStream.range(0,106).mapToObj(i->"同行人"+i).toList();
        var vocabulary=Map.of("tag",Map.of("added",List.of("#Work"),"hidden",List.of("旧标签")),
                              "person",Map.of("added",people));
        data.updateSettings(Map.of("journalVocabulary",vocabulary));
        var expected=data.settings().get("journalVocabulary");
        assertEquals(Map.of("tag",Map.of("added",List.of("work"),"hidden",List.of("旧标签")),
                            "person",Map.of("added",people)),expected);
        var backup=service.export();
        settings.clear();
        assertEquals(true,service.preview(backup).get("valid"));
        service.restore(backup,"merge");
        assertEquals(expected,data.settings().get("journalVocabulary"));
        backup.put("settings",Map.of("journalVocabulary",Map.of("tag",Map.of("added",List.of("#Work"),"hidden",List.of("work")))));
        assertEquals(false,service.preview(backup).get("valid"));
    }
    @Test void exportIncludesNewCollectionsAndPinnedFiltersWithoutAddingThemToBootstrap(){
        var task=data.create("tasks",Map.of("title","项目 A 联调"));data.create("journal",Map.of("title","联调进展","startDate","2025-10-20","taskId",task.get("id"),"tags",List.of("项目 A")));
        var pin=Map.of("id",UUID.randomUUID().toString(),"name","项目 A","query",Map.of("collection","journal","tags",List.of("项目 A")));
        data.updateSettings(Map.of("journalPinnedFilters",List.of(pin),"journalView","search"));
        assertFalse(data.state().containsKey("journal"));assertFalse(data.state().containsKey("tasks"));
        var exported=service.export();assertEquals(true,service.preview(exported).get("valid"));
        @SuppressWarnings("unchecked") var collections=(Map<String,List<Map<String,Object>>>)exported.get("collections");
        assertEquals(1,collections.get("journal").size());assertEquals(1,collections.get("tasks").size());
        assertEquals(data.settings(),exported.get("settings"));
    }
    @Test void manualMinutesExportAndRestoreWithoutReconstructingExactTimes(){
        var task=data.create("tasks",Map.of("title","复习线性代数"));
        var journal=data.create("journal",Map.of("title","整理矩阵笔记","startDate","2025-10-20","timeMode","PERIOD","period","EVENING","taskId",task.get("id"),"durationMinutes",80));
        var exported=service.export();store.values().forEach(Map::clear);
        assertEquals(true,service.preview(exported).get("valid"));assertEquals(2,service.restore(exported,"merge").get("imported"));
        var restored=store.get("journal").get(journal.get("id"));
        assertEquals(new BigDecimal("80"),restored.get("durationMinutes"));assertEquals("EVENING",restored.get("period"));assertNull(restored.get("startTime"));assertNull(restored.get("endTime"));
        assertEquals(journal.get("version"),restored.get("version"));assertEquals(journal.get("createdAt"),restored.get("createdAt"));
    }
    @Test void olderJournalBackupWithoutDurationStillMatchesTheExistingRow(){
        var task=task();var journal=journal(task);var backup=backup(Map.of("journal",List.of(journal),"tasks",List.of(task)));
        assertEquals(2,service.restore(backup,"merge").get("imported"));
        var restored=store.get("journal").get(journal.get("id"));assertTrue(restored.containsKey("durationMinutes"));assertNull(restored.get("durationMinutes"));
        var preview=service.preview(backup);assertEquals(true,preview.get("valid"));assertEquals(List.of(),preview.get("conflicts"));assertEquals(0,preview.get("newRecords"));
    }
    @Test void conflictingDurationBackupAndDerivedEffortAreRejectedBeforeAnyInsert(){
        var task=task();var journal=journal(task);journal.put("durationMinutes",90);
        var backup=backup(Map.of("journal",List.of(journal),"tasks",List.of(task)));
        assertEquals(false,service.preview(backup).get("valid"));assertThrows(IllegalArgumentException.class,()->service.restore(backup,"merge"));
        journal.remove("durationMinutes");task.put("effort",Map.of("totalMinutes",90));assertEquals(false,service.preview(backup).get("valid"));
        verify(repository,never()).insert(anyString(),anyMap());
    }
}
