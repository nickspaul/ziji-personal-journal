package cn.ziji.backup;

import cn.ziji.data.*;
import cn.ziji.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@Service
public class BackupService {
    private static final Logger log=LoggerFactory.getLogger(BackupService.class);
    // Import-only names: retired tables and CRUD schemas must not be recreated.
    private static final Map<String,String> RETIRED_COLLECTIONS=Map.of("completeness","每日完整度","goals","个人目标");
    private final DataService data;private final DataRepository repository;private final SchemaRegistry registry;private final DataValidator validator;private final ObjectMapper json;private final JdbcTemplate jdbc;private final Path backupDirectory;
    public BackupService(DataService data,DataRepository repository,SchemaRegistry registry,DataValidator validator,ObjectMapper json,JdbcTemplate jdbc,@Value("${ziji.data-directory}") String directory) {
        this.data=data;this.repository=repository;this.registry=registry;this.validator=validator;this.json=json;this.jdbc=jdbc;this.backupDirectory=Path.of(directory).toAbsolutePath().normalize().resolve("backups");
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> export() {
        Map<String,Object> backup=new LinkedHashMap<>();backup.put("format","ziji-backup");backup.put("schemaVersion",1);backup.put("exportedAt",DataValidator.now());backup.put("timezone","Asia/Shanghai");backup.put("collections",data.state(null));backup.put("settings",data.settings());backup.put("audit",auditExport());return backup;
    }
    private List<Map<String,Object>> auditExport(){return jdbc.query("SELECT * FROM zj_audit ORDER BY occurredAt,id",(rs,n)->{Map<String,Object> row=new LinkedHashMap<>();row.put("id",rs.getString("id"));row.put("occurredAt",rs.getTimestamp("occurredAt").toLocalDateTime().toString());row.put("action",rs.getString("action"));row.put("collection",rs.getString("collectionName"));row.put("rowId",rs.getString("rowId"));row.put("before",repository.object(rs.getString("beforeJson")));row.put("after",repository.object(rs.getString("afterJson")));return row;});}
    private record Plan(Map<String,List<Map<String,Object>>> additions,Map<String,Object> preview){}
    @SuppressWarnings("unchecked")
    private Plan plan(Map<String,Object> backup) {
        if(!"ziji-backup".equals(backup.get("format"))||!"1".equals(String.valueOf(backup.get("schemaVersion"))))throw new IllegalArgumentException("备份格式或版本不受支持");
        if(!(backup.get("collections") instanceof Map<?,?> raw))throw new IllegalArgumentException("备份缺少数据集合");
        List<Map<String,Object>> ignoredCollections=new ArrayList<>();List<String> warnings=new ArrayList<>();int ignoredRecords=0;
        for(var entry:raw.entrySet()) {
            String name=entry.getKey().toString();String label=RETIRED_COLLECTIONS.get(name);
            if(label==null){registry.require(name);continue;}
            Object value=entry.getValue();if(value==null)value=List.of();
            if(!(value instanceof List<?> list))throw new IllegalArgumentException(label+"应为数组");
            ignoredRecords+=list.size();if(ignoredRecords>100000)throw new IllegalArgumentException("一次恢复最多十万条业务记录");
            ignoredCollections.add(Map.of("collection",name,"label",label,"count",list.size()));
            if(!list.isEmpty())warnings.add(label+"已移除，将跳过旧备份中的 "+list.size()+" 条记录；其他有效数据照常校验和恢复。");
        }
        Map<String,List<Map<String,Object>>> union=data.state(null);Map<String,List<Map<String,Object>>> additions=new LinkedHashMap<>();Map<String,Object> counts=new LinkedHashMap<>();List<String> errors=new ArrayList<>();List<Map<String,Object>> conflicts=new ArrayList<>();int total=ignoredRecords,newCount=0,existingCount=0;
        for(var c:registry.all()) {
            Object source=raw.get(c.name());if(source==null)source=List.of();if(!(source instanceof List<?> list))throw new IllegalArgumentException(c.label()+"应为数组");
            if((total+=list.size())>100000)throw new IllegalArgumentException("一次恢复最多十万条业务记录");
            List<Map<String,Object>> created=new ArrayList<>();Set<String> seen=new HashSet<>();int skipped=0,conflictCount=0;
            for(Object item:list) {
                try {
                    if(!(item instanceof Map<?,?>))throw new IllegalArgumentException("行内容应为对象");Map<String,Object> incoming=(Map<String,Object>)item;
                    String id=DataValidator.uuid(incoming.get("id"));if(!seen.add(id))throw new IllegalArgumentException("备份内有重复 ID："+id);
                    Map<String,Object> normalized=validator.normalize(c.name(),incoming,null,true);
                    normalized.put("id",id);normalized.put("version",DataService.version(incoming.get("version")));normalized.put("createdAt",LocalDateTime.parse(String.valueOf(incoming.get("createdAt"))).toString());normalized.put("updatedAt",LocalDateTime.parse(String.valueOf(incoming.get("updatedAt"))).toString());
                    if(!(incoming.get("deleted") instanceof Boolean))throw new IllegalArgumentException("缺少删除状态");normalized.put("deleted",incoming.get("deleted"));
                    Set<String> businessKeys=new HashSet<>(DataValidator.META);c.fields().forEach(f->businessKeys.add(f.key()));
                    for(var entry:incoming.entrySet())if(!businessKeys.contains(entry.getKey())&&validator.isDerived(entry.getKey())&&!entry.getKey().equals("derivedJson")){validateSnapshot(entry.getKey(),entry.getValue());normalized.put(entry.getKey(),entry.getValue());}
                    checkSnapshotCalculations(c.name(),normalized);
                    Map<String,Object> existing=union.get(c.name()).stream().filter(r->id.equals(r.get("id"))).findFirst().orElse(null);
                    if(existing!=null){skipped++;existingCount++;if(!equivalent(existing,normalized)){conflictCount++;if(conflicts.size()<100)conflicts.add(Map.of("collection",c.name(),"id",id,"action","KEEP_CURRENT"));}}
                    else {created.add(normalized);newCount++;}
                }catch(RuntimeException e){if(errors.size()<50)errors.add(c.label()+"："+(e.getMessage()==null?"数据无效":e.getMessage()));}
            }
            additions.put(c.name(),created);counts.put(c.name(),Map.of("new",created.size(),"existing",skipped,"conflicts",conflictCount));
        }
        for(var e:additions.entrySet()){List<Map<String,Object>> merged=new ArrayList<>(union.get(e.getKey()));merged.addAll(e.getValue());union.put(e.getKey(),merged);}
        for(var e:additions.entrySet())for(var row:e.getValue())try {
            data.checkReferences(e.getKey(),row,null,union);
            if(e.getKey().equals("drinks")&&!Boolean.TRUE.equals(row.get("deleted"))&&union.get("sessions").stream().anyMatch(s->Objects.equals(s.get("id"),row.get("sessionId"))&&Boolean.TRUE.equals(s.get("deleted"))))throw new IllegalArgumentException("有效饮酒明细关联了已删除场次");
        }catch(RuntimeException ex){if(errors.size()<50)errors.add(e.getKey()+" "+row.get("id")+"："+ex.getMessage());}
        try{cn.ziji.ledger.DebtAccounting.validateHistory(union.getOrDefault("ledger",List.of()));}
        catch(IllegalArgumentException e){errors.add("负债余额："+e.getMessage());}
        try{validateAudit(backup.get("audit"));}catch(RuntimeException e){errors.add("操作历史："+e.getMessage());}
        if(backup.get("settings")!=null&&!(backup.get("settings") instanceof Map<?,?>))errors.add("设置内容应为对象");
        if(backup.get("settings") instanceof Map<?,?> settings) {
            try {
                if(settings.containsKey("journalPinnedFilters"))DataService.validateJournalPins(settings.get("journalPinnedFilters"));
                if(settings.containsKey("journalVocabulary"))DataService.validateJournalVocabulary(settings.get("journalVocabulary"));
                if(settings.containsKey("journalView")&&!Set.of("day","search","tasks").contains(settings.get("journalView")))throw new IllegalArgumentException("生活日志视图不正确");
            }catch(RuntimeException e){errors.add("生活日志设置："+e.getMessage());}
        }
        Map<String,Object> preview=new LinkedHashMap<>();preview.put("valid",errors.isEmpty());preview.put("counts",counts);preview.put("newRecords",newCount);preview.put("existingRecords",existingCount);preview.put("conflicts",conflicts);preview.put("errors",errors);preview.put("policy","保留本机已有 ID 的记录，只导入新 ID；历史快照保留。当前设置优先，备份不包含解锁口令。");
        preview.put("ignoredCollections",ignoredCollections);preview.put("ignoredRecords",ignoredRecords);preview.put("warnings",warnings);
        return new Plan(additions,preview);
    }
    public Map<String,Object> preview(Map<String,Object> backup){return plan(backup).preview();}
    private boolean equivalent(Object a,Object b) {
        if(a==null||b==null)return a==b;
        if(a instanceof Number&&b instanceof Number)return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()))==0;
        if(a instanceof Map<?,?> am&&b instanceof Map<?,?> bm){Set<Object> keys=new HashSet<>(am.keySet());keys.addAll(bm.keySet());return keys.stream().allMatch(k->equivalent(am.get(k),bm.get(k)));}
        if(a instanceof List<?> al&&b instanceof List<?> bl){if(al.size()!=bl.size())return false;for(int i=0;i<al.size();i++)if(!equivalent(al.get(i),bl.get(i)))return false;return true;}
        return a.equals(b);
    }
    private void validateSnapshot(String key,Object value) {
        if(value==null)return;
        Set<String> numeric=Set.of("snapshotUnitCount","snapshotUnitPrice","snapshotTarMg","snapshotNicotineMg","snapshotCoMg","snapshotBottleMl","snapshotAbv","cost","actualMl","ethanolGrams","ethanolEquivalent42Ml","tarTotalMg","nicotineTotalMg","coTotalMg","equivalentBottles","estimatedCost","referenceValue");
        if(numeric.contains(key)) {
            if(!(value instanceof Number))throw new IllegalArgumentException("快照字段应为数字："+key);
            BigDecimal n=new BigDecimal(value.toString());if(n.signum()<0||n.abs().compareTo(new BigDecimal("99999999999999"))>0)throw new IllegalArgumentException("快照数值无效："+key);
            if(key.equals("snapshotAbv")&&n.compareTo(new BigDecimal("100"))>0)throw new IllegalArgumentException("快照酒精度超过100");
            if(Set.of("snapshotUnitCount","snapshotBottleMl").contains(key)&&n.signum()==0)throw new IllegalArgumentException("快照规格必须大于零");return;
        }
        if(key.equals("warnings")){if(value instanceof List<?> list&&list.size()<100&&list.stream().allMatch(x->x instanceof String&&x.toString().length()<2000))return;throw new IllegalArgumentException("快照说明应为文本数组");}
        if(value instanceof String s){if(s.length()>10000)throw new IllegalArgumentException("快照文本过长");if(key.equals("snapshotPriceSourceId"))DataValidator.uuid(s);if(Set.of("snapshotPriceEffectiveDate","snapshotSourceDate").contains(key))LocalDate.parse(s);return;}
        throw new IllegalArgumentException("快照字段格式无效："+key);
    }
    private BigDecimal decimal(Map<String,Object> row,String key){return row.get(key)==null?null:new BigDecimal(row.get(key).toString());}
    private void matches(BigDecimal expected,BigDecimal actual,String message){matches(expected,actual,4,message);}
    private void matches(BigDecimal expected,BigDecimal actual,int scale,String message){if(expected!=null&&actual!=null&&expected.setScale(scale,RoundingMode.HALF_UP).compareTo(actual.setScale(scale,RoundingMode.HALF_UP))!=0)throw new IllegalArgumentException(message);}
    private void checkSnapshotCalculations(String collection,Map<String,Object> row) {
        if(Set.of("GIFT","PAID_BY_OTHER").contains(String.valueOf(row.get("costType")))&&decimal(row,"cost")!=null&&decimal(row,"cost").signum()!=0)throw new IllegalArgumentException("别人提供的个人分摊成本应为零");
        if(collection.equals("smoking")) {
            BigDecimal count=decimal(row,"count"),unit=decimal(row,"snapshotUnitPrice");
            if(unit!=null&&!Set.of("GIFT","PAID_BY_OTHER").contains(String.valueOf(row.get("costType"))))matches(count.multiply(unit),decimal(row,"cost"),2,"吸烟成本与快照不一致");
            for(String[] pair:new String[][]{{"snapshotTarMg","tarTotalMg"},{"snapshotNicotineMg","nicotineTotalMg"},{"snapshotCoMg","coTotalMg"}}){BigDecimal mg=decimal(row,pair[0]);if(mg!=null)matches(count.multiply(mg),decimal(row,pair[1]),"成分折算与快照不一致");}
        }
        if(collection.equals("drinks")) {
            BigDecimal actual=decimal(row,"actualMl"),abv=decimal(row,"snapshotAbv"),bottle=decimal(row,"snapshotBottleMl");
            if("ML".equals(row.get("inputMode")))matches(decimal(row,"volumeMl"),actual,"饮用毫升与快照不一致");
            else if(bottle!=null&&row.get("bottles")!=null)matches(decimal(row,"bottles").multiply(bottle).multiply(row.get("share")==null?BigDecimal.ONE:decimal(row,"share")),actual,"瓶数换算与快照不一致");
            if(actual!=null&&abv!=null)matches(actual.multiply(abv).multiply(new BigDecimal("0.00789")),decimal(row,"ethanolGrams"),"纯酒精折算与快照不一致");
            BigDecimal ethanol=decimal(row,"ethanolGrams");
            if(ethanol!=null)matches(ethanol.divide(new BigDecimal("0.33138"),8,RoundingMode.HALF_UP),decimal(row,"ethanolEquivalent42Ml"),2,"42度白酒等量换算与纯酒精量不一致");
            BigDecimal unit=decimal(row,"snapshotUnitPrice");if(actual!=null&&bottle!=null&&bottle.signum()>0&&unit!=null&&!Set.of("GIFT","PAID_BY_OTHER").contains(String.valueOf(row.get("costType"))))matches(actual.multiply(unit).divide(bottle,8,RoundingMode.HALF_UP),decimal(row,"cost"),2,"饮酒成本与快照不一致");
        }
    }
    @Transactional(isolation=Isolation.SERIALIZABLE)
    public Map<String,Object> restore(Map<String,Object> backup,String mode) {
        if(!"merge".equals(mode))throw new IllegalArgumentException("仅支持预览后的合并恢复");
        repository.lockSettings();
        Plan plan=plan(backup);if(!Boolean.TRUE.equals(plan.preview().get("valid")))throw new IllegalArgumentException("备份校验失败："+plan.preview().get("errors"));
        String preBackup=savePreRestore();int inserted=0;
        for(var e:plan.additions().entrySet())for(var row:e.getValue()){repository.insert(e.getKey(),row);repository.audit("IMPORT",e.getKey(),row.get("id").toString(),null,row);inserted++;}
        if(backup.get("settings") instanceof Map<?,?> backedSettings) {
            Map<String,Object> merged=new LinkedHashMap<>();backedSettings.forEach((k,v)->merged.put(k.toString(),v));merged.putAll(repository.settings());data.updateSettings(merged);
        }
        restoreAudit(backup.get("audit"));
        repository.audit("BACKUP_RESTORE","backup",preBackup,null,Map.of("inserted",inserted,"mode",mode));
        Map<String,Object> result=new LinkedHashMap<>(plan.preview());result.put("imported",inserted);result.put("preRestoreBackup",preBackup);result.put("message","恢复完成；同 ID 的本机记录和当前设置保持不变");return result;
    }
    private String savePreRestore() {return saveSnapshot("PRE_RESTORE","before-restore-");}
    private String saveSnapshot(String reason,String prefix) {
        try {
            Files.createDirectories(backupDirectory);String id=UUID.randomUUID().toString();String name=prefix+id+".json";Path target=backupDirectory.resolve(name).normalize();if(!target.startsWith(backupDirectory))throw new IllegalArgumentException("备份路径无效");
            byte[] bytes=json.writerWithDefaultPrettyPrinter().writeValueAsBytes(export());Files.write(target,bytes,StandardOpenOption.CREATE_NEW);
            jdbc.update("INSERT INTO zj_backups(id,createdAt,reason,fileName,sizeBytes) VALUES (?,NOW(6),?,?,?)",id,reason,name,bytes.length);return name;
        }catch(Exception e){throw new ApiException(500,"恢复前备份失败，未导入任何数据，请检查备份目录权限与磁盘空间");}
    }
    @Scheduled(initialDelay=60000,fixedDelay=3600000)
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public void automaticBackup() {
        try {
            boolean hasData=registry.all().stream().filter(c->!c.name().equals("health")).anyMatch(c->!repository.list(c.name(),null).isEmpty());if(!hasData)return;
            java.sql.Timestamp previous=jdbc.queryForObject("SELECT MAX(createdAt) FROM zj_backups WHERE reason='AUTO_DAILY'",java.sql.Timestamp.class);
            if(previous!=null&&previous.toLocalDateTime().plusHours(24).isAfter(LocalDateTime.now(DataValidator.ZONE)))return;
            saveSnapshot("AUTO_DAILY","auto-daily-");log.info("Daily local backup completed");
        }catch(RuntimeException e){log.warn("Automatic backup was not completed: {}",e.getClass().getSimpleName());}
    }
    @SuppressWarnings("unchecked")
    private void restoreAudit(Object raw) {
        validateAudit(raw);if(raw==null)return;List<?> rows=(List<?>)raw;
        for(Object obj:rows) {
            if(!(obj instanceof Map<?,?> map))throw new IllegalArgumentException("操作历史格式不正确");
            String id=DataValidator.uuid(map.get("id"));LocalDateTime time=LocalDateTime.parse(String.valueOf(map.get("occurredAt")));String action=String.valueOf(map.get("action"));String collection=String.valueOf(map.get("collection"));String rowId=map.get("rowId")==null?null:map.get("rowId").toString();
            if(action.length()>40||collection.length()>80||(rowId!=null&&rowId.length()>100))throw new IllegalArgumentException("操作历史字段过长");
            if(jdbc.queryForObject("SELECT COUNT(*) FROM zj_audit WHERE id=?",Integer.class,id)==0)jdbc.update("INSERT INTO zj_audit(id,occurredAt,action,collectionName,rowId,beforeJson,afterJson) VALUES (?,?,?,?,?,?,?)",id,java.sql.Timestamp.valueOf(time),action,collection,rowId,repository.json(map.get("before")),repository.json(map.get("after")));
        }
    }
    private void validateAudit(Object raw) {
        if(raw==null)return;if(!(raw instanceof List<?> rows)||rows.size()>200000)throw new IllegalArgumentException("格式或数量不正确");Set<String> ids=new HashSet<>();
        for(Object obj:rows) {
            if(!(obj instanceof Map<?,?> map))throw new IllegalArgumentException("每条历史应为对象");String id=DataValidator.uuid(map.get("id"));if(!ids.add(id))throw new IllegalArgumentException("重复历史ID");LocalDateTime.parse(String.valueOf(map.get("occurredAt")));
            if(!(map.get("action") instanceof String action)||action.isBlank()||action.length()>40)throw new IllegalArgumentException("操作名称无效");if(!(map.get("collection") instanceof String c)||c.isBlank()||c.length()>80)throw new IllegalArgumentException("集合名称无效");
            if(map.get("rowId")!=null&&(!(map.get("rowId") instanceof String)||map.get("rowId").toString().length()>100))throw new IllegalArgumentException("对象ID无效");
            for(String key:List.of("before","after"))if(map.get(key)!=null&&!(map.get(key) instanceof Map<?,?>))throw new IllegalArgumentException("修改前后值应为对象");
        }
    }
    public List<Map<String,Object>> list(){return jdbc.queryForList("SELECT id,createdAt,reason,fileName,sizeBytes FROM zj_backups ORDER BY createdAt DESC");}
}
