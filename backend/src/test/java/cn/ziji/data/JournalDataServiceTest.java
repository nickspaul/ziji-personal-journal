package cn.ziji.data;

import cn.ziji.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JournalDataServiceTest {
    private final SchemaRegistry registry=new SchemaRegistry();
    private final DataRepository repository=mock(DataRepository.class);
    private final Map<String,Map<String,Map<String,Object>>> store=new HashMap<>();
    private DataService service;
    @BeforeEach void setup(){
        registry.all().forEach(c->store.put(c.name(),new LinkedHashMap<>()));
        when(repository.find(anyString(),anyString())).thenAnswer(i->store.get(i.getArgument(0)).get(i.getArgument(1)));
        when(repository.list(anyString(),nullable(Boolean.class))).thenAnswer(i->{Boolean deleted=i.getArgument(1);return store.get(i.getArgument(0)).values().stream().filter(r->deleted==null||deleted.equals(r.get("deleted"))).toList();});
        doAnswer(i->{Map<String,Object> row=i.getArgument(1);store.get(i.getArgument(0)).put(row.get("id").toString(),new LinkedHashMap<>(row));return null;}).when(repository).insert(anyString(),anyMap());
        when(repository.update(anyString(),anyMap(),anyLong())).thenAnswer(i->{
            Map<String,Map<String,Object>> collection=store.get(i.getArgument(0));Map<String,Object> next=i.getArgument(1);var old=collection.get(next.get("id"));
            if(old==null||((Number)old.get("version")).longValue()!=(long)i.getArgument(2))return false;
            collection.put(next.get("id").toString(),new LinkedHashMap<>(next));return true;
        });
        when(repository.settings()).thenReturn(Map.of());service=new DataService(repository,registry,new DataValidator(registry));
    }
    private Map<String,Object> task(){return service.create("tasks",Map.of("title","项目 A 联调"));}
    private Map<String,Object> journal(Map<String,Object> task){return service.create("journal",Map.of("title","接口已经写完","startDate","2025-10-20","taskId",task.get("id")));}
    @Test void ordinaryStateAndNewWritesDoNotLoadTheEntireJournalOrOldBehavior(){
        task();verify(repository,never()).list(anyString(),any());
        clearInvocations(repository);var ordinary=service.state();assertFalse(ordinary.containsKey("journal"));assertFalse(ordinary.containsKey("tasks"));
        verify(repository,never()).list(eq("journal"),any());verify(repository,never()).list(eq("tasks"),any());
        var complete=service.state((Boolean)null);assertEquals(1,complete.get("tasks").size());assertTrue(complete.containsKey("journal"));
    }
    @Test void repeatedCreateAndNoOpUpdateProduceNoExtraRowsOrAudit(){
        String id=UUID.randomUUID().toString();var input=Map.<String,Object>of("id",id,"title","联调","tags",List.of("#项目 A"));
        var created=service.create("tasks",input);clearInvocations(repository);
        assertEquals(created,service.create("tasks",input));assertEquals(created,service.update("tasks",id,Map.of("version",1,"title","联调","tags",List.of("项目 a"))));
        verify(repository,never()).insert(anyString(),anyMap());verify(repository,never()).update(anyString(),anyMap(),anyLong());verify(repository,never()).audit(anyString(),anyString(),anyString(),any(),any());
        assertThrows(ApiException.class,()->service.create("tasks",Map.of("id",id,"title","另一项工作")));
    }
    @Test void completionDoesNotDuplicateJournalAndStaleEditsRemainProtected(){
        var task=task();var progress=journal(task);var done=service.update("tasks",task.get("id").toString(),Map.of("version",1,"status","DONE","completedDate","2025-10-21"));
        assertEquals(1,store.get("journal").size());assertEquals(progress,store.get("journal").get(progress.get("id")));
        clearInvocations(repository);assertEquals(done,service.update("tasks",task.get("id").toString(),Map.of("version",1,"status","DONE")));
        verify(repository,never()).update(anyString(),anyMap(),anyLong());
        assertThrows(ApiException.class,()->service.update("tasks",task.get("id").toString(),Map.of("version",1,"title","过期窗口覆盖")));
        var reopened=service.update("tasks",task.get("id").toString(),Map.of("version",2,"status","TODO"));assertNull(reopened.get("completedDate"));assertEquals(1,store.get("journal").size());
    }
    @Test void DeletedTaskKeepsItsHistoryButCannotReceiveNewAssociations(){
        var task=task();var progress=journal(task);service.delete("tasks",task.get("id").toString(),1);
        assertFalse((boolean)store.get("journal").get(progress.get("id")).get("deleted"));
        assertThrows(IllegalArgumentException.class,()->journal(task));
        var edited=service.update("journal",progress.get("id").toString(),Map.of("version",1,"content","补充当时的细节"));assertEquals(task.get("id"),edited.get("taskId"));
        service.delete("journal",progress.get("id").toString(),2);var restored=service.restore("journal",progress.get("id").toString(),3);assertFalse((boolean)restored.get("deleted"));assertEquals(task.get("id"),restored.get("taskId"));
    }
    @Test void MissingTaskReferenceIsRejectedWithoutWritingAnything(){
        assertThrows(IllegalArgumentException.class,()->service.create("journal",Map.of("title","进展","startDate","2025-10-20","taskId",UUID.randomUUID().toString())));
        verify(repository,never()).insert(anyString(),anyMap());
    }
    @Test void PinnedFiltersValidateAndUnchangedSettingsDoNotAudit(){
        assertThrows(IllegalArgumentException.class,()->service.validateSettings(Map.of("journalView","invalid")));
        assertThrows(IllegalArgumentException.class,()->service.validateSettings(Map.of("journalPinnedFilters","出差")));
        var next=service.updateSettings(Map.of("journalView","day"));assertEquals("day",next.get("journalView"));
        verify(repository,never()).settings(anyMap());verify(repository,never()).audit(anyString(),anyString(),anyString(),any(),any());
    }
    @Test void manualMinutesAreAuditedOnlyWhenChangedAndSurviveRecycle(){
        var task=task();var progress=journal(task);String id=progress.get("id").toString();
        var edited=service.update("journal",id,Map.of("version",1,"durationMinutes",45));
        assertEquals(new BigDecimal("45"),edited.get("durationMinutes"));assertNull(progress.get("durationMinutes"));
        verify(repository).audit(eq("UPDATE"),eq("journal"),eq(id),eq(progress),eq(edited));
        clearInvocations(repository);assertEquals(edited,service.update("journal",id,Map.of("version",2,"durationMinutes",45)));
        verify(repository,never()).update(anyString(),anyMap(),anyLong());verify(repository,never()).audit(anyString(),anyString(),anyString(),any(),any());
        service.delete("journal",id,2);var restored=service.restore("journal",id,3);
        assertEquals(new BigDecimal("45"),restored.get("durationMinutes"));assertEquals(task.get("id"),restored.get("taskId"));
    }
    @Test void computedEffortCannotBeWrittenIntoTaskOrJournal(){
        var task=task();var progress=journal(task);clearInvocations(repository);
        Map<String,Object> effort=Map.of("totalMinutes",999,"unmeasuredCount",0);
        assertThrows(IllegalArgumentException.class,()->service.update("tasks",task.get("id").toString(),Map.of("version",1,"effort",effort)));
        assertThrows(IllegalArgumentException.class,()->service.update("journal",progress.get("id").toString(),Map.of("version",1,"effort",effort)));
        verify(repository,never()).insert(anyString(),anyMap());verify(repository,never()).update(anyString(),anyMap(),anyLong());verify(repository,never()).audit(anyString(),anyString(),anyString(),any(),any());
    }
}
