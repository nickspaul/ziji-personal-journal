package cn.ziji.data;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

@Repository
public class DataRepository {
    private final JdbcTemplate jdbc;
    private final SchemaRegistry registry;
    private final ObjectMapper json;
    public DataRepository(JdbcTemplate jdbc,SchemaRegistry registry,ObjectMapper json) {
        this.jdbc=jdbc;this.registry=registry;this.json=json;
    }
    public String json(Object object) {
        try { return json.writeValueAsString(object); }
        catch(Exception e) { throw new IllegalArgumentException("数据无法序列化"); }
    }
    public Map<String,Object> object(String value) {
        if(value==null||value.isBlank()) return new LinkedHashMap<>();
        try { return json.readValue(value,new TypeReference<LinkedHashMap<String,Object>>(){}); }
        catch(Exception e) { throw new IllegalStateException("保存的数据格式异常"); }
    }
    private Map<String,Object> row(SchemaRegistry.Collection c,ResultSet rs) throws SQLException {
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("id",rs.getString("id"));m.put("version",rs.getLong("version"));
        m.put("createdAt",rs.getTimestamp("createdAt").toLocalDateTime().toString());
        m.put("updatedAt",rs.getTimestamp("updatedAt").toLocalDateTime().toString());m.put("deleted",rs.getBoolean("deleted"));
        for(var f:c.fields()) {
            Object value=switch(f.type()) {
                case "number" -> rs.getBigDecimal(f.key());
                case "boolean" -> rs.getObject(f.key())==null?null:rs.getBoolean(f.key());
                case "date" -> rs.getDate(f.key())==null?null:rs.getDate(f.key()).toLocalDate().toString();
                case "datetime" -> rs.getTimestamp(f.key())==null?null:rs.getTimestamp(f.key()).toLocalDateTime().toString();
                case "time" -> rs.getTime(f.key())==null?null:rs.getTime(f.key()).toLocalTime().toString();
                case "strings" -> strings(rs.getString(f.key()));
                default -> rs.getString(f.key());
            };
            m.put(f.key(),value);
        }
        Map<String,Object> derived=object(rs.getString("derivedJson"));
        derived.forEach((k,v)->{if(!m.containsKey(k))m.put(k,v);});
        return m;
    }
    public Map<String,Object> mapRow(String collection,ResultSet rs) throws SQLException { return row(registry.require(collection),rs); }
    private List<String> strings(String value) {
        if(value==null||value.isBlank())return List.of();
        try {
            Object decoded=json.readValue(value,Object.class);if(decoded==null)return List.of();
            if(!(decoded instanceof List<?> list)||list.stream().anyMatch(item->!(item instanceof String)))throw new IllegalArgumentException();
            return list.stream().map(String.class::cast).toList();
        }
        catch(Exception e){throw new IllegalStateException("保存的标签或人员格式异常");}
    }
    public List<Map<String,Object>> list(String collection,Boolean deleted) {
        var c=registry.require(collection);
        String sql="SELECT * FROM "+c.table()+(deleted==null?"":" WHERE deleted=?")+" ORDER BY createdAt DESC,id DESC";
        return deleted==null?jdbc.query(sql,(rs,n)->row(c,rs)):jdbc.query(sql,(rs,n)->row(c,rs),deleted);
    }
    public Map<String,Object> find(String collection,String id) {
        var c=registry.require(collection);
        List<Map<String,Object>> result=jdbc.query("SELECT * FROM "+c.table()+" WHERE id=?",(rs,n)->row(c,rs),id);
        return result.isEmpty()?null:result.get(0);
    }
    public List<Map<String,Object>> debtHistoryExcept(String platform,String id) {
        return jdbc.query("SELECT `date`,debtKind,debtPlatform,amount,debtFee FROM zj_ledger WHERE deleted=FALSE AND debtPlatform=? AND id<>?",
            (rs,n)->{
                Map<String,Object> row = new LinkedHashMap<>();
                row.put("date",rs.getDate("date").toLocalDate().toString());
                row.put("debtKind",rs.getString("debtKind"));row.put("debtPlatform",rs.getString("debtPlatform"));
                row.put("amount",rs.getBigDecimal("amount"));row.put("debtFee",rs.getBigDecimal("debtFee"));
                return row;
            },platform,id);
    }
    private Object value(SchemaRegistry.Field field,Object value) {
        if(value==null)return null;
        return switch(field.type()) {
            case "datetime" -> Timestamp.valueOf(LocalDateTime.parse(value.toString()));
            case "date" -> java.sql.Date.valueOf(value.toString());
            case "time" -> java.sql.Time.valueOf(LocalTime.parse(value.toString()));
            case "strings" -> json(value);
            default -> value;
        };
    }
    private Map<String,Object> derived(SchemaRegistry.Collection c,Map<String,Object> row) {
        Map<String,Object> values=new LinkedHashMap<>(row);
        Set<String> keys=new HashSet<>(List.of("id","version","createdAt","updatedAt","deleted"));
        c.fields().forEach(f->keys.add(f.key()));keys.forEach(values::remove);
        return values;
    }
    public void insert(String collection,Map<String,Object> row) {
        var c=registry.require(collection);List<String> columns=new ArrayList<>(List.of("id","version","createdAt","updatedAt","deleted"));
        List<Object> values=new ArrayList<>(Arrays.asList(row.get("id"),row.get("version"),Timestamp.valueOf(LocalDateTime.parse(row.get("createdAt").toString())),Timestamp.valueOf(LocalDateTime.parse(row.get("updatedAt").toString())),row.get("deleted")));
        for(var f:c.fields()){columns.add(f.key());values.add(value(f,row.get(f.key())));}
        columns.add("derivedJson");values.add(json(derived(c,row)));
        String names=String.join(",",columns.stream().map(k->"`"+k+"`").toList());
        jdbc.update("INSERT INTO "+c.table()+" ("+names+") VALUES ("+String.join(",",Collections.nCopies(columns.size(),"?"))+")",values.toArray());
    }
    public boolean update(String collection,Map<String,Object> row,long expectedVersion) {
        var c=registry.require(collection);List<String> parts=new ArrayList<>(List.of("version=?","updatedAt=?","deleted=?"));
        List<Object> values=new ArrayList<>(Arrays.asList(row.get("version"),Timestamp.valueOf(LocalDateTime.parse(row.get("updatedAt").toString())),row.get("deleted")));
        for(var f:c.fields()){parts.add("`"+f.key()+"`=?");values.add(value(f,row.get(f.key())));}
        parts.add("derivedJson=?");values.add(json(derived(c,row)));values.add(row.get("id"));values.add(expectedVersion);
        return jdbc.update("UPDATE "+c.table()+" SET "+String.join(",",parts)+" WHERE id=? AND version=?",values.toArray())==1;
    }
    public void audit(String action,String collection,String id,Map<String,Object> before,Map<String,Object> after) {
        jdbc.update("INSERT INTO zj_audit(id,occurredAt,action,collectionName,rowId,beforeJson,afterJson) VALUES (?,?,?,?,?,?,?)",UUID.randomUUID().toString(),Timestamp.valueOf(LocalDateTime.now(DataValidator.ZONE)),action,collection,id,before==null?null:json(before),after==null?null:json(after));
    }
    public List<Map<String,Object>> auditList(int limit) {
        return jdbc.query("SELECT * FROM zj_audit ORDER BY occurredAt DESC,id DESC LIMIT ?",(rs,n)->{
            Map<String,Object> m=new LinkedHashMap<>();m.put("id",rs.getString("id"));m.put("occurredAt",rs.getTimestamp("occurredAt").toLocalDateTime().toString());m.put("action",rs.getString("action"));m.put("collection",rs.getString("collectionName"));m.put("rowId",rs.getString("rowId"));m.put("before",object(rs.getString("beforeJson")));m.put("after",object(rs.getString("afterJson")));return m;
        },Math.min(5000,Math.max(1,limit)));
    }
    public void lockSettings() {jdbc.queryForObject("SELECT id FROM zj_settings WHERE id=1 FOR UPDATE",Integer.class);}
    public Map<String,Object> settings() {return object(jdbc.queryForObject("SELECT payload FROM zj_settings WHERE id=1",String.class));}
    public void settings(Map<String,Object> values) {jdbc.update("UPDATE zj_settings SET payload=?,updatedAt=NOW(6) WHERE id=1",json(values));}
}
