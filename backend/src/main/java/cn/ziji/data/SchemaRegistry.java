package cn.ziji.data;

import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class SchemaRegistry {
    public record Field(String key,String label,String type,boolean required,List<Map<String,String>> options) {
        public Map<String,Object> description() {
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("key",key); m.put("label",label); m.put("type",type); m.put("required",required);
            if (!options.isEmpty()) m.put("options",options);
            return m;
        }
    }
    public record Collection(String name,String label,List<Field> fields) {
        public String table() { return "zj_"+name; }
        public Map<String,Object> description() {
            List<Map<String,Object>> descriptions=fields.stream().map(field->{
                Map<String,Object> description=field.description();
                if(name.equals("journal")&&field.key().equals("durationMinutes")) {
                    description.put("min",1);description.put("max",10080);description.put("step",1);
                }
                return description;
            }).toList();
            return Map.of("name",name,"label",label,"fields",descriptions);
        }
    }
    private final LinkedHashMap<String,Collection> collections = new LinkedHashMap<>();
    public static boolean isJournalCollection(String name) { return Set.of("journal","tasks").contains(name); }
    public SchemaRegistry() {
        add("products", "商品资料", List.of(
            field("kind","类别","select",true,"SMOKING:香烟|ALCOHOL:酒品"),
            field("brand","品牌","text",true,""),
            field("name","名称","text",true,""),
            field("variant","规格型号","text",false,""),
            field("packagePrice","每包或瓶单价（元）","number",false,""),
            field("priceConfirmed","单价已确认","boolean",false,""),
            field("priceType","单价来源","select",false,"PERSONAL:个人设定单价|ACTUAL:实际单价|REFERENCE:参考单价"),
            field("aliases","别名","text",false,""),
            field("barcode","条码","text",false,""),
            field("unitCount","每包根数","number",false,""),
            field("bottleMl","每瓶毫升","number",false,""),
            field("abv","酒精度 %","number",false,""),
            field("tarMg","焦油 mg/支","number",false,""),
            field("nicotineMg","烟气烟碱 mg/支","number",false,""),
            field("coMg","一氧化碳 mg/支","number",false,""),
            field("constituentMethod","成分标示或检测口径","text",false,""),
            field("packagingVersion","包装版本","text",false,""),
            field("region","销售地区","text",false,""),
            field("sourceUrl","资料来源","text",false,""),
            field("sourceDate","资料日期","date",false,""),
            field("verified","已经确认","boolean",false,""),
            field("favorite","收藏","boolean",false,""),
            field("locked","锁定个人资料","boolean",false,""),
            field("active","启用","boolean",false,""),
            field("notes","备注","textarea",false,"")
        ));
        add("prices", "价格历史", List.of(
            field("productId","商品","product",true,""),
            field("amount","每包或瓶价格（元）","number",true,""),
            field("priceType","价格类型","select",true,"ACTUAL:实际购买价|PERSONAL:个人确认价|REFERENCE:参考价"),
            field("effectiveDate","生效日期","date",true,""),
            field("currency","币种","select",true,"CNY:人民币"),
            field("channel","渠道","text",false,""),
            field("sourceUrl","来源","text",false,""),
            field("verified","已确认","boolean",false,""),
            field("notes","备注","textarea",false,"")
        ));
        add("purchases", "购买流水", List.of(
            field("productId","商品","product",true,""),
            field("quantity","包或瓶数量","number",true,""),
            field("amount","本次总付款（元）","number",true,""),
            field("occurredAt","购买时间","datetime",true,""),
            field("payer","付款人","select",true,"SELF:本人|OTHER:其他人"),
            field("notes","备注","textarea",false,"")
        ));
        add("smoking", "吸烟记录", List.of(
            field("occurredAt","发生时间","datetime",true,""),
            field("timePrecision","时间精度","select",true,"APPROX:大概时间（前后约1小时）|EXACT:时间准确|DATE:仅记得日期"),
            field("productId","香烟","product",false,""),
            field("count","根数","number",true,""),
            field("source","来源","select",true,"SELF:自购|GIFT:别人提供|OTHER:其他"),
            field("purchaseId","购买批次","purchase",false,""),
            field("unitPrice","单根价格覆盖（元）","number",false,""),
            field("mood","情绪","text",false,""),
            field("context","场景","text",false,""),
            field("notes","备注","textarea",false,"")
        ));
        add("sessions", "饮酒场次", List.of(
            field("startedAt","开始时间","datetime",true,""),
            field("endedAt","结束时间","datetime",false,""),
            field("timePrecision","时间精度","select",true,"APPROX:大概时间（前后约1小时）|EXACT:时间准确|DATE:仅记得日期"),
            field("context","场景","text",false,""),
            field("notes","备注","textarea",false,"")
        ));
        add("drinks", "饮酒明细", List.of(
            field("sessionId","饮酒场次","session",true,""),
            field("productId","酒品","product",false,""),
            field("occurredAt","饮用时间","datetime",true,""),
            field("timePrecision","时间精度","select",true,"APPROX:大概时间（前后约1小时）|EXACT:时间准确|DATE:仅记得日期"),
            field("inputMode","录入方式","select",true,"BOTTLES:按瓶数|ML:直接填写毫升"),
            field("bottles","瓶数","number",false,""),
            field("bottleMl","每瓶毫升","number",false,""),
            field("share","本人饮用比例 0至1","number",false,""),
            field("volumeMl","本人实际饮用毫升","number",false,""),
            field("abv","酒精度 %","number",false,""),
            field("purchaseId","购买批次","purchase",false,""),
            field("unitPrice","每瓶价格覆盖（元）","number",false,""),
            field("notes","备注","textarea",false,"")
        ));
        add("intimacy", "自慰记录", List.of(
            field("occurredAt","发生时间","datetime",true,""),
            field("timePrecision","时间精度","select",true,"APPROX:大概时间（前后约1小时）|EXACT:时间准确|DATE:仅记得日期"),
            field("count","次数","number",true,""),
            field("durationMinutes","持续分钟","number",false,""),
            field("moodBefore","之前感受","text",false,""),
            field("moodAfter","之后感受","text",false,""),
            field("impact","对休息生活的影响","text",false,""),
            field("manualAlcohol","本人确认的酒后状态","select",true,"AUTO:采用自动判断|YES:是|NO:否|UNKNOWN:不确定"),
            field("overrideReason","本人判断说明","textarea",false,""),
            field("notes","备注","textarea",false,"")
        ));
        add("body", "身体数据", List.of(
            field("measuredAt","测量日期","datetime",true,""),
            field("heightCm","身高厘米","number",false,""),
            field("weightKg","体重千克","number",false,""),
            field("bodyFatPct","体脂率 %","number",false,""),
            field("waistCm","腰围厘米","number",false,""),
            field("age","年龄","number",false,""),
            field("sex","模型使用的生理参数","select",true,"MALE:男性参数|FEMALE:女性参数|UNSPECIFIED:不填写"),
            field("notes","备注","textarea",false,"")
        ));
        add("completeness", "每日完整度", List.of(
            field("date","日期","date",true,""),
            field("kind","行为","select",true,"SMOKING:吸烟|ALCOHOL:饮酒|INTIMACY:自慰"),
            field("complete","已确认记录完整","boolean",true,"")
        ));
        add("goals", "个人目标", List.of(
            field("kind","行为","select",true,"SMOKING:吸烟|ALCOHOL:饮酒|INTIMACY:自慰"),
            field("period","周期","select",true,"DAY:每日|WEEK:每周|MONTH:每月"),
            field("limitValue","个人目标","number",true,""),
            field("unit","计量单位","select",true,"COUNT:根数或次数|ML:毫升|GRAMS:纯酒精克数"),
            field("enabled","启用","boolean",true,"")
        ));
        add("health", "健康资料", List.of(
            field("title","标题","text",true,""),
            field("topic","主题","select",true,"SMOKING:吸烟|ALCOHOL:饮酒|INTIMACY:自慰|GENERAL:通用"),
            field("body","说明","textarea",true,""),
            field("sourceUrl","权威来源","text",false,""),
            field("reviewedAt","资料复核日期","date",false,"")
        ));
        add("tasks", "待办任务", List.of(
            field("title","待办事项","text",true,""),
            field("content","说明","textarea",false,""),
            field("status","状态","select",true,"TODO:待开始|DOING:进行中|DONE:已完成|PAUSED:暂缓|CANCELLED:已取消"),
            field("plannedDate","安排日期","date",false,""),
            field("dueDate","截止日期","date",false,""),
            field("completedDate","实际完成日期","date",false,""),
            field("result","结果说明","textarea",false,""),
            field("important","重要","boolean",true,""),
            field("tags","标签","strings",false,"")
        ));
        add("journal", "生活日志", List.of(
            field("title","做了什么","text",true,""),
            field("content","详细内容","textarea",false,""),
            field("category","分类","select",false,"LIFE:生活|MEAL:餐饮|WORK:工作|STUDY:学习|TRAVEL:出行|SOCIAL:社交|OTHER:其他"),
            field("startDate","发生日期","date",true,""),
            field("endDate","结束日期","date",false,""),
            field("timeMode","时间方式","select",true,"DATE:只记日期|PERIOD:大致时段|EXACT:具体时间"),
            field("period","时段","select",false,"DAWN:凌晨|EARLY:早晨|MORNING:上午|NOON:中午|AFTERNOON:下午|EVENING:晚上"),
            field("startTime","开始时间","time",false,""),
            field("endTime","结束时间","time",false,""),
            field("durationMinutes","投入时长（分钟）","number",false,""),
            field("place","地点","text",false,""),
            field("people","和谁","strings",false,""),
            field("tags","标签","strings",false,""),
            field("taskId","关联待办","task",false,"")
        ));
    }
    private static Field field(String key,String label,String type,boolean required,String options) {
        List<Map<String,String>> choices = new ArrayList<>();
        if (!options.isEmpty()) for (String option:options.split("\\|")) {
            String[] parts=option.split(":",2);
            choices.add(Map.of("value",parts[0],"label",parts[1]));
        }
        return new Field(key,label,type,required,choices);
    }
    private void add(String name,String label,List<Field> fields) { collections.put(name,new Collection(name,label,fields)); }
    public Collection require(String name) {
        Collection c=collections.get(name);
        if(c==null) throw new IllegalArgumentException("不支持的数据集合");
        return c;
    }
    public List<Collection> all() { return List.copyOf(collections.values()); }
    public List<Map<String,Object>> describe() { return all().stream().map(Collection::description).toList(); }
}
