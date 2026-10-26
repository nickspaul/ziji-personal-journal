package cn.ziji.journal;

import cn.ziji.data.DataRepository;
import cn.ziji.data.DataService;
import cn.ziji.data.DataValidator;
import cn.ziji.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Search only the requested page; daily matches use interval boundaries rather than copied diary rows. */
@Service
public class JournalQueryService {
    private final JdbcTemplate jdbc;
    private final DataRepository repository;
    private final DataService data;
    private final TaskEffortService effort;
    private static final Set<String> CATEGORIES=Set.of("LIFE","MEAL","WORK","STUDY","TRAVEL","SOCIAL","OTHER");
    private static final Set<String> STATUSES=Set.of("TODO","DOING","DONE","PAUSED","CANCELLED","OPEN","ANY");
    private static final Pattern TERMS=Pattern.compile("#\"((?:\\\\.|[^\"\\\\])*)\"|\\S+");

    public JournalQueryService(JdbcTemplate jdbc,DataRepository repository,DataService data,TaskEffortService effort) {
        this.jdbc=jdbc;this.repository=repository;this.data=data;this.effort=effort;
    }

    record SearchPlan(String collection,String where,List<Object> arguments,int page,int size,
                      boolean ascending,boolean dates,LocalDate from,LocalDate to,String dateField) {}

