package cn.ziji.data;

import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.util.*;

@Component
public class DataValidator {
    public static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    public static final Set<String> META=Set.of("id","version","createdAt","updatedAt","deleted");
    private final SchemaRegistry registry;
    public DataValidator(SchemaRegistry registry){this.registry=registry;}
    public static String now(){return LocalDateTime.now(ZONE).withNano(0).toString();}
    public static String uuid(Object value) {
        try {String s=String.valueOf(value);if(!UUID.fromString(s).toString().equalsIgnoreCase(s))throw new IllegalArgumentException();return s.toLowerCase(Locale.ROOT);}
        catch(Exception e){throw new IllegalArgumentException("记录 ID 必须为 UUID");}
    }
    public Map<String,Object> normalize(String collection,Map<String,Object> input,Map<String,Object> previous,boolean imported) {
        var c=registry.require(collection);Map<String,Object> m=new LinkedHashMap<>();
        Set<String> allowed=new HashSet<>(META);c.fields().forEach(f->allowed.add(f.key()));
        for(String key:input.keySet()) {
            if(!allowed.contains(key)&&(!SchemaRegistry.usesBehaviorState(collection)||!isDerived(key))) throw new IllegalArgumentException("不支持的字段："+key);
        }
        if(previous!=null) for(var f:c.fields())m.put(f.key(),previous.get(f.key()));
        for(var f:c.fields())if(input.containsKey(f.key()))m.put(f.key(),parse(f,input.get(f.key())));
        if(previous==null&&!imported) defaults(collection,m);
        // Old clients and backups omit this field because their form recorded only masturbation.
        if(collection.equals("intimacy") && !input.containsKey("activityType") && m.get("activityType")==null)
            m.put("activityType","MASTURBATION");
        for(var f:c.fields()) {
            Object v=m.get(f.key());
            if(f.required()&&(v==null||v instanceof String s&&s.isBlank())) throw new IllegalArgumentException("请填写"+f.label());
            if(v!=null)m.put(f.key(),parse(f,v));
            else m.put(f.key(),null);
        }
        if(collection.equals("products")&&!imported) {
            List<String> unused="SMOKING".equals(m.get("kind"))
                ?List.of("bottleMl","abv")
                :List.of("unitCount","tarMg","nicotineMg","coMg","constituentMethod");
            unused.forEach(key->m.put(key,null));
        }
        if(SchemaRegistry.isJournalCollection(collection))validateJournal(collection,m,previous,imported);
        if(collection.equals("ledger")) {
            validStorageDate(m,"date");
            BigDecimal amount=(BigDecimal)m.get("amount");
            if(amount.signum()<=0||amount.stripTrailingZeros().scale()>2)
                throw new IllegalArgumentException("收支金额必须大于零，最多精确到分");
            String category=m.get("category").toString();
            if(category.codePointCount(0,category.length())>40||category.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("收支分类最多40字，不能包含换行或控制字符");
        }
        validateRanges(collection,m,!imported&&(previous==null||!Boolean.TRUE.equals(previous.get("priceConfirmed"))));
        return m;
    }
    public boolean isDerived(String key) {
        return Set.of("snapshotName","snapshotUnitCount","snapshotUnitPrice","snapshotPriceType","snapshotPriceSourceId","snapshotPriceEffectiveDate","snapshotPriceCurrency","snapshotPurchasePayer","snapshotTarMg","snapshotNicotineMg","snapshotCoMg","snapshotConstituentMethod","snapshotPackagingVersion","snapshotSourceUrl","snapshotSourceDate","snapshotBottleMl","snapshotAbv","cost","costType","actualMl","ethanolGrams","ethanolEquivalent42Ml","tarTotalMg","nicotineTotalMg","coTotalMg","calculationVersion","equivalentBottles","constituentNotice").contains(key);
    }
    private Object parse(SchemaRegistry.Field f,Object value) {
        if(value==null||value instanceof String s&&s.isBlank()) return null;
        try {
            return switch(f.type()) {
                case "number" -> {
                    if(value instanceof Boolean)throw new IllegalArgumentException();
                    BigDecimal d=new BigDecimal(value.toString());
                    if(d.precision()>20||d.scale()>6||d.abs().compareTo(new BigDecimal("99999999999999"))>0)throw new IllegalArgumentException();
                    yield d;
                }
                case "boolean" -> {if(!(value instanceof Boolean))throw new IllegalArgumentException();yield value;}
                case "date" -> LocalDate.parse(value.toString()).toString();
                case "time" -> {
                    if(!(value instanceof String text)||!text.matches("\\d{2}:\\d{2}"))throw new IllegalArgumentException();
                    yield LocalTime.parse(text).toString();
                }
                case "strings" -> normalizeStrings(value,f.key().equals("tags"));
                case "datetime" -> {
                    String text=value.toString();
                    // Body measurements are maintained by date; retain full timestamps from older records.
                    yield f.key().equals("measuredAt")&&text.length()==10
                        ? LocalDate.parse(text).atStartOfDay().toString()
                        : LocalDateTime.parse(text).withNano(0).toString();
                }
                case "product","session","purchase","task" -> uuid(value);
                case "select" -> {
                    String s=value.toString();if(f.options().stream().noneMatch(o->o.get("value").equals(s)))throw new IllegalArgumentException();yield s;
                }
                default -> {
                    if(!(value instanceof String))throw new IllegalArgumentException();
                    String s=value.toString().trim();int max=f.type().equals("textarea")?10000:f.key().equals("sourceUrl")?2000:500;
                    if(s.length()>max)throw new IllegalArgumentException();
                    if(f.key().equals("sourceUrl")){URI u=URI.create(s);if(!Set.of("http","https").contains(u.getScheme())||u.getHost()==null)throw new IllegalArgumentException();}
                    yield s;
                }
            };
        }catch(Exception e){throw new IllegalArgumentException(f.label()+"的格式或范围不正确");}
    }
    private void defaults(String c,Map<String,Object> m) {
        switch(c) {
            case "products" -> {put(m,"verified",false);put(m,"favorite",false);put(m,"locked",false);put(m,"active",true);put(m,"priceConfirmed",false);put(m,"priceType","PERSONAL");}
            case "prices" -> {put(m,"currency","CNY");put(m,"verified",false);put(m,"priceType","PERSONAL");put(m,"effectiveDate",LocalDate.now(ZONE).toString());}
            case "purchases" -> {put(m,"occurredAt",now());put(m,"payer","SELF");put(m,"quantity",BigDecimal.ONE);}
            case "smoking" -> {put(m,"occurredAt",now());put(m,"timePrecision","EXACT");put(m,"count",BigDecimal.ONE);put(m,"source","SELF");}
            case "sessions" -> {put(m,"startedAt",now());put(m,"timePrecision","EXACT");}
            case "drinks" -> {put(m,"occurredAt",now());put(m,"timePrecision","EXACT");put(m,"inputMode","BOTTLES");put(m,"share",BigDecimal.ONE);if("BOTTLES".equals(m.get("inputMode")))put(m,"bottles",BigDecimal.ONE);}
            case "intimacy" -> {put(m,"occurredAt",now());put(m,"timePrecision","EXACT");put(m,"count",BigDecimal.ONE);put(m,"manualAlcohol","AUTO");}
            case "body" -> {put(m,"measuredAt",now());put(m,"sex","UNSPECIFIED");}
            case "ledger" -> {put(m,"date",LocalDate.now(ZONE).toString());put(m,"direction","EXPENSE");}
            case "journal" -> {put(m,"startDate",LocalDate.now(ZONE).toString());put(m,"timeMode","DATE");put(m,"people",List.of());put(m,"tags",List.of());}
            case "tasks" -> {put(m,"status","TODO");put(m,"important",false);put(m,"tags",List.of());}
            default -> {}
        }
    }
    public static List<String> normalizeStrings(Object value,boolean tags) {
        if(value==null)return List.of();
        if(!(value instanceof List<?> list)||list.size()>30)throw new IllegalArgumentException("标签或人员最多填写30项");
        LinkedHashSet<String> normalized=new LinkedHashSet<>();
        for(Object item:list) {
            if(!(item instanceof String text))throw new IllegalArgumentException("标签或人员应为文字数组");
            text=text.trim();if(tags)text=text.replaceFirst("^#+","").trim().toLowerCase(Locale.ROOT);
            if(text.isEmpty())continue;
            if(text.length()>80||text.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("每个标签或人员名称最多80字，不能包含控制字符");
            normalized.add(text);
        }
        return List.copyOf(normalized);
    }
    private void validateJournal(String collection,Map<String,Object> row,Map<String,Object> previous,boolean imported) {
        row.put("tags",normalizeStrings(row.get("tags"),true));
        LocalDate today=LocalDate.now(ZONE);
        if(collection.equals("tasks")) {
            boolean done="DONE".equals(row.get("status"));
            if(!imported) {
                if(done&&row.get("completedDate")==null)row.put("completedDate",today.toString());
                if(!done)row.put("completedDate",null);
            }
            if(done&&row.get("completedDate")==null)throw new IllegalArgumentException("已完成任务需要实际完成日期");
            if(!done&&row.get("completedDate")!=null)throw new IllegalArgumentException("未完成任务不应包含实际完成日期");
            if(row.get("completedDate")!=null&&LocalDate.parse(row.get("completedDate").toString()).isAfter(today))throw new IllegalArgumentException("实际完成日期不能在未来");
            for(String key:List.of("plannedDate","dueDate","completedDate"))validStorageDate(row,key);
            return;
        }
        row.put("people",normalizeStrings(row.get("people"),false));
        validStorageDate(row,"startDate");validStorageDate(row,"endDate");
        LocalDate start=LocalDate.parse(row.get("startDate").toString());
        LocalDate end=row.get("endDate")==null?start:LocalDate.parse(row.get("endDate").toString());
        if(end.isBefore(start))throw new IllegalArgumentException("结束日期不能早于发生日期");
        if(start.isAfter(today)||end.isAfter(today))throw new IllegalArgumentException("生活日志只记录已经发生的日期，未来安排请添加待办");
        String mode=row.get("timeMode").toString();
        if(!imported) {
            if(!mode.equals("PERIOD"))row.put("period",null);
            if(!mode.equals("EXACT")){row.put("startTime",null);row.put("endTime",null);}
            if(end.equals(start))row.put("endDate",null);
        }
        if(mode.equals("PERIOD")&&row.get("period")==null)throw new IllegalArgumentException("请选择大致时段");
        if(!mode.equals("PERIOD")&&row.get("period")!=null)throw new IllegalArgumentException("当前时间方式不应含有时段");
        if(!mode.equals("EXACT")&&(row.get("startTime")!=null||row.get("endTime")!=null))throw new IllegalArgumentException("日期或时段记录不应包含具体时刻");
        if(mode.equals("EXACT")) {
            if(row.get("startTime")==null)throw new IllegalArgumentException("请填写开始时间");
            LocalDateTime started=start.atTime(LocalTime.parse(row.get("startTime").toString()));
            LocalDateTime ended=row.get("endTime")==null?null:end.atTime(LocalTime.parse(row.get("endTime").toString()));
            if(ended!=null&&ended.isBefore(started))throw new IllegalArgumentException("结束时间早于开始时间；跨午夜请填写结束日期");
            LocalDateTime now=LocalDateTime.now(ZONE);
            if(started.isAfter(now)||ended!=null&&ended.isAfter(now))throw new IllegalArgumentException("实际发生时间不能在未来，请核对时间或添加待办");
            if(ended!=null&&row.get("durationMinutes")!=null) {
                if(imported)throw new IllegalArgumentException("完整起止时间会自动计算时长，备份不应同时包含手填投入时长");
                row.put("durationMinutes",null);
            }
        }
        if(row.get("durationMinutes")!=null) {
            range(row,"durationMinutes","1","10080",true);
            long availableMinutes=(java.time.temporal.ChronoUnit.DAYS.between(start,end)+1)*1440;
            if(new BigDecimal(row.get("durationMinutes").toString()).compareTo(BigDecimal.valueOf(availableMinutes))>0)
                throw new IllegalArgumentException("投入时长不能超过所填日期范围，请核对时长或补充结束日期");
        }
    }
    private void validStorageDate(Map<String,Object> row,String key) {
        if(row.get(key)==null)return;
        int year=LocalDate.parse(row.get(key).toString()).getYear();
        if(year<1000||year>9999)throw new IllegalArgumentException("日期年份应在1000至9999之间");
    }
    private void put(Map<String,Object> m,String k,Object value){if(!m.containsKey(k)||m.get(k)==null)m.put(k,value);}
    private void range(Map<String,Object> m,String key,String low,String high,boolean integer) {
        if(m.get(key)==null)return;BigDecimal d=new BigDecimal(m.get(key).toString());
        if(d.compareTo(new BigDecimal(low))<0||d.compareTo(new BigDecimal(high))>0||(integer&&d.stripTrailingZeros().scale()>0))throw new IllegalArgumentException(key+"超出允许范围");
    }
    private void validateRanges(String c,Map<String,Object> m,boolean requireProductSpecifications) {
        for(String k:List.of("amount","unitPrice","packagePrice","tarMg","nicotineMg","coMg"))range(m,k,"0","999999999",false);
        range(m,"abv","0","100",false);range(m,"share","0","1",false);range(m,"unitCount","1","1000",true);
        range(m,"count","1","10000",true);range(m,"quantity","0.000001","1000000",false);range(m,"bottles","0.000001","10000",false);
        range(m,"bottleMl","0.000001","100000",false);range(m,"volumeMl","0.000001","1000000",false);
        if(!c.equals("journal"))range(m,"durationMinutes","0","1440",false);
        range(m,"heightCm","50","260",false);range(m,"weightKg","10","500",false);range(m,"age","1","120",true);
        range(m,"bodyFatPct","0","100",false);range(m,"waistCm","1","500",false);
        if(c.equals("products")&&Boolean.TRUE.equals(m.get("priceConfirmed"))) {
            if(m.get("packagePrice")==null)throw new IllegalArgumentException("请填写种类单价，未知价格请保持未确认");
            if(requireProductSpecifications&&"SMOKING".equals(m.get("kind"))&&m.get("unitCount")==null)throw new IllegalArgumentException("请填写每包根数，以便换算每根单价");
            if(requireProductSpecifications&&"ALCOHOL".equals(m.get("kind"))&&m.get("bottleMl")==null)throw new IllegalArgumentException("请填写每瓶毫升，以便换算饮用费用");
        }
        if(c.equals("sessions")&&m.get("endedAt")!=null&&LocalDateTime.parse(m.get("endedAt").toString()).isBefore(LocalDateTime.parse(m.get("startedAt").toString())))throw new IllegalArgumentException("结束时间不能早于开始时间");
        if(c.equals("intimacy")&&!"AUTO".equals(m.get("manualAlcohol"))&&(m.get("overrideReason")==null||m.get("overrideReason").toString().isBlank()))throw new IllegalArgumentException("请填写本人判断酒后状态的说明");
    }
}
