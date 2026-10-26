package cn.ziji.data;

import cn.ziji.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MissingCostRepairTest {
    private final SchemaRegistry registry=new SchemaRegistry();
    private DataRepository repository;
    private DataService service;
    private final Map<String,List<Map<String,Object>>> rows=new LinkedHashMap<>();
    private Map<String,Object> product;

    @BeforeEach void setup() {
        repository=mock(DataRepository.class);
        registry.all().forEach(c->rows.put(c.name(),new ArrayList<>()));
        when(repository.list(anyString(),eq(false))).thenAnswer(i->rows.get(i.getArgument(0)).stream()
            .filter(r->!Boolean.TRUE.equals(r.get("deleted"))).toList());
        when(repository.update(anyString(),anyMap(),anyLong())).thenAnswer(i->{
            List<Map<String,Object>> collection=rows.get(i.getArgument(0));
            Map<String,Object> next=i.getArgument(1);
            for(int n=0;n<collection.size();n++)if(Objects.equals(collection.get(n).get("id"),next.get("id"))){
                if(((Number)collection.get(n).get("version")).longValue()!=(long)i.getArgument(2))return false;
                collection.set(n,new LinkedHashMap<>(next));return true;
            }
            return false;
        });
        service=new DataService(repository,registry,new DataValidator(registry));
        product=row();product.putAll(Map.of("kind","SMOKING","brand","测试","name","20根","unitCount",20,
            "packagePrice",40,"priceConfirmed",true,"priceType","PERSONAL"));rows.get("products").add(product);
    }
    private Map<String,Object> row(){return new LinkedHashMap<>(Map.of("id",UUID.randomUUID().toString(),"version",1L,
        "createdAt","2026-10-01T12:00:00","updatedAt","2026-10-01T12:00:00","deleted",false));}
    private Map<String,Object> smoke(){var r=row();r.putAll(Map.of("productId",product.get("id"),"occurredAt","2026-10-01T12:00:00",
        "timePrecision","APPROX","count",3,"source","SELF","snapshotUnitCount",20,"snapshotName","旧名称","snapshotTarMg",5));
        r.put("snapshotUnitPrice",null);r.put("cost",null);rows.get("smoking").add(r);return r;}
    @Test void repairsOnlyMissingCostsAndKeepsKnownAndDeletedRows() {
        var missing=smoke();var known=smoke();known.put("cost",BigDecimal.ZERO);known.put("snapshotUnitPrice",BigDecimal.ZERO);
        var deleted=smoke();deleted.put("deleted",true);var noProduct=smoke();noProduct.put("productId",null);
        assertEquals(1,service.repairMissingCosts());
        var repaired=rows.get("smoking").get(0);assertEquals(0,new BigDecimal(repaired.get("cost").toString()).compareTo(new BigDecimal("6")));
        assertEquals(2L,repaired.get("version"));assertEquals("旧名称",repaired.get("snapshotName"));assertEquals(5,repaired.get("snapshotTarMg"));
        assertEquals(missing.get("occurredAt"),repaired.get("occurredAt"));assertSame(known,rows.get("smoking").get(1));
        assertSame(deleted,rows.get("smoking").get(2));assertSame(noProduct,rows.get("smoking").get(3));
        verify(repository,times(1)).audit(eq("FILL_MISSING_COST"),eq("smoking"),eq(missing.get("id").toString()),eq(missing),eq(repaired));
        verify(repository,never()).insert(anyString(),anyMap());
    }
    @Test void repeatedStartupRepairCreatesNoFurtherWritesOrAudit() {
        smoke();assertEquals(1,service.repairMissingCosts());clearInvocations(repository);
        assertEquals(0,service.repairMissingCosts());
        verify(repository,never()).update(anyString(),anyMap(),anyLong());
        verify(repository,never()).audit(anyString(),anyString(),anyString(),any(),any());
    }
    @Test void unconfirmedProductDoesNotResurrectOldPriceHistory() {
        smoke();product.put("priceConfirmed",false);
        rows.get("prices").add(new LinkedHashMap<>(Map.of("id",UUID.randomUUID().toString(),"productId",product.get("id"),
            "amount",20,"verified",true,"priceType","PERSONAL","currency","CNY","effectiveDate","2026-09-01")));
        assertEquals(0,service.repairMissingCosts());verify(repository,never()).update(anyString(),anyMap(),anyLong());
    }
    @Test void concurrentEditIsNotOverwrittenOrAuditedAsSuccessful() {
        smoke();doReturn(false).when(repository).update(anyString(),anyMap(),anyLong());
        assertThrows(ApiException.class,()->service.repairMissingCosts());
        verify(repository,never()).audit(anyString(),anyString(),anyString(),any(),any());
    }
}
