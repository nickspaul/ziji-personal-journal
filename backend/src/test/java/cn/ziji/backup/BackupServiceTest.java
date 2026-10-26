package cn.ziji.backup;

import cn.ziji.data.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BackupServiceTest {
    private BackupService service;private DataRepository repository;private final SchemaRegistry registry=new SchemaRegistry();
    @BeforeEach void setup(){DataService data=mock(DataService.class);when(data.state((Boolean)null)).thenAnswer(i->{Map<String,List<Map<String,Object>>> state=new LinkedHashMap<>();registry.all().forEach(c->state.put(c.name(),new ArrayList<>()));return state;});repository=mock(DataRepository.class);service=new BackupService(data,repository,registry,new DataValidator(registry),new ObjectMapper(),mock(JdbcTemplate.class),"./target/test-backups");}
    Map<String,Object> row(){Map<String,Object> r=new LinkedHashMap<>();r.put("id",UUID.randomUUID().toString());r.put("version",1);r.put("createdAt","2026-10-21T20:00:00");r.put("updatedAt","2026-10-21T20:00:00");r.put("deleted",false);return r;}
    Map<String,Object> backup(){var drink=row();drink.putAll(Map.of("sessionId",UUID.randomUUID().toString(),"occurredAt","2026-10-21T20:00:00","timePrecision","EXACT","inputMode","ML","volumeMl",250,"bottleMl",330,"abv",5,"unitPrice",5));drink.putAll(Map.of("snapshotBottleMl",330,"snapshotAbv",5,"snapshotUnitPrice",5,"actualMl",250,"ethanolGrams",9.8625,"cost",3.79,"costType","OVERRIDE"));return new LinkedHashMap<>(Map.of("format","ziji-backup","schemaVersion",1,"collections",Map.of("drinks",List.of(drink))));}
    @SuppressWarnings("unchecked") Map<String,Object> drink(Map<String,Object> b){return ((List<Map<String,Object>>)((Map<String,Object>)b.get("collections")).get("drinks")).get(0);}
    @Test void acceptsPreviouslyRoundedMoney(){var result=service.preview(backup());assertEquals(true,result.get("valid"),result.toString());}
    @Test void rejectsStringWhereCostMustBeNumeric(){var b=backup();drink(b).put("cost","3.79");var result=service.preview(b);assertEquals(false,result.get("valid"));assertTrue(result.get("errors").toString().contains("应为数字"));}
    @Test void rejectsIncorrectEthanol(){var b=backup();drink(b).put("ethanolGrams",99);var result=service.preview(b);assertEquals(false,result.get("valid"));assertTrue(result.get("errors").toString().contains("纯酒精"));}
    @Test void acceptsFortyTwoDegreeEquivalent(){var b=backup();drink(b).put("ethanolEquivalent42Ml",29.7619);assertEquals(true,service.preview(b).get("valid"));}
    @Test void rejectsIncorrectFortyTwoDegreeEquivalent(){var b=backup();drink(b).put("ethanolEquivalent42Ml",999);var result=service.preview(b);assertEquals(false,result.get("valid"));assertTrue(result.get("errors").toString().contains("42度"));}
    @Test void validatesAuditBeforeRestore(){var b=backup();b.put("audit",List.of(Map.of("id","invalid")));var result=service.preview(b);assertEquals(false,result.get("valid"));assertTrue(result.get("errors").toString().contains("操作历史"));}
    Map<String,Object> bodyBackup(boolean withNewMetrics){
        var body=row();body.putAll(Map.of("measuredAt","2026-10-21T21:32:15","heightCm",172,"weightKg",68.5,"age",30,"sex","MALE","notes","原测量记录"));
        if(withNewMetrics)body.putAll(Map.of("bodyFatPct",19.2,"waistCm",81.5));
        return new LinkedHashMap<>(Map.of("format","ziji-backup","schemaVersion",1,"collections",Map.of("body",List.of(body))));
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    @Test void restoresNewBodyMetricsWithoutLosingPrecision(){
        var b=bodyBackup(true);assertEquals(true,service.preview(b).get("valid"));
        assertEquals(1,service.restore(b,"merge").get("imported"));
        ArgumentCaptor<Map<String,Object>> captured=ArgumentCaptor.forClass(Map.class);verify(repository).insert(eq("body"),captured.capture());
        var restored=captured.getValue();assertEquals("19.2",restored.get("bodyFatPct").toString());assertEquals("81.5",restored.get("waistCm").toString());
        assertEquals("2026-10-21T21:32:15",restored.get("measuredAt"));assertEquals("原测量记录",restored.get("notes"));
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    @Test void restoresLegacyBodyBackupWithUnknownNewMetrics(){
        var b=bodyBackup(false);assertEquals(true,service.preview(b).get("valid"));
        assertEquals(1,service.restore(b,"merge").get("imported"));
        ArgumentCaptor<Map<String,Object>> captured=ArgumentCaptor.forClass(Map.class);verify(repository).insert(eq("body"),captured.capture());
        var restored=captured.getValue();assertNull(restored.get("bodyFatPct"));assertNull(restored.get("waistCm"));
        assertEquals("68.5",restored.get("weightKg").toString());assertEquals("172",restored.get("heightCm").toString());
    }
    @SuppressWarnings("unchecked")
    @Test void rejectsOutOfRangeBodyMetricsFromBackup(){
        var b=bodyBackup(true);var body=((List<Map<String,Object>>)((Map<String,Object>)b.get("collections")).get("body")).get(0);body.put("bodyFatPct",101);
        assertEquals(false,service.preview(b).get("valid"));
    }
    @Test void acceptsApproximateDrinkTimeInBackup(){var b=backup();drink(b).put("timePrecision","APPROX");assertEquals(true,service.preview(b).get("valid"));}
}
