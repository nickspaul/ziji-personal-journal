package cn.ziji.report;

import cn.ziji.data.*;
import cn.ziji.domain.BehaviorCalculator;
import cn.ziji.journal.TaskEffortService;
import cn.ziji.ledger.DebtAccounting;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.*;
import java.time.*;
import java.util.*;

@Service
public class PeriodReportService {
    public record Report(LocalDate from, LocalDate to, String timezone, List<Section> sections) {}
    public record Section(String key, String label, String rule, Map<String,Object> summary, List<Map<String,Object>> records) {}
    private static final List<String> ORDER=List.of("ledger","journal","tasks","smoking","alcohol","intimacy","body");
    private static final String COST_RULE="费用按当时单价和本人用量估算，与账本实付分别统计。";
    private static final String ZERO_RULE="已过去且没有记录的日期按 0 次统计。";
    private final ReportQueries queries;
    private final SchemaRegistry schema;
    private final DataRepository repository;
    public PeriodReportService(ReportQueries queries, SchemaRegistry schema, DataRepository repository) {
        this.queries=queries; this.schema=schema; this.repository=repository;
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Report report(LocalDate from,LocalDate to,String selected) {
        if(from==null||to==null||to.isBefore(from)||from.getYear()<1000||to.getYear()>9999)
            throw new IllegalArgumentException("请选择有效的开始和结束日期");
        Set<String> keys=new LinkedHashSet<>(Arrays.asList(selected==null?new String[0]:selected.split(",",-1)));
        if(keys.isEmpty() || !ORDER.containsAll(keys)) throw new IllegalArgumentException("请选择有效的导出分类");
        Map<String,List<Map<String,Object>>> data=new HashMap<>();
        for(String key:keys) {
            switch(key) {
                case "tasks" -> data.put(key,queries.tasks(from,to));
                case "journal" -> data.put(key,queries.journal(from,to));
                case "alcohol" -> {
                    data.put("sessions",queries.range("sessions",from,to));
                    data.put("drinks",queries.range("drinks",from,to));
                }
                default -> data.put(key,queries.range(key,from,to));
            }
        }
        LocalDateTime now=LocalDateTime.now(DataValidator.ZONE);
        boolean links=keys.contains("alcohol") && (!data.getOrDefault("smoking",List.of()).isEmpty()
            || !data.getOrDefault("intimacy",List.of()).isEmpty());
        boolean estimate=keys.contains("body") && !data.getOrDefault("drinks",List.of()).isEmpty();
        var context=links||estimate?queries.modelContext(to):Map.<String,List<Map<String,Object>>>of();
        var settings=links?repository.settings():Map.<String,Object>of();
        Map<Object,Object> taskTitles=new HashMap<>();
        for(var task:data.getOrDefault("tasks",List.of())) taskTitles.put(task.get("id"),task.get("title"));
        Map<Object,List<Map<String,Object>>> linkedEntries=new HashMap<>();
        for(var entry:data.getOrDefault("journal",List.of())) if(entry.get("taskId")!=null)
            linkedEntries.computeIfAbsent(entry.get("taskId"),ignored->new ArrayList<>()).add(entry);
        List<Section> sections=new ArrayList<>();
        for(String key:ORDER) if(keys.contains(key)) {
            if(key.equals("alcohol")) {
                sections.add(alcohol(data,queries.emptySessions(from,to),estimate,context,from,to,now));
                continue;
            }
            var raw=data.get(key);
            Map<String,Object> summary=new LinkedHashMap<>();
            summary.put("记录数",raw.size());
            List<Map<String,Object>> records=new ArrayList<>();
            for(var row:raw) {
                var record=fields(key,row);
                switch(key) {
                    case "ledger" -> {
                        record.put("计入支出（元）",DebtAccounting.expense(row));
                        record.put("现金流出（元）",DebtAccounting.cashOutflow(row));
                        if (row.get("debtKind") != null)
                            record.put("欠款本金变动（元）",DebtAccounting.principalChange(row));
                    }
                    case "journal" -> put(record,"关联待办",taskTitles.get(row.get("taskId")));
                    case "tasks" -> {
                        put(record,"创建日期",day(row.get("createdAt")));
                        if(keys.contains("journal")) {
                            var entries=linkedEntries.getOrDefault(row.get("id"),List.of());
                            var effort=TaskEffortService.summarizePeriod(entries,from,to);
                            record.put("期间关联日志数",entries.size());
                            record.put("期间已知投入（分钟）",effort.get("totalMinutes"));
                            record.put("未能确定期间时长的日志数",effort.get("unmeasuredCount"));
                        }
                    }
                    case "body" -> {
                        BigDecimal weight=decimal(row.get("weightKg")),height=decimal(row.get("heightCm"));
                        if(weight!=null && height!=null && height.signum()>0)
                            record.put("BMI",weight.movePointRight(4).divide(height.multiply(height),1,RoundingMode.HALF_UP));
                    }
                    case "smoking" -> {
                        put(record,"香烟",row.get("snapshotName"));
                        record.put("用量估算费用（元）",row.get("cost"));
                        put(record,"单根价格（元）",row.get("snapshotUnitPrice"));
                        put(record,"每根标签焦油（mg）",row.get("snapshotTarMg"));
                        put(record,"每根标签烟碱（mg）",row.get("snapshotNicotineMg"));
                        put(record,"每根标签一氧化碳（mg）",row.get("snapshotCoMg"));
                    }
                }
                if(links && (key.equals("smoking")||key.equals("intimacy"))
                        && !LocalDateTime.parse(row.get("occurredAt").toString()).isAfter(now)) {
                    var link=BehaviorCalculator.alcoholLink(row,context,settings,now);
                    record.put("饮酒关联判断",switch(String.valueOf(link.get("effective"))) {
                        case "YES" -> "有关联"; case "NO" -> "无关联"; default -> "不确定";
                    });
                    record.put("自动判断依据",link.get("reason"));
                    if("MANUAL".equals(link.get("source"))) {
                        record.put("判断来源","本人确认");
                        put(record,"本人判断说明",row.get("overrideReason"));
                    } else record.put("判断来源","根据已录记录估算");
                }
                records.add(record);
            }
            String rule=switch(key) {
                case "ledger" -> {
                    BigDecimal income=amount(raw,"INCOME");
                    BigDecimal expense=raw.stream().map(DebtAccounting::expense).reduce(BigDecimal.ZERO,BigDecimal::add);
                    summary.put("现金流出（元）",raw.stream().map(DebtAccounting::cashOutflow).reduce(BigDecimal.ZERO,BigDecimal::add));
                    summary.put("期间欠款本金净变动（元）",raw.stream().map(DebtAccounting::principalChange).reduce(BigDecimal.ZERO,BigDecimal::add));
                    summary.put("收入（元）",income);summary.put("支出（元）",expense);
                    summary.put("期间结余（元）",income.subtract(expense));
                    yield "按记账日期统计。信用消费在购买时计支出，还款只将利息和手续费计为支出。现金流出含还款本金，期初欠款只增加本金。结余按收入减支出计算。";
                }
                case "journal" -> "按发生日期筛选，跨日事项与所选日期有交集即列出一次，保留完整起止时间。手填时长指整条记录。";
                case "tasks" -> "列出期间创建、安排、到期、完成或有关联日志的待办。状态及结果取导出时的记录。同时选择生活日志时汇总投入，精确时段只计范围内的分钟并去重，跨越边界的手填时长不作分摊。";
                case "smoking" -> {
                    summary.put("根数",sum(raw,"count"));
                    known(summary,"已知估算费用（元）","缺少费用的记录数",raw,"cost",0);
                    yield ZERO_RULE+COST_RULE+"烟盒成分为每根标签值。同时选择饮酒可查看基于原始饮酒时间线的关联判断。";
                }
                case "intimacy" -> {
                    BigDecimal total=sum(raw,"count");
                    BigDecimal sex=sum(raw.stream().filter(row->"SEX".equals(row.get("activityType"))).toList(),"count");
                    summary.put("次数",total);
                    summary.put("自慰次数",total.subtract(sex));
                    summary.put("性生活次数",sex);
                    yield ZERO_RULE+"同时选择饮酒时附关联判断。时间重合仅供回顾。";
                }
                case "body" -> "按测量日期列出。未测量的日期留空。BMI 使用同一条记录的身高和体重。";
                default -> throw new IllegalStateException();
            };
            sections.add(new Section(key,schema.require(key).label(),rule,summary,records));
        }
        return new Report(from,to,DataValidator.ZONE.getId(),sections);
    }

    private Section alcohol(Map<String,List<Map<String,Object>>> data, Set<String> emptySessions,
                            boolean showEstimate,Map<String,List<Map<String,Object>>> context,
                            LocalDate from,LocalDate to,LocalDateTime now) {
        var sessions=data.get("sessions");var drinks=data.get("drinks");
        Map<Object,String> names=new HashMap<>();
        List<Map<String,Object>> records=new ArrayList<>();
        for(var session:sessions) {
            var record=fields("sessions",session);
            record.put("记录类型","场次");
            record.put("场次标记",names.computeIfAbsent(session.get("id"),id->"场次 "+(names.size()+1)));
            record.put("缺少酒品明细",emptySessions.contains(String.valueOf(session.get("id"))));
            records.add(record);
        }
        for(var drink:drinks) {
            var record=fields("drinks",drink);
            record.put("记录类型","酒品明细");
            record.put("场次标记",names.computeIfAbsent(drink.get("sessionId"),id->"场次 "+(names.size()+1)));
            put(record,"酒品",drink.get("snapshotName"));
            put(record,"采用的酒精度（%vol）",drink.get("snapshotAbv"));
            record.put("本人饮用量（毫升）",drink.get("actualMl"));
            record.put("纯酒精（克）",drink.get("ethanolGrams"));
            record.put("折合42度白酒（毫升）",BehaviorCalculator.equivalent42(decimal(drink.get("ethanolGrams"))));
            record.put("用量估算费用（元）",drink.get("cost"));
            records.add(record);
        }
        Map<String,Object> summary=new LinkedHashMap<>();
        summary.put("开始的场次数",sessions.size());
        summary.put("酒品明细数",drinks.size());
        summary.put("无酒品明细的场次数",emptySessions.size());
        known(summary,"已知本人饮用量（毫升）","缺饮用量的项数",drinks,"actualMl",emptySessions.size());
        known(summary,"已知纯酒精（克）","缺纯酒精量的项数",drinks,"ethanolGrams",emptySessions.size());
        summary.put("已知酒精量折合42度白酒（毫升）",BehaviorCalculator.equivalent42(decimal(summary.get("已知纯酒精（克）"))));
        known(summary,"已知估算费用（元）","缺费用的项数",drinks,"cost",emptySessions.size());
        if(showEstimate) {
            LocalDateTime end=to.atTime(LocalTime.MAX);
            var model=BehaviorCalculator.alcoholEstimate(context,end.isBefore(now)?end:now,now);
            String latest=day(model.get("latestDrinkAt"));
            if(latest!=null && latest.compareTo(from.toString())>=0 && latest.compareTo(to.toString())<=0) {
                if("ESTIMATE".equals(model.get("status"))) {
                    summary.put("代谢时长情景估算",model.get("durationHoursLow")+"–"+model.get("durationHoursHigh")+" 小时，从最后一次饮用起算。不能据此判断清醒或驾驶能力。");
                    summary.put("模型说明",model.get("warnings"));
                } else if("INSUFFICIENT_DATA".equals(model.get("status"))) summary.put("饮酒估算缺失项",model.get("missing"));
            }
        }
        return new Section("alcohol","饮酒","场次按开始日期、酒品按饮用日期分别选取。相同场次标记可连接两类记录。场次与明细不能相加计算饮酒次数。"+ZERO_RULE+COST_RULE+"容量或酒精度未知时保留缺项。",summary,records);
    }

    private Map<String,Object> fields(String name,Map<String,Object> row) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(var field:schema.require(name).fields()) {
            String key=field.key();
            // External references and manually supplied drinking details are only added
            // explicitly when the matching category is selected.
            if(key.endsWith("Id")||key.equals("manualAlcohol")||key.equals("overrideReason")) continue;
            Object value=row.get(key);
            if(value==null)continue;
            if(!field.options().isEmpty()) value=field.options().stream()
                    .filter(option->option.get("value").equals(row.get(key)))
                    .map(option->option.get("label")).findFirst().orElse(row.get(key).toString());
            if(field.type().equals("datetime") && row.containsKey("timePrecision")) {
                String time=value.toString();
                if("DATE".equals(row.get("timePrecision"))) value=day(time);
                if("APPROX".equals(row.get("timePrecision")) && time.length()>=13)
                    value=time.substring(0,10)+" 约 "+time.substring(11,13)+" 时";
            }
            put(result,field.label(),value);
        }
        return result;
    }

    private static void known(Map<String,Object> result,String label,String missingLabel,
                              List<Map<String,Object>> rows,String field,int extraMissing) {
        long missing=rows.stream().filter(r->r.get(field)==null).count();
        result.put(label,(rows.size()==missing && missing+extraMissing>0)?null:sum(rows,field));
        result.put(missingLabel,missing+extraMissing);
    }
    private static BigDecimal amount(List<Map<String,Object>> rows,String direction) {
        return sum(rows.stream().filter(r->direction.equals(r.get("direction"))).toList(),"amount");
    }
    private static BigDecimal sum(List<Map<String,Object>> rows,String key) {
        return rows.stream().map(r->decimal(r.get(key))).filter(Objects::nonNull).reduce(BigDecimal.ZERO,BigDecimal::add);
    }
    private static BigDecimal decimal(Object value) { return value==null?null:new BigDecimal(value.toString()); }
    private static String day(Object value) { return value==null?null:value.toString().substring(0,10); }
    private static void put(Map<String,Object> result,String label,Object value) {
        if(value!=null && !(value instanceof List<?> list && list.isEmpty())) result.put(label,value);
    }
}
