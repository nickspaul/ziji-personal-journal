package cn.ziji.data;

import cn.ziji.domain.BehaviorCalculator;
import cn.ziji.web.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
public class DataService {
    private final DataRepository repository;
    private final SchemaRegistry registry;
    private final DataValidator validator;
    public DataService(DataRepository repository,SchemaRegistry registry,DataValidator validator){this.repository=repository;this.registry=registry;this.validator=validator;}
    public List<Map<String,Object>> schema(){return registry.describe();}
    public List<Map<String,Object>> list(String collection,boolean deleted){return repository.list(collection,deleted);}
    public Map<String,List<Map<String,Object>>> state(){
        Map<String,List<Map<String,Object>>> state=new LinkedHashMap<>();
        for(var c:registry.all())if(!SchemaRegistry.isJournalCollection(c.name()))state.put(c.name(),repository.list(c.name(),false));
        return state;
    }
    public Map<String,List<Map<String,Object>>> state(Boolean deleted) {
        Map<String,List<Map<String,Object>>> state=new LinkedHashMap<>();for(var c:registry.all())state.put(c.name(),repository.list(c.name(),deleted));return state;
    }
    public Map<String,Object> find(String collection,String id){return repository.find(collection,DataValidator.uuid(id));}
    public Map<String,Object> require(String collection,String id){Map<String,Object> r=find(collection,id);if(r==null)throw new ApiException(404,"记录不存在");return r;}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> create(String collection,Map<String,Object> input) {
        registry.require(collection);String id=input.get("id")==null?UUID.randomUUID().toString():DataValidator.uuid(input.get("id"));
        Map<String,Object> existing=repository.find(collection,id);
        if(existing!=null)return idempotent(collection,input,existing);
        Map<String,Object> normalized=validator.normalize(collection,input,null,false);
        Map<String,List<Map<String,Object>>> current=SchemaRegistry.isJournalCollection(collection)?Map.of():state();checkReferences(collection,normalized,null,current);checkCompleteness(collection,normalized,null);
        Map<String,Object> enriched=BehaviorCalculator.enrich(collection,normalized,null,current);
        enriched=new LinkedHashMap<>(enriched);enriched.put("id",id);enriched.put("version",1L);enriched.put("createdAt",DataValidator.now());enriched.put("updatedAt",DataValidator.now());enriched.put("deleted",false);
        try{repository.insert(collection,enriched);}catch(DuplicateKeyException e){Map<String,Object> concurrent=repository.find(collection,id);if(concurrent!=null)return idempotent(collection,input,concurrent);throw e;}
        repository.audit("CREATE",collection,id,null,enriched);if(collection.equals("products"))recordProductPrice(enriched,null);
        if(collection.equals("prices"))fillMissingCostsFor(String.valueOf(enriched.get("productId")));
        return enriched;
    }
    private Map<String,Object> idempotent(String collection,Map<String,Object> input,Map<String,Object> existing) {
        if(Boolean.TRUE.equals(existing.get("deleted")))throw new ApiException(409,"这个 ID 已在回收站，请恢复或使用新 ID");
        Map<String,Object> supplied=validator.normalize(collection,input,existing,false);
        for(var f:registry.require(collection).fields())if(input.containsKey(f.key())&&!same(supplied.get(f.key()),existing.get(f.key())))throw new ApiException(409,"同一个请求 ID 对应了不同内容，请重新提交");
        return existing;
    }
    private boolean same(Object a,Object b){if(a==null||b==null)return a==b;if(a instanceof Number&&b instanceof Number)return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()))==0;return a.equals(b);}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> update(String collection,String id,Map<String,Object> input) {
        Map<String,Object> previous=require(collection,id);if(Boolean.TRUE.equals(previous.get("deleted")))throw new ApiException(409,"请先恢复这条记录");
        long expected=version(input.get("version"));
        if(!SchemaRegistry.isJournalCollection(collection)&&expected!=((Number)previous.get("version")).longValue())throw ApiException.conflict();
        Map<String,Object> normalized=validator.normalize(collection,input,previous,false);
        if(SchemaRegistry.isJournalCollection(collection)&&sameBusiness(collection,normalized,previous))return previous;
        if(expected!=((Number)previous.get("version")).longValue())throw ApiException.conflict();
        protectHistoricalReferences(collection,id,previous,normalized);
        Map<String,List<Map<String,Object>>> current=SchemaRegistry.isJournalCollection(collection)?Map.of():state();checkReferences(collection,normalized,previous,current);checkCompleteness(collection,normalized,id);
        Map<String,Object> result=new LinkedHashMap<>(BehaviorCalculator.enrich(collection,normalized,previous,current));
        result.put("id",id);result.put("version",expected+1);result.put("createdAt",previous.get("createdAt"));result.put("updatedAt",DataValidator.now());result.put("deleted",false);
        if(!repository.update(collection,result,expected))throw ApiException.conflict();repository.audit("UPDATE",collection,id,previous,result);
        if(collection.equals("products")){recordProductPrice(result,previous);fillMissingCostsFor(id);}
        if(collection.equals("prices"))fillMissingCostsFor(String.valueOf(result.get("productId")));
        return result;
    }
    private boolean sameBusiness(String collection,Map<String,Object> left,Map<String,Object> right) {
        return registry.require(collection).fields().stream().allMatch(f->same(left.get(f.key()),right.get(f.key())));
    }
    private void recordProductPrice(Map<String,Object> product,Map<String,Object> previous) {
        if(product.get("packagePrice")==null)return;
        if(previous!=null&&same(product.get("packagePrice"),previous.get("packagePrice"))&&same(product.get("priceConfirmed"),previous.get("priceConfirmed"))&&same(product.get("priceType"),previous.get("priceType")))return;
        Map<String,Object> price=new LinkedHashMap<>();price.put("productId",product.get("id"));price.put("amount",product.get("packagePrice"));price.put("verified",Boolean.TRUE.equals(product.get("priceConfirmed")));price.put("priceType",product.get("priceType")==null?"PERSONAL":product.get("priceType"));price.put("effectiveDate",LocalDate.now(DataValidator.ZONE).toString());price.put("currency","CNY");price.put("channel","种类单价设置");price.put("notes","种类单价修改时留存；已有费用保留，有依据的缺失费用自动补齐。");
        create("prices",price);
    }
    /** Repairs only missing prices; no synthetic events, replacement snapshots or new tables. */
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public int repairMissingCosts(){return fillMissingCostsFor(null);}
    private int fillMissingCostsFor(String productId) {
        Map<String,List<Map<String,Object>>> current=state();int updated=0;
        for(String collection:List.of("smoking","drinks"))for(Map<String,Object> before:current.get(collection)) {
            if(before.get("cost")!=null || productId!=null&&!Objects.equals(productId,before.get("productId")))continue;
            Map<String,Object> next=BehaviorCalculator.fillMissingCost(collection,before,current);
            if(next.get("cost")==null)continue;
            next=new LinkedHashMap<>(next);long expected=version(before.get("version"));
            next.put("version",expected+1);next.put("updatedAt",DataValidator.now());
            if(!repository.update(collection,next,expected))throw ApiException.conflict();
            repository.audit("FILL_MISSING_COST",collection,String.valueOf(next.get("id")),before,next);updated++;
        }
        return updated;
    }
    public static long version(Object value){try{long v=new BigDecimal(String.valueOf(value)).longValueExact();if(v<1)throw new Exception();return v;}catch(Exception e){throw new IllegalArgumentException("缺少有效的记录版本，请刷新后重试");}}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> delete(String collection,String id,long expected) {
        Map<String,Object> previous=require(collection,id);if(expected!=((Number)previous.get("version")).longValue())throw ApiException.conflict();
        if(Boolean.TRUE.equals(previous.get("deleted")))return previous;
        if(collection.equals("sessions")&&list("drinks",false).stream().anyMatch(r->id.equals(r.get("sessionId"))))throw new ApiException(409,"场次还有饮酒明细，请先删除或转移明细");
        Map<String,Object> next=new LinkedHashMap<>(previous);next.put("deleted",true);next.put("version",expected+1);next.put("updatedAt",DataValidator.now());
        if(!repository.update(collection,next,expected))throw ApiException.conflict();repository.audit("DELETE",collection,id,previous,next);return next;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> restore(String collection,String id,long expected) {
        Map<String,Object> previous=require(collection,id);if(expected!=((Number)previous.get("version")).longValue())throw ApiException.conflict();
        if(!Boolean.TRUE.equals(previous.get("deleted")))return previous;
        Map<String,List<Map<String,Object>>> current=SchemaRegistry.isJournalCollection(collection)?Map.of():state();checkReferences(collection,previous,previous,current);checkCompleteness(collection,previous,id);
        if(collection.equals("drinks")&&current.get("sessions").stream().noneMatch(r->Objects.equals(r.get("id"),previous.get("sessionId"))))throw new ApiException(409,"请先恢复对应的饮酒场次");
        Map<String,Object> next=new LinkedHashMap<>(previous);next.put("deleted",false);next.put("version",expected+1);next.put("updatedAt",DataValidator.now());
        next=BehaviorCalculator.fillMissingCost(collection,next,current);
        if(!repository.update(collection,next,expected))throw ApiException.conflict();repository.audit("RESTORE",collection,id,previous,next);
        if(collection.equals("products"))fillMissingCostsFor(id);
        if(collection.equals("prices"))fillMissingCostsFor(String.valueOf(next.get("productId")));
        return next;
    }
    private void checkCompleteness(String collection,Map<String,Object> row,String exceptId) {
        if(!collection.equals("completeness"))return;
        if(list(collection,false).stream().anyMatch(r->!Objects.equals(exceptId,r.get("id"))&&Objects.equals(row.get("date"),r.get("date"))&&Objects.equals(row.get("kind"),r.get("kind"))))throw new ApiException(409,"这一天的该类完整度已经存在，请修改已有记录");
    }
    private void protectHistoricalReferences(String collection,String id,Map<String,Object> before,Map<String,Object> after) {
        if(collection.equals("products")&&!Objects.equals(before.get("kind"),after.get("kind"))) {
            for(String c:List.of("smoking","drinks","purchases","prices"))if(repository.list(c,null).stream().anyMatch(r->id.equals(r.get("productId"))))throw new ApiException(409,"该商品已有历史引用，不能更改类别；请为另一类别新建商品");
        }
        if(collection.equals("purchases")&&!Objects.equals(before.get("productId"),after.get("productId"))) {
            for(String c:List.of("smoking","drinks"))if(repository.list(c,null).stream().anyMatch(r->id.equals(r.get("purchaseId"))))throw new ApiException(409,"该购买批次已有消耗记录，请先调整相关记录的购买批次，再更改商品");
        }
        if(collection.equals("purchases")&&!Objects.equals(before.get("occurredAt"),after.get("occurredAt"))) {
            LocalDateTime purchased=LocalDateTime.parse(after.get("occurredAt").toString());
            for(String c:List.of("smoking","drinks"))if(repository.list(c,null).stream().anyMatch(r->id.equals(r.get("purchaseId"))&&purchased.isAfter(LocalDateTime.parse(r.get("occurredAt").toString()))))throw new ApiException(409,"购买时间晚于已关联的消耗时间，请先核对相关记录");
        }
    }
    public void checkReferences(String collection,Map<String,Object> row,Map<String,Object> previous,Map<String,List<Map<String,Object>>> state) {
        for(var f:registry.require(collection).fields()) {
            String target=switch(f.type()){case "product"->"products";case "session"->"sessions";case "purchase"->"purchases";case "task"->"tasks";default->null;};
            if(target==null||row.get(f.key())==null)continue;
            String reference=row.get(f.key()).toString();Map<String,Object> ref=state.getOrDefault(target,List.of()).stream().filter(r->reference.equals(r.get("id"))).findFirst().orElse(null);
            if(target.equals("tasks")&&!state.containsKey("tasks")) {
                ref=repository.find("tasks",reference);
                if(ref!=null&&Boolean.TRUE.equals(ref.get("deleted"))&&(previous==null||!Objects.equals(previous.get(f.key()),reference)))throw new IllegalArgumentException("关联待办已移入回收站，请先恢复或选择其他待办");
            }
            if(ref==null){if(previous!=null&&Objects.equals(previous.get(f.key()),reference))continue;throw new IllegalArgumentException(f.label()+"不存在或已删除");}
            if(f.key().equals("productId")&&(collection.equals("smoking")||collection.equals("drinks"))) {
                String kind=collection.equals("smoking")?"SMOKING":"ALCOHOL";
                if(!kind.equals(ref.get("kind")))throw new IllegalArgumentException("商品类别与记录不符");
            }
            if(f.key().equals("purchaseId")&&row.get("productId")!=null&&!Objects.equals(row.get("productId"),ref.get("productId")))throw new IllegalArgumentException("购买批次与所选商品不一致");
        }
    }
    public Map<String,Object> settings() {
        Map<String,Object> values=new LinkedHashMap<>();values.put("theme","dark");values.put("reducedMotion",false);values.put("hidden",false);values.put("reminderMode","GENTLE");values.put("recentAlcoholHours",24);values.put("quietStart","23:00");values.put("quietEnd","08:00");values.put("dailyReminderLimit",3);values.put("defaultSmokingProductId",null);values.put("defaultAlcoholProductId",null);values.put("journalPinnedFilters",List.of());values.put("journalView","day");values.putAll(repository.settings());return values;
    }
    @Transactional
    public Map<String,Object> updateSettings(Map<String,Object> input) {
        Map<String,Object> before=settings();Map<String,Object> next=validateSettings(input);
        if(before.equals(next))return before;
        repository.settings(next);repository.audit("SETTINGS","settings","1",before,next);return next;
    }
    public Map<String,Object> validateSettings(Map<String,Object> input) {
        Map<String,Object> before=settings();Map<String,Object> next=new LinkedHashMap<>(before);
        for(var e:input.entrySet()) {
            String k=e.getKey();Object v=e.getValue();
            switch(k) {
                case "theme" -> {if(v==null||!Set.of("dark","light","system").contains(v))throw new IllegalArgumentException("主题不正确");}
                case "reminderMode" -> {if(v==null||!Set.of("OFF","GENTLE","ACTIVE").contains(v))throw new IllegalArgumentException("提醒模式不正确");}
                case "hidden","reducedMotion" -> {if(!(v instanceof Boolean))throw new IllegalArgumentException("设置应为开关值");}
                case "recentAlcoholHours","dailyReminderLimit" -> {int n;try{n=new BigDecimal(v.toString()).intValueExact();}catch(Exception ex){throw new IllegalArgumentException("设置应为整数");}if(n<0||n>(k.equals("recentAlcoholHours")?168:20))throw new IllegalArgumentException("设置超出范围");v=n;}
                case "quietStart","quietEnd" -> {try{v=LocalTime.parse(v.toString()).toString();}catch(Exception ex){throw new IllegalArgumentException("安静时间格式不正确");}}
                case "defaultSmokingProductId","defaultAlcoholProductId" -> {if(v!=null&&!v.toString().isBlank()){v=DataValidator.uuid(v);Map<String,Object> product=require("products",v.toString());if(Boolean.TRUE.equals(product.get("deleted")))throw new IllegalArgumentException("默认商品已删除");String kind=k.equals("defaultSmokingProductId")?"SMOKING":"ALCOHOL";if(!kind.equals(product.get("kind")))throw new IllegalArgumentException("默认商品类别不符");}else v=null;}
                case "journalView" -> {if(v==null||!Set.of("day","search","tasks").contains(v))throw new IllegalArgumentException("生活日志视图不正确");}
                case "journalPinnedFilters" -> v=validateJournalPins(v);
                default -> throw new IllegalArgumentException("不支持的设置："+k);
            }
            next.put(k,v);
        }
        return next;
    }
    public static List<Map<String,Object>> validateJournalPins(Object value) {
        if(!(value instanceof List<?> list)||list.size()>12)throw new IllegalArgumentException("固定筛选最多12项");
        List<Map<String,Object>> result=new ArrayList<>();Set<String> ids=new HashSet<>();
        for(Object entry:list) {
            if(!(entry instanceof Map<?,?> pin)||!Set.of("id","name","query").containsAll(pin.keySet()))throw new IllegalArgumentException("固定筛选格式不正确");
            String id=DataValidator.uuid(pin.get("id"));if(!ids.add(id))throw new IllegalArgumentException("固定筛选编号重复");
            if(!(pin.get("name") instanceof String name)||name.isBlank()||name.trim().length()>60)throw new IllegalArgumentException("请填写60字以内的筛选名称");
            if(!(pin.get("query") instanceof Map<?,?> query))throw new IllegalArgumentException("缺少固定筛选条件");
            Map<String,Object> normalized=new LinkedHashMap<>();
            for(var field:query.entrySet()) {
                if(!(field.getKey() instanceof String key))throw new IllegalArgumentException("筛选字段不正确");
                Object item=field.getValue();
                if(item==null)continue;
                switch(key) {
                    case "collection" -> {if(!Set.of("journal","tasks").contains(item))throw new IllegalArgumentException("筛选集合不正确");}
                    case "q","person","place" -> {if(!(item instanceof String text)||text.length()>500)throw new IllegalArgumentException("筛选文字过长");item=text.trim();}
                    case "from","to" -> {try{item=LocalDate.parse(item.toString()).toString();}catch(Exception e){throw new IllegalArgumentException("筛选日期不正确");}}
                    case "tags" -> item=DataValidator.normalizeStrings(item,true);
                    case "tagMode" -> {if(!Set.of("all","any").contains(item))throw new IllegalArgumentException("标签匹配方式不正确");}
                    case "category" -> {if(!Set.of("","LIFE","MEAL","WORK","STUDY","TRAVEL","SOCIAL","OTHER").contains(item))throw new IllegalArgumentException("日志分类不正确");}
                    case "status" -> {if(!Set.of("","ANY","ALL","OPEN","TODO","DOING","DONE","PAUSED","CANCELLED").contains(item))throw new IllegalArgumentException("任务状态不正确");}
                    case "dateField" -> {if(!Set.of("plannedDate","dueDate","completedDate").contains(item))throw new IllegalArgumentException("任务日期口径不正确");}
                    case "taskId" -> {if(item instanceof String s&&s.isBlank())continue;item=DataValidator.uuid(item);}
                    case "unscheduled","overdue","important" -> {if(!(item instanceof Boolean))throw new IllegalArgumentException("筛选选项应为开关值");}
                    default -> throw new IllegalArgumentException("不支持的筛选字段："+key);
                }
                normalized.put(key,item);
            }
            normalized.putIfAbsent("collection","journal");
            if(normalized.get("from")!=null&&normalized.get("to")!=null&&LocalDate.parse(normalized.get("to").toString()).isBefore(LocalDate.parse(normalized.get("from").toString())))throw new IllegalArgumentException("筛选结束日期不能早于开始日期");
            result.add(Map.of("id",id,"name",name.trim(),"query",normalized));
        }
        return result;
    }
    public Map<String,Object> analytics(LocalDate from,LocalDate to) {
        if(from==null)from=LocalDate.now(DataValidator.ZONE).minusDays(29);if(to==null)to=LocalDate.now(DataValidator.ZONE);
        if(to.isBefore(from)||java.time.temporal.ChronoUnit.DAYS.between(from,to)>3660)throw new IllegalArgumentException("日期范围不正确或超过十年");
        return BehaviorCalculator.analytics(state(),settings(),from,to,LocalDateTime.now(DataValidator.ZONE));
    }
    public Map<String,Object> lifetimeSummary() {
        return BehaviorCalculator.summary(state(),LocalDateTime.now(DataValidator.ZONE));
    }
}
