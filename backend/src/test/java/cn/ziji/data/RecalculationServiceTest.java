package cn.ziji.data;

import cn.ziji.domain.BehaviorCalculator;
import cn.ziji.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecalculationServiceTest {
    private static final String PRODUCT_ID="42f45d54-86a4-483a-97cd-3b2f307e6160";
    private static final String RECORD_ID="825c2615-22cc-4fd7-a15d-8cafb6dbf070";
    private final DataService data=mock(DataService.class);
    private final DataRepository repository=mock(DataRepository.class);
    private final DataValidator validator=new DataValidator(new SchemaRegistry());
    private final Map<String,List<Map<String,Object>>> state=new LinkedHashMap<>();
    private final Map<String,Object> product=new LinkedHashMap<>();
    private RecalculationService service;
    private Map<String,Object> record;

    @BeforeEach
    void setup() {
        product.putAll(Map.of("id",PRODUCT_ID,"version",1L,"kind","SMOKING","name","测试香烟",
            "unitCount",new BigDecimal("20.000000"),"packagePrice",new BigDecimal("20.000000"),
            "priceConfirmed",true,"priceType","PERSONAL"));
        state.put("products",List.of(product));state.put("prices",List.of());
        state.put("smoking",new ArrayList<>());state.put("drinks",List.of());
        Map<String,Object> raw=validator.normalize("smoking",Map.of("productId",PRODUCT_ID,
            "occurredAt","2026-10-24T10:00:00","timePrecision","EXACT","count",3,"source","SELF"),null,false);
        record=new LinkedHashMap<>(BehaviorCalculator.enrich("smoking",raw,null,state));
        record.putAll(Map.of("id",RECORD_ID,"version",1L,"createdAt","2026-10-24T10:01:00",
            "updatedAt","2026-10-24T10:01:00","deleted",false));
        state.get("smoking").add(record);
        when(data.require("products",PRODUCT_ID)).thenReturn(product);
        when(data.state()).thenReturn(state);
        ObjectMapper json=new ObjectMapper();
        when(repository.json(any())).thenAnswer(call->json.writeValueAsString(call.getArgument(0)));
        service=new RecalculationService(data,repository,validator);
    }

    private Map<String,Object> request() {
        return new LinkedHashMap<>(Map.of("sourceCollection","products","sourceId",PRODUCT_ID));
    }

    private Map<String,Object> applyRequest(Map<String,Object> preview) {
        Map<String,Object> request=request();request.put("previewToken",preview.get("previewToken"));return request;
    }

    @Test
    void equivalentNumbersDoNotCreateUpdatesOrAuditEntries() {
        record.put("cost",3.0);record.put("snapshotUnitPrice",1);
        record.put("snapshotUnitCount",20L);record.put("count",new BigDecimal("3.000000"));
        Map<String,Object> preview=service.preview(request());
        assertEquals(0,preview.get("count"));
        assertEquals(List.of(),preview.get("rows"));
        assertEquals(0,service.apply(applyRequest(preview)).get("updated"));
        verify(repository,never()).update(anyString(),anyMap(),anyLong());
        verify(repository,never()).audit(anyString(),anyString(),anyString(),any(),any());
        assertEquals(1L,record.get("version"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void genuineChangeIsAppliedOnceAndRepeatedRecalculationIsEmpty() {
        product.put("packagePrice",new BigDecimal("40"));
        when(repository.update(eq("smoking"),anyMap(),eq(1L))).thenAnswer(call->{
            Map<String,Object> saved=new LinkedHashMap<>((Map<String,Object>)call.getArgument(1));
            state.get("smoking").set(0,saved);return true;
        });
        Map<String,Object> preview=service.preview(request());
        assertEquals(1,preview.get("count"));
        Map<String,Object> change=((List<Map<String,Object>>)preview.get("rows")).get(0);
        Map<String,Object> after=(Map<String,Object>)change.get("after");
        assertEquals(0,new BigDecimal("6").compareTo(new BigDecimal(after.get("cost").toString())));
        assertEquals(1,service.apply(applyRequest(preview)).get("updated"));
        assertEquals(2L,state.get("smoking").get(0).get("version"));
        Map<String,Object> repeated=service.preview(request());
        assertEquals(0,repeated.get("count"));
        assertEquals(0,service.apply(applyRequest(repeated)).get("updated"));
        verify(repository,times(1)).update(eq("smoking"),anyMap(),eq(1L));
        verify(repository,times(1)).audit(eq("RECALCULATE"),eq("smoking"),eq(RECORD_ID),anyMap(),anyMap());
    }

    @Test
    void sourceVersionChangeStillInvalidatesAnEmptyPreview() {
        Map<String,Object> preview=service.preview(request());
        assertEquals(0,preview.get("count"));
        product.put("version",2L);
        assertThrows(ApiException.class,()->service.apply(applyRequest(preview)));
        verify(repository,never()).update(anyString(),anyMap(),anyLong());
    }

    @Test
    void affectedRecordVersionChangeStillInvalidatesPreview() {
        product.put("packagePrice",40);
        Map<String,Object> preview=service.preview(request());
        assertEquals(1,preview.get("count"));
        record.put("version",2L);
        assertThrows(ApiException.class,()->service.apply(applyRequest(preview)));
        verify(repository,never()).update(anyString(),anyMap(),anyLong());
    }
}
