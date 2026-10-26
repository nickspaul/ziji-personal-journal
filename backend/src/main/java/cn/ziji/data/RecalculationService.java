package cn.ziji.data;

import cn.ziji.domain.BehaviorCalculator;
import cn.ziji.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class RecalculationService {
    private final DataService data;private final DataRepository repository;private final DataValidator validator;
    public RecalculationService(DataService data,DataRepository repository,DataValidator validator){this.data=data;this.repository=repository;this.validator=validator;}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> preview(Map<String,Object> request){return plan(request);}
    private Map<String,Object> plan(Map<String,Object> request) {
        String sourceCollection=String.valueOf(request.get("sourceCollection"));
        if(!Set.of("products","prices","purchases").contains(sourceCollection))throw new IllegalArgumentException("请选择商品、价格或购买记录作为重算来源");
        String sourceId=DataValidator.uuid(request.get("sourceId"));Map<String,Object> source=data.require(sourceCollection,sourceId);
        String productId=sourceCollection.equals("products")?sourceId:String.valueOf(source.get("productId"));
        Map<String,List<Map<String,Object>>> state=data.state();List<Map<String,Object>> rows=new ArrayList<>();List<String> errors=new ArrayList<>();
        for(String collection:List.of("smoking","drinks"))for(Map<String,Object> previous:state.get(collection)) {
            boolean affected=sourceCollection.equals("purchases")?sourceId.equals(previous.get("purchaseId")):productId.equals(previous.get("productId"));
            if(!affected)continue;if(rows.size()>10000)throw new IllegalArgumentException("一次重算最多一万条，请缩小影响范围");
            try {
                Map<String,Object> raw=validator.normalize(collection,previous,null,true);
                Map<String,Object> after=new LinkedHashMap<>(BehaviorCalculator.enrich(collection,raw,null,state));
                after.put("id",previous.get("id"));after.put("version",previous.get("version"));after.put("createdAt",previous.get("createdAt"));after.put("updatedAt",previous.get("updatedAt"));after.put("deleted",false);
                if(sameValue(previous,after))continue;
                rows.add(Map.of("collection",collection,"id",previous.get("id"),"version",previous.get("version"),"before",previous,"after",after));
            }catch(IllegalArgumentException e){errors.add(collection+" "+previous.get("id")+"："+e.getMessage());}
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("sourceCollection",sourceCollection);result.put("sourceId",sourceId);result.put("sourceVersion",source.get("version"));result.put("count",rows.size());result.put("rows",rows);result.put("errors",errors);result.put("valid",errors.isEmpty());
        try {byte[] digest=MessageDigest.getInstance("SHA-256").digest(repository.json(result).getBytes(StandardCharsets.UTF_8));result.put("previewToken",HexFormat.of().formatHex(digest));}catch(Exception e){throw new IllegalStateException("无法生成重算预览");}
        result.put("message","只刷新这些记录的名称、规格、成分与费用快照。原始根数、本人饮用量、手填价格和购买批次保持不变。身体参数与酒后关联按查询自动重算。");return result;
    }
    // JDBC decimals and JSON numbers can differ in type or scale without changing the value.
    // Missing optional values and explicit nulls likewise do not warrant a new history entry.
    private static boolean sameValue(Object before,Object after) {
        if(before==null||after==null)return before==after;
        if(before instanceof Number&&after instanceof Number)
            return new BigDecimal(before.toString()).compareTo(new BigDecimal(after.toString()))==0;
        if(before instanceof Map<?,?> left&&after instanceof Map<?,?> right) {
            Set<Object> keys=new HashSet<>(left.keySet());keys.addAll(right.keySet());
            return keys.stream().allMatch(key->sameValue(left.get(key),right.get(key)));
        }
        if(before instanceof List<?> left&&after instanceof List<?> right) {
            if(left.size()!=right.size())return false;
            for(int i=0;i<left.size();i++)if(!sameValue(left.get(i),right.get(i)))return false;
            return true;
        }
        return before.equals(after);
    }
    @SuppressWarnings("unchecked")
    @Transactional(isolation=Isolation.SERIALIZABLE)
    public Map<String,Object> apply(Map<String,Object> request) {
        if(!(request.get("previewToken") instanceof String token)||token.length()!=64)throw new IllegalArgumentException("请先预览重算影响");
        Map<String,Object> plan=plan(request);if(!Boolean.TRUE.equals(plan.get("valid")))throw new IllegalArgumentException("重算预览有缺失资料，请先修正："+plan.get("errors"));
        if(!token.equals(plan.get("previewToken")))throw new ApiException(409,"数据在预览后发生了变化，请重新预览再确认");
        List<Map<String,Object>> rows=(List<Map<String,Object>>)plan.get("rows");
        for(var row:rows) {
            String collection=row.get("collection").toString();Map<String,Object> before=(Map<String,Object>)row.get("before");Map<String,Object> next=new LinkedHashMap<>((Map<String,Object>)row.get("after"));long version=DataService.version(row.get("version"));
            next.put("version",version+1);next.put("updatedAt",DataValidator.now());if(!repository.update(collection,next,version))throw ApiException.conflict();repository.audit("RECALCULATE",collection,next.get("id").toString(),before,next);
        }
        return Map.of("updated",rows.size(),"message","历史快照已按预览更新，原始记录与手填覆盖值已保留");
    }
}
