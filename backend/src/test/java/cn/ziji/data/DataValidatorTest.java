package cn.ziji.data;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DataValidatorTest {
    final DataValidator validator=new DataValidator(new SchemaRegistry());
    @Test void explicitUnknownPriceStaysUnknown(){var row=validator.normalize("smoking",Map.of("count",3),null,false);assertNull(row.get("unitPrice"));assertEquals("EXACT",row.get("timePrecision"));assertEquals("3",row.get("count").toString());}
    @Test void rejectsFractionalCigaretteCount(){assertThrows(IllegalArgumentException.class,()->validator.normalize("smoking",Map.of("count",1.5),null,false));}
    @Test void rejectsSqlAndUnknownFieldNames(){assertThrows(IllegalArgumentException.class,()->validator.normalize("smoking",Map.of("count",1,"passwordHash","oops"),null,false));assertThrows(IllegalArgumentException.class,()->new SchemaRegistry().require("products; DROP TABLE zj_auth"));}
    @Test void derivedFieldsAlsoHaveAWhitelist(){assertThrows(IllegalArgumentException.class,()->validator.normalize("smoking",Map.of("count",1,"snapshotInjectedField","not-allowed"),null,false));}
    @Test void rejectsUnsafeSourceUrls(){assertThrows(IllegalArgumentException.class,()->validator.normalize("products",Map.of("kind","SMOKING","brand","示例","name","示例","sourceUrl","javascript:alert(1)"),null,false));}
    @Test void rejectsAbvAndShareOutOfRange(){assertThrows(IllegalArgumentException.class,()->validator.normalize("drinks",Map.of("sessionId",UUID.randomUUID().toString(),"abv",101),null,false));assertThrows(IllegalArgumentException.class,()->validator.normalize("drinks",Map.of("sessionId",UUID.randomUUID().toString(),"share",1.1),null,false));}
    @Test void cannotInventMissingTimeDuringBackupRestore(){assertThrows(IllegalArgumentException.class,()->validator.normalize("smoking",Map.of("count",1),null,true));}
    @Test void manualStateRequiresReason(){assertThrows(IllegalArgumentException.class,()->validator.normalize("intimacy",Map.of("manualAlcohol","YES"),null,false));var row=validator.normalize("intimacy",Map.of("manualAlcohol","YES","overrideReason","补充当时情况"),null,false);assertEquals("YES",row.get("manualAlcohol"));}
    @Test void endCannotBeEarlierThanStart(){assertThrows(IllegalArgumentException.class,()->validator.normalize("sessions",Map.of("startedAt","2026-10-21T20:00:00","endedAt","2026-10-21T19:00:00"),null,false));}
    @Test void productPriceNeedsRealPackageSpecification(){assertThrows(IllegalArgumentException.class,()->validator.normalize("products",Map.of("kind","SMOKING","brand","测试","name","测试","packagePrice",20,"priceConfirmed",true),null,false));var row=validator.normalize("products",Map.of("kind","SMOKING","brand","测试","name","测试","packagePrice",0,"unitCount",20,"priceConfirmed",true),null,false);assertEquals("0",row.get("packagePrice").toString());assertEquals(true,row.get("priceConfirmed"));}
    @Test void productUnknownPriceIsNotFree(){var row=validator.normalize("products",Map.of("kind","ALCOHOL","brand","测试","name","测试","bottleMl",500),null,false);assertNull(row.get("packagePrice"));assertEquals(false,row.get("priceConfirmed"));assertThrows(IllegalArgumentException.class,()->validator.normalize("products",Map.of("kind","ALCOHOL","brand","测试","name","测试","packagePrice",-1),null,false));}
    @Test void incompleteLegacyProductCanBeEditedAndRestored(){var old=Map.<String,Object>of("kind","ALCOHOL","brand","测试","name","测试","packagePrice",10,"priceConfirmed",true);var edited=validator.normalize("products",Map.of("notes","补充备注"),old,false);assertNull(edited.get("bottleMl"));assertEquals("补充备注",edited.get("notes"));var restored=validator.normalize("products",old,null,true);assertNull(restored.get("bottleMl"));assertEquals(true,restored.get("priceConfirmed"));}
    @Test void bodyMeasurementAcceptsDateWithoutMinutePrecision(){
        var row=validator.normalize("body",Map.of("measuredAt","2026-10-24","weightKg",68.5,"bodyFatPct",19.2,"waistCm",81.5),null,false);
        assertEquals("2026-10-24T00:00",row.get("measuredAt"));
        assertEquals("68.5",row.get("weightKg").toString());
        assertEquals("19.2",row.get("bodyFatPct").toString());
        assertEquals("81.5",row.get("waistCm").toString());
        assertEquals("UNSPECIFIED",row.get("sex"));
        assertNull(row.get("age"));assertNull(row.get("heightCm"));
    }
    @Test void bodyMeasurementDoesNotInferBodyFatOrReplaceOlderTimestamp(){
        var old=Map.<String,Object>of("measuredAt","2026-10-21T21:32:15","heightCm",172,"weightKg",68.5,"sex","UNSPECIFIED");
        var row=validator.normalize("body",Map.of("notes","补充测量备注"),old,false);
        assertEquals("2026-10-21T21:32:15",row.get("measuredAt"));
        assertNull(row.get("bodyFatPct"));assertNull(row.get("waistCm"));
    }
    @Test void bodyMetricsRejectImpossibleValuesAndCanBeCleared(){
        for(var entry:List.of(Map.of("bodyFatPct",-0.1),Map.of("bodyFatPct",100.1),Map.of("waistCm",0),Map.of("waistCm",501)))
            assertThrows(IllegalArgumentException.class,()->validator.normalize("body",new LinkedHashMap<>(entry),null,false));
        var old=validator.normalize("body",Map.of("bodyFatPct",20,"waistCm",80),null,false);
        var update=new LinkedHashMap<String,Object>();update.put("bodyFatPct",null);update.put("waistCm",null);
        var row=validator.normalize("body",update,old,false);assertNull(row.get("bodyFatPct"));assertNull(row.get("waistCm"));
    }
    @Test void approximateTimeIsAvailableForEveryBehaviorCollection(){
        for(String collection:List.of("smoking","sessions","drinks","intimacy")) {
            var values=new LinkedHashMap<String,Object>();values.put("timePrecision","APPROX");
            if(collection.equals("drinks"))values.put("sessionId",UUID.randomUUID().toString());
            assertEquals("APPROX",validator.normalize(collection,values,null,false).get("timePrecision"));
        }
        assertThrows(IllegalArgumentException.class,()->validator.normalize("smoking",Map.of("occurredAt","2026-10-24"),null,false));
    }
    @Test void newProductsOnlyKeepSpecificationsForTheirKind(){
        var smoking=validator.normalize("products",mixedProduct("SMOKING"),null,false);
        assertNull(smoking.get("bottleMl"));assertNull(smoking.get("abv"));
        assertEquals("20",smoking.get("unitCount").toString());assertEquals("5",smoking.get("tarMg").toString());
        assertEquals("包装标示",smoking.get("constituentMethod"));
        var alcohol=validator.normalize("products",mixedProduct("ALCOHOL"),null,false);
        for(String key:List.of("unitCount","tarMg","nicotineMg","coMg","constituentMethod"))assertNull(alcohol.get(key),key);
        assertEquals("500",alcohol.get("bottleMl").toString());assertEquals("4",alcohol.get("abv").toString());
    }
    @Test void ordinaryProductSaveClearsUnrelatedLegacySpecificationsWithoutMutatingInput(){
        for(String kind:List.of("SMOKING","ALCOHOL")) {
            var previous=mixedProduct(kind);
            var saved=validator.normalize("products",Map.of("notes","更新资料"),previous,false);
            assertEquals("更新资料",saved.get("notes"));
            if(kind.equals("SMOKING")){assertNull(saved.get("bottleMl"));assertNull(saved.get("abv"));}
            else for(String key:List.of("unitCount","tarMg","nicotineMg","coMg","constituentMethod"))assertNull(saved.get(key),key);
            assertEquals(20,previous.get("unitCount"));assertEquals(500,previous.get("bottleMl"));
        }
    }
    @Test void importedProductRetainsItsOriginalSpecificationsForBothKinds(){
        for(String kind:List.of("SMOKING","ALCOHOL")) {
            var restored=validator.normalize("products",mixedProduct(kind),null,true);
            assertEquals(kind,restored.get("kind"));
            assertEquals("20",restored.get("unitCount").toString());assertEquals("500",restored.get("bottleMl").toString());
            assertEquals("4",restored.get("abv").toString());assertEquals("5",restored.get("tarMg").toString());
            assertEquals("0.5",restored.get("nicotineMg").toString());assertEquals("4",restored.get("coMg").toString());
            assertEquals("包装标示",restored.get("constituentMethod"));
        }
    }
    private Map<String,Object> mixedProduct(String kind){
        var row=new LinkedHashMap<String,Object>();
        row.put("kind",kind);row.put("brand","测试");row.put("name","测试");
        row.put("unitCount",20);row.put("bottleMl",500);row.put("abv",4);
        row.put("tarMg",5);row.put("nicotineMg",0.5);row.put("coMg",4);row.put("constituentMethod","包装标示");
        return row;
    }
}