    static SearchPlan plan(MultiValueMap<String,String> parameters) {
        String collection=value(parameters,"collection","journal");
        if(!Set.of("journal","tasks").contains(collection))throw invalid("请选择生活日志或待办");
        int page=integer(parameters,"page",1,1,1000000),size=integer(parameters,"size",20,1,50);
        if(size!=20&&size!=50)throw invalid("每页支持 20 或 50 条");
        String order=value(parameters,"order","desc"),view=value(parameters,"view","items");
        if(!Set.of("asc","desc").contains(order)||!Set.of("items","dates").contains(view))throw invalid("排序或查看方式不正确");
        if(view.equals("dates")&&!collection.equals("journal"))throw invalid("日期视图用于生活日志");
        LocalDate from=date(parameters.getFirst("from")),to=date(parameters.getFirst("to"));
        if(from!=null&&to!=null&&from.isAfter(to))throw invalid("开始日期不能晚于结束日期");
        List<String> clauses=new ArrayList<>();List<Object> args=new ArrayList<>();
        clauses.add("r.deleted=?");args.add(bool(parameters,"deleted",false));
        String dateField=value(parameters,"dateField","plannedDate");
        if(!Set.of("plannedDate","dueDate","completedDate").contains(dateField))throw invalid("任务日期类型不正确");
        if(collection.equals("journal")) {
            if(from!=null){clauses.add("COALESCE(r.endDate,r.startDate)>=?");args.add(from.toString());}
            if(to!=null){clauses.add("r.startDate<=?");args.add(to.toString());}
            String category=value(parameters,"category","");
            if(!category.isEmpty()){
                if(!CATEGORIES.contains(category))throw invalid("日志分类不正确");
                clauses.add("r.category=?");args.add(category);
            }
            String taskId=value(parameters,"taskId","");
            if(!taskId.isEmpty()){clauses.add("r.taskId=?");args.add(DataValidator.uuid(taskId));}
            String person=value(parameters,"person","");
            if(!person.isEmpty()){bounded(person,80,"人物");clauses.add("JSON_CONTAINS(COALESCE(r.people,JSON_ARRAY()),JSON_QUOTE(?))");args.add(person);}
            String place=value(parameters,"place","");
            if(!place.isEmpty()){bounded(place,500,"地点");clauses.add("LOWER(COALESCE(r.place,'')) LIKE ? ESCAPE '!'");args.add(like(place));}
        } else {
            if(from!=null){clauses.add("r."+dateField+">=?");args.add(from.toString());}
            if(to!=null){clauses.add("r."+dateField+"<=?");args.add(to.toString());}
            String status=value(parameters,"status","ANY");
            if(status.equals("ALL"))status="ANY";
            if(!STATUSES.contains(status))throw invalid("待办状态不正确");
            if(status.equals("OPEN"))clauses.add("r.status IN ('TODO','DOING','PAUSED')");
            else if(!status.equals("ANY")){clauses.add("r.status=?");args.add(status);}
            if(bool(parameters,"unscheduled",false))clauses.add("r.plannedDate IS NULL");
            if(bool(parameters,"overdue",false)){
                clauses.add("r.status IN ('TODO','DOING','PAUSED') AND r.dueDate<?");args.add(LocalDate.now(DataValidator.ZONE).toString());
            }
            if(bool(parameters,"important",false))clauses.add("r.important=TRUE");
        }
        LinkedHashSet<String> tags=new LinkedHashSet<>();
        for(String raw:parameters.getOrDefault("tags",List.of()))if(raw!=null&&!raw.isBlank())tags.add(tag(raw));
        if(tags.size()>20)throw invalid("一次最多筛选 20 个标签");
        String tagMode=value(parameters,"tagMode","all");
        if(!Set.of("all","any").contains(tagMode))throw invalid("标签匹配方式不正确");
        if(!tags.isEmpty()){
            clauses.add("("+String.join(tagMode.equals("all")?" AND ":" OR ",Collections.nCopies(tags.size(),tagClause()))+")");args.addAll(tags);
        }
        String q=value(parameters,"q","");bounded(q,500,"搜索内容");
        if(!q.isBlank()){
            Matcher terms=TERMS.matcher(q);int termCount=0;
            List<String> fields=collection.equals("journal")?List.of("title","content","place","people","tags"):List.of("title","content","result","tags");
            while(terms.find()){
                if(++termCount>20)throw invalid("一次最多搜索 20 个词");
                String term=terms.group();
                if(term.startsWith("#")){clauses.add(tagClause());args.add(tag(terms.group(1)==null?term:unquote(terms.group(1))));}
                else{
                    clauses.add("("+String.join(" OR ",fields.stream().map(field->"LOWER(COALESCE(CAST(r."+field+" AS CHAR),'')) LIKE ? ESCAPE '!'").toList())+")");
                    for(int i=0;i<fields.size();i++)args.add(like(term));
                }
            }
        }
        return new SearchPlan(collection,String.join(" AND ",clauses),args,page,size,order.equals("asc"),view.equals("dates"),from,to,dateField);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> query(MultiValueMap<String,String> parameters) {
        SearchPlan p=plan(parameters);String table="zj_"+p.collection;
        if(p.dates){
            List<DateRange> ranges=jdbc.query("SELECT r.startDate,r.endDate FROM "+table+" r WHERE "+p.where,
                (rs,n)->new DateRange(rs.getDate("startDate").toLocalDate(),rs.getDate("endDate")==null?null:rs.getDate("endDate").toLocalDate()),p.arguments.toArray());
            return datePage(ranges,p.from,p.to,p.page,p.size,p.ascending);
        }
        Long counted=jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" r WHERE "+p.where,Long.class,p.arguments.toArray());
        long total=counted==null?0:counted;int page=effectivePage(p.page,p.size,total);
        List<Object> args=new ArrayList<>(p.arguments);args.add(p.size);args.add((long)(page-1)*p.size);
        String join=p.collection.equals("journal")?" LEFT JOIN zj_tasks linked ON linked.id=r.taskId":"";
        String columns=p.collection.equals("journal")?"r.*,linked.title AS linkedTaskTitle,linked.deleted AS linkedTaskDeleted":"r.*";
        String ordering=ordering(p);
        List<Map<String,Object>> items=jdbc.query("SELECT "+columns+" FROM "+table+" r"+join+" WHERE "+p.where+" ORDER BY "+ordering+" LIMIT ? OFFSET ?",(rs,n)->{
            Map<String,Object> row=repository.mapRow(p.collection,rs);
            if(p.collection.equals("journal")&&row.get("taskId")!=null){row.put("taskTitle",rs.getString("linkedTaskTitle"));row.put("taskDeleted",rs.getObject("linkedTaskDeleted")==null||rs.getBoolean("linkedTaskDeleted"));}
            return row;
        },args.toArray());
        if(p.collection.equals("tasks")&&!items.isEmpty()) {
            Map<String,Map<String,Object>> totals=effort.forTasks(items.stream().map(row->row.get("id").toString()).toList());
            items.forEach(row->row.put("effort",totals.get(row.get("id").toString())));
        }
        return Map.of("items",items,"total",total,"page",page,"size",p.size);
    }

    static String ordering(SearchPlan p) {
        String direction=p.ascending?" ASC":" DESC";
        if(p.collection.equals("journal"))return "r.startDate"+direction+",COALESCE(r.startTime,'23:59:59')"+direction+",r.id"+direction;
        String date="(r."+p.dateField+" IS NULL) ASC,r."+p.dateField+direction;
        return (p.dateField.equals("plannedDate")?"r.important DESC,"+date:date+",r.important DESC")+
            ",r.createdAt"+direction+",r.id"+direction;
    }

    record DateRange(LocalDate start,LocalDate end) {}
    private record Segment(LocalDate start,LocalDate end,long count) {}
    static Map<String,Object> datePage(List<DateRange> ranges,LocalDate from,LocalDate to,int requestedPage,int size,boolean ascending) {
        TreeMap<LocalDate,Long> boundaries=new TreeMap<>();
        for(DateRange range:ranges){
            LocalDate start=range.start,end=range.end==null?start:range.end;
            if(from!=null&&start.isBefore(from))start=from;if(to!=null&&end.isAfter(to))end=to;
            if(end.isBefore(start))continue;
            boundaries.merge(start,1L,Long::sum);boundaries.merge(end.plusDays(1),-1L,Long::sum);
        }
        List<Segment> segments=new ArrayList<>();LocalDate previous=null;long active=0,total=0;
        for(var e:boundaries.entrySet()){
            if(previous!=null&&active>0&&previous.isBefore(e.getKey())){
                segments.add(new Segment(previous,e.getKey().minusDays(1),active));total+=ChronoUnit.DAYS.between(previous,e.getKey());
            }
            active+=e.getValue();previous=e.getKey();
        }
        int page=effectivePage(requestedPage,size,total);long skip=(long)(page-1)*size;
        if(!ascending)Collections.reverse(segments);
        List<Map<String,Object>> dates=new ArrayList<>();
        for(Segment segment:segments){
            long length=ChronoUnit.DAYS.between(segment.start,segment.end)+1;
            if(skip>=length){skip-=length;continue;}
            LocalDate cursor=ascending?segment.start.plusDays(skip):segment.end.minusDays(skip);length-=skip;skip=0;
            while(length-->0&&dates.size()<size){dates.add(Map.of("date",cursor.toString(),"count",segment.count));cursor=ascending?cursor.plusDays(1):cursor.minusDays(1);}
            if(dates.size()==size)break;
        }
        return Map.of("items",List.of(),"dates",dates,"total",total,"page",page,"size",size);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> facets(String query) {
        String q=query==null?"":query.trim();bounded(q,500,"搜索内容");
        List<Map<String,Object>> tags=jdbc.queryForList("SELECT name,COUNT(*) AS count FROM (SELECT jt.label AS name FROM zj_journal r JOIN JSON_TABLE(COALESCE(r.tags,JSON_ARRAY()),'$[*]' COLUMNS(label VARCHAR(100) PATH '$')) jt WHERE r.deleted=FALSE UNION ALL SELECT tt.label AS name FROM zj_tasks t JOIN JSON_TABLE(COALESCE(t.tags,JSON_ARRAY()),'$[*]' COLUMNS(label VARCHAR(100) PATH '$')) tt WHERE t.deleted=FALSE) labels GROUP BY name ORDER BY count DESC,name LIMIT 100");
        List<String> people=jdbc.queryForList("SELECT DISTINCT jt.label FROM zj_journal r JOIN JSON_TABLE(COALESCE(r.people,JSON_ARRAY()),'$[*]' COLUMNS(label VARCHAR(100) PATH '$')) jt WHERE r.deleted=FALSE ORDER BY jt.label LIMIT 100",String.class);
        List<String> places=jdbc.queryForList("SELECT DISTINCT place FROM zj_journal WHERE deleted=FALSE AND place IS NOT NULL AND place<>'' ORDER BY place LIMIT 100",String.class);
        List<Map<String,Object>> tasks=jdbc.queryForList("SELECT id,title,status FROM zj_tasks WHERE deleted=FALSE AND LOWER(title) LIKE ? ESCAPE '!' ORDER BY CASE WHEN status IN ('TODO','DOING') THEN 0 WHEN status='PAUSED' THEN 1 ELSE 2 END,important DESC,updatedAt DESC,id DESC LIMIT 100",like(q));
        return Map.of("tags",tags,"people",people,"places",places,"tasks",tasks);
    }

    private record TagPlan(String from,String to,List<Map<String,Object>> affected,List<Object> pinned,String token) {}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> previewTag(Map<String,Object> request) {return tagPreview(tagPlan(request));}

    private TagPlan tagPlan(Map<String,Object> request) {
        String from=tag(requiredText(request,"from")),to=tag(requiredText(request,"to"));
        List<Map<String,Object>> affected=new ArrayList<>();
        if(!from.equals(to))for(String collection:List.of("journal","tasks")){
            List<Map<String,Object>> matches=jdbc.query("SELECT * FROM zj_"+collection+" WHERE JSON_CONTAINS(COALESCE(tags,JSON_ARRAY()),JSON_QUOTE(?)) ORDER BY id",(rs,n)->repository.mapRow(collection,rs),from);
            for(var row:matches)affected.add(Map.of("collection",collection,"id",row.get("id"),"version",row.get("version"),"tags",row.get("tags"),"deleted",Boolean.TRUE.equals(row.get("deleted"))));
        }
        List<Object> pinned=asList(data.settings().get("journalPinnedFilters"));
        Map<String,Object> signature=new LinkedHashMap<>();signature.put("from",from);signature.put("to",to);signature.put("rows",affected);signature.put("pinned",pinned);
        return new TagPlan(from,to,affected,pinned,digest(repository.json(signature)));
    }
    private Map<String,Object> tagPreview(TagPlan p) {
        long journals=p.affected.stream().filter(r->r.get("collection").equals("journal")).count();
        List<Object> renamed=renamePinned(p.pinned,p.from,p.to);
        Map<String,Object> result=new LinkedHashMap<>();result.put("from",p.from);result.put("to",p.to);result.put("count",p.affected.size());
        result.put("journalCount",journals);result.put("taskCount",p.affected.size()-journals);result.put("pinnedChanged",!Objects.equals(p.pinned,renamed));result.put("previewToken",p.token);
        result.put("trashCount",p.affected.stream().filter(r->Boolean.TRUE.equals(r.get("deleted"))).count());
        result.put("message",p.from.equals(p.to)?"标签名称相同，无需修改":"更新使用此标签的日志、任务及固定筛选，包括回收站中的记录；正文和删除状态保持不变。");return result;
    }

    @Transactional(isolation=Isolation.SERIALIZABLE)
    public Map<String,Object> renameTag(Map<String,Object> request) {
        Object token=request.get("previewToken");if(!(token instanceof String s)||s.length()!=64)throw invalid("请先预览标签修改");
        TagPlan p=tagPlan(request);if(!p.token.equals(token))throw new ApiException(409,"标签使用情况已变化，请重新预览");
        for(var affected:p.affected){
            String collection=affected.get("collection").toString(),id=affected.get("id").toString();
            List<String> tags=renamedTags(asList(affected.get("tags")),p.from,p.to);
            Map<String,Object> before=data.require(collection,id);long version=DataService.version(affected.get("version"));
            if(DataService.version(before.get("version"))!=version)throw ApiException.conflict();
            Map<String,Object> after=new LinkedHashMap<>(before);after.put("tags",tags);after.put("version",version+1);after.put("updatedAt",DataValidator.now());
            if(!repository.update(collection,after,version))throw ApiException.conflict();
            repository.audit("RENAME_TAG",collection,id,before,after);
        }
        List<Object> pinned=renamePinned(p.pinned,p.from,p.to);
        if(!Objects.equals(p.pinned,pinned))data.updateSettings(Map.of("journalPinnedFilters",pinned));
        return Map.of("updated",p.affected.size(),"from",p.from,"to",p.to,"message",p.affected.isEmpty()?"没有需要修改的记录":"标签已更新，原始记录内容已保留");
    }

    static List<Object> renamePinned(List<Object> source,String from,String to) {
        if(from.equals(to))return source;
        List<Object> result=new ArrayList<>();
        for(Object entry:source){
            if(!(entry instanceof Map<?,?> map)||!(map.get("query") instanceof Map<?,?> query)){result.add(entry);continue;}
            Map<String,Object> next=new LinkedHashMap<>();map.forEach((k,v)->next.put(k.toString(),v));
            Map<String,Object> filter=new LinkedHashMap<>();query.forEach((k,v)->filter.put(k.toString(),v));
            if(filter.get("tags") instanceof List<?>)filter.put("tags",renamedTags(asList(filter.get("tags")),from,to));
            if(filter.get("q") instanceof String text){
                Matcher words=TERMS.matcher(text);StringBuilder changed=new StringBuilder();
                while(words.find()){
                    String word=words.group();String replacement=word;
                    if(word.startsWith("#")&&word.length()>1&&tag(words.group(1)==null?word:unquote(words.group(1))).equals(from))replacement=tagToken(to);
                    words.appendReplacement(changed,Matcher.quoteReplacement(replacement));
                }
                words.appendTail(changed);filter.put("q",changed.toString());
            }
            next.put("query",filter);result.add(next);
        }
        return result;
    }
    private static List<String> renamedTags(List<Object> values,String from,String to){
        LinkedHashSet<String> result=new LinkedHashSet<>();for(Object value:values){String normalized=tag(String.valueOf(value));result.add(normalized.equals(from)?to:normalized);}return new ArrayList<>(result);
    }
    private static List<Object> asList(Object raw){return raw instanceof List<?> values?new ArrayList<>(values):List.of();}
    static String tag(String value){List<String> tags=DataValidator.normalizeStrings(List.of(value),true);if(tags.isEmpty())throw invalid("请填写标签名称");return tags.get(0);}
    private static String requiredText(Map<String,Object> request,String key){if(!(request.get(key) instanceof String text))throw invalid("请填写标签名称");return text;}
    private static String unquote(String value){return value.replaceAll("\\\\(.)","$1");}
    private static String tagToken(String value){return value.matches(".*[\\s\"\\\\].*")?"#\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\"":"#"+value;}
    static String like(String value){return "%"+value.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";}
    private static String tagClause(){return "JSON_CONTAINS(COALESCE(r.tags,JSON_ARRAY()),JSON_QUOTE(?))";}
    private static int effectivePage(int requested,int size,long total){return (int)Math.min(requested,Math.max(1,(total+size-1)/size));}
    private static String value(MultiValueMap<String,String> p,String key,String fallback){String v=p.getFirst(key);return v==null||v.isBlank()?fallback:v.trim();}
    private static int integer(MultiValueMap<String,String> p,String key,int fallback,int min,int max){try{int v=Integer.parseInt(value(p,key,String.valueOf(fallback)));if(v<min||v>max)throw new NumberFormatException();return v;}catch(NumberFormatException e){throw invalid("分页参数不正确");}}
    private static boolean bool(MultiValueMap<String,String> p,String key,boolean fallback){String v=value(p,key,String.valueOf(fallback));if(!Set.of("true","false").contains(v))throw invalid("筛选开关不正确");return Boolean.parseBoolean(v);}
    private static LocalDate date(String value){if(value==null||value.isBlank())return null;try{return LocalDate.parse(value);}catch(Exception e){throw invalid("日期格式应为 YYYY-MM-DD");}}
    private static void bounded(String value,int max,String label){if(value.length()>max)throw invalid(label+"过长");}
    private static String digest(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("无法生成标签预览",e);}}
    private static IllegalArgumentException invalid(String message){return new IllegalArgumentException(message);}
}
