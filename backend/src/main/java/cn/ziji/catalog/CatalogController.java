package cn.ziji.catalog;

import cn.ziji.data.DataService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.*;

/** Product-only outbound requests. No personal state enters remote requests. */
@RestController
@RequestMapping("/api/catalog")
public class CatalogController {
    private static final String LIQUOR_URL="https://shop.jiuxian.com/show/wap/act/viewShopActivity.htm?actId=7474&viewType=2";
    private static final Pattern TOBACCO_PATH=Pattern.compile("/xiangyan/[a-z0-9]+\\.html");
    private static final String NOTE="第三方参考资料，导入前请核对具体包装、规格和实际购买价。";
    private final DataService data;
    private final ObjectMapper json;
    private final List<Map<String,Object>> cached=new ArrayList<>();
    private final Map<String,Instant> fetched=new ConcurrentHashMap<>();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private Instant lastRemote=Instant.EPOCH;

    public CatalogController(DataService data,ObjectMapper json) throws Exception {
        this.data=data;this.json=json;
        try(InputStream in=new ClassPathResource("catalog-seed.json").getInputStream()) {
            cached.addAll(json.readValue(in,new TypeReference<List<Map<String,Object>>>(){}));
        }
    }

    @GetMapping("/search")
    public synchronized Map<String,Object> search(@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="false") boolean refresh) {
        q=q.strip();if(q.length()>300)throw new IllegalArgumentException("检索内容过长");
        List<String> warnings=new ArrayList<>();
        if(q.startsWith("https://")) validateTobaccoUrl(q);
        if(refresh&&q.startsWith("https://")) {
            validateTobaccoUrl(q);
            if(Instant.now().isBefore(lastRemote.plusSeconds(3)))throw new IllegalArgumentException("请稍等几秒再查询商品资料");
            lastRemote=Instant.now();
            try { mergeCandidate(parseTobacco(fetch(q),q)); } catch(Exception ex) { warnings.add("商品来源暂时不可用，请稍后重试或手动添加。"); }
        } else if(refresh) {
            if(Instant.now().isBefore(lastRemote.plusSeconds(3)))throw new IllegalArgumentException("请稍等几秒再更新商品资料");
            lastRemote=Instant.now();
            var matches=filter(q);
            Set<String> urls=new LinkedHashSet<>();
            for(var candidate:matches) {
                var url=String.valueOf(candidate.get("sourceUrl"));
                if(urls.size()<4)urls.add(url);
            }
            for(String url:urls) {
                if(fetched.containsKey(url)&&fetched.get(url).isAfter(Instant.now().minusSeconds(300)))continue;
                try {
                    if(LIQUOR_URL.equals(url))parseLiquor(fetch(url)).forEach(this::mergeCandidate);
                    else { validateTobaccoUrl(url);mergeCandidate(parseTobacco(fetch(url),url)); }
                    fetched.put(url,Instant.now());
                } catch(Exception ex) { warnings.add("部分来源更新失败，保留原有带日期参考资料。"); }
            }
        }
        var candidates=filter(q);
        String message=candidates.isEmpty()?"当前目录未找到该型号。可手动添加，或粘贴烟酒之家具体香烟产品页链接读取规格。":"共 "+candidates.size()+" 条候选。参考价不代表本次成交价；烟盒标示值不代表人体吸收量。";
        return Map.of("candidates",candidates,"warnings",warnings.stream().distinct().toList(),"message",message);
    }

    @PostMapping("/import")
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String,Object> importCandidate(@RequestBody Map<String,Object> body) {
        if(!(body.get("candidate") instanceof Map<?,?>))throw new IllegalArgumentException("请选择要导入的商品");
        Map<String,Object> candidate=(Map<String,Object>)body.get("candidate");
        if(!(candidate.get("product") instanceof Map<?,?>))throw new IllegalArgumentException("候选商品格式不正确");
        var product=new LinkedHashMap<String,Object>((Map<String,Object>)candidate.get("product"));
        product.remove("id");product.remove("version");
        product.put("verified",Boolean.TRUE.equals(body.get("verified")));
        product.putIfAbsent("active",true);product.putIfAbsent("locked",false);
        String target=body.get("targetProductId")==null?null:String.valueOf(body.get("targetProductId"));
        Map<String,Object> saved;
        boolean duplicate=false;
        if(target!=null&&!target.isBlank()) {
            var existing=data.list("products",false).stream().filter(x->target.equals(x.get("id"))).findFirst().orElseThrow(()->new IllegalArgumentException("要更新的商品不存在"));
            if(Boolean.TRUE.equals(existing.get("locked")))throw new IllegalArgumentException("此商品已锁定，先在商品管理中解除锁定再更新");
            product.put("version",existing.get("version"));
            product.put("favorite",existing.getOrDefault("favorite",false));
            saved=data.update("products",target,product);
        } else {
            var match=data.list("products",false).stream().filter(x->Objects.equals(x.get("name"),product.get("name"))&&Objects.equals(x.get("kind"),product.get("kind"))&&Objects.equals(x.get("variant"),product.get("variant"))).findFirst();
            if(match.isPresent()){saved=match.get();duplicate=true;}else saved=data.create("products",product);
        }
        Map<String,Object> savedPrice=null;
        if(candidate.get("price") instanceof Map<?,?> priceRaw) {
            var price=new LinkedHashMap<String,Object>((Map<String,Object>)priceRaw);
            price.remove("id");price.remove("version");price.put("productId",saved.get("id"));price.put("priceType","REFERENCE");price.put("verified",false);
            boolean exists=data.list("prices",false).stream().anyMatch(x->Objects.equals(x.get("productId"),saved.get("id"))&&Objects.equals(x.get("effectiveDate"),price.get("effectiveDate"))&&sameNumber(x.get("amount"),price.get("amount"))&&Objects.equals(x.get("channel"),price.get("channel")));
            if(!exists&&price.get("amount")!=null)savedPrice=data.create("prices",price);
        }
        var result=new LinkedHashMap<String,Object>();result.put("product",saved);result.put("price",savedPrice);result.put("duplicate",duplicate);result.put("message",duplicate?"商品已存在，保留现有资料并检查参考价历史。":"商品资料已导入，可设置为常用并填写实际购买价。");return result;
    }

    private boolean sameNumber(Object a,Object b){try{return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()))==0;}catch(Exception e){return false;}}
    private List<Map<String,Object>> filter(String q) {
        String needle=q.toLowerCase(Locale.ROOT).replace("（","(").replace("）",")");
        return cached.stream().filter(x->{if(needle.isBlank())return true;String hay=(x.get("product")+" "+x.get("sourceUrl")).toLowerCase(Locale.ROOT).replace("（","(").replace("）",")");return hay.contains(needle);}).limit(50).map(x->json.convertValue(x,new TypeReference<Map<String,Object>>(){})).toList();
    }
    private void mergeCandidate(Map<String,Object> candidate){cached.removeIf(x->Objects.equals(x.get("id"),candidate.get("id")));cached.add(candidate);}
    private void validateTobaccoUrl(String url) {
        URI uri;try{uri=URI.create(url);}catch(Exception e){throw new IllegalArgumentException("商品链接无效");}
        if(!"https".equals(uri.getScheme())||!"www.yanjiuhome.com".equals(uri.getHost())||uri.getUserInfo()!=null||uri.getPort()!=-1||uri.getQuery()!=null||uri.getFragment()!=null||!TOBACCO_PATH.matcher(uri.getPath()).matches())throw new IllegalArgumentException("目前支持 https://www.yanjiuhome.com/xiangyan/ 下的具体产品页；其他来源请手动添加。");
    }
    private String fetch(String url)throws Exception {
        var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).header("User-Agent","ZijiLocal/0.2 (personal product reference)").GET().build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
        try(var in=response.body()){
            if(response.statusCode()!=200)throw new IllegalStateException("Source unavailable");
            byte[] bytes=in.readNBytes(2_000_001);if(bytes.length>2_000_000)throw new IllegalStateException("Source too large");
            return new String(bytes,StandardCharsets.UTF_8);
        }
    }
    private String clean(String s){return s.replaceAll("(?is)<script\\b[^>]*>.*?</script>|<style\\b[^>]*>.*?</style>"," ").replaceAll("<[^>]+>"," ").replace("&nbsp;"," ").replace("&amp;","&").replaceAll("\\s+"," ").strip();}
    private String capture(String s,String expression){var m=Pattern.compile(expression,Pattern.CASE_INSENSITIVE|Pattern.DOTALL).matcher(s);return m.find()?m.group(1):null;}
    private BigDecimal num(String s,String expression){String v=capture(s,expression);return v==null?null:new BigDecimal(v);}
    private String date(){return LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();}
    private Map<String,Object> price(BigDecimal amount,String source,String channel){var p=new LinkedHashMap<String,Object>();p.put("amount",amount);p.put("priceType","REFERENCE");p.put("effectiveDate",date());p.put("currency","CNY");p.put("channel",channel);p.put("sourceUrl",source);p.put("verified",false);return p;}
    private Map<String,Object> baseProduct(String name,String brand,String kind,String source){var p=new LinkedHashMap<String,Object>();p.put("kind",kind);p.put("name",name);p.put("brand",brand);p.put("sourceUrl",source);p.put("sourceDate",date());p.put("verified",false);p.put("favorite",false);p.put("locked",false);p.put("active",true);p.put("notes",NOTE);return p;}
    private Map<String,Object> parseTobacco(String html,String url) {
        String title=capture(html,"<title>(.*?)</title>");if(title==null)throw new IllegalArgumentException("来源页面没有商品名称");
        String name=clean(title).split("\\|")[0].strip();String text=clean(html);
        if(!text.contains("产品规格"))throw new IllegalArgumentException("没有找到产品规格，保留原资料");
        String brand=name.split("[（(]")[0];var p=baseProduct(name,brand,"SMOKING",url);p.put("variant",name);
        p.put("unitCount",num(text,"单盒\\(包\\)支数\\s*(\\d+)"));p.put("tarMg",num(text,"焦油量\\s*([\\d.]+)\\s*mg"));p.put("nicotineMg",num(text,"烟气烟碱量\\s*([\\d.]+)\\s*mg"));p.put("coMg",num(text,"一氧化碳量\\s*([\\d.]+)\\s*mg"));p.put("constituentMethod","第三方产品页转录的包装标示，具体版本待核对");
        String id=URI.create(url).getPath().replace("/xiangyan/","").replace(".html","");var c=new LinkedHashMap<String,Object>();c.put("id","yanjiu-"+id);c.put("product",p);
        BigDecimal amount=num(text,"参考价格\\s*[¥￥]\\s*([\\d.]+)");c.put("price",amount==null?null:price(amount,url,"烟酒之家公开参考价"));c.put("sourceLabel","烟酒之家 · 第三方资料");c.put("sourceUrl",url);c.put("note",NOTE);return c;
    }
    private List<Map<String,Object>> parseLiquor(String html){
        var result=new ArrayList<Map<String,Object>>();var items=Pattern.compile("<li\\b[^>]*proid=\"(\\d+)\"[^>]*>(.*?)</li>",Pattern.CASE_INSENSITIVE|Pattern.DOTALL).matcher(html);
        while(items.find()){
            String body=items.group(2),raw=capture(body,"<div\\s+class=\"name\"[^>]*>(.*?)</div>");if(raw==null)continue;
            String name=clean(raw);if(Pattern.compile("整箱|\\*\\s*\\d|×\\s*\\d|套装").matcher(name).find())continue;
            String priceText=capture(body,"<div\\s+class=\"price\"[^>]*>(.*?)</div>");if(priceText==null)continue;
            BigDecimal amount=num(clean(priceText),"[¥￥]\\s*([\\d.]+)"),ml=num(name,"(\\d+(?:\\.\\d+)?)\\s*ml"),abv=num(name,"(\\d+(?:\\.\\d+)?)\\s*[°度]");if(amount==null||ml==null||abv==null)continue;
            String brand=name.contains("贵州大曲")?"贵州大曲":name.contains("茅台王子")?"茅台王子":name.contains("茅台迎宾")?"茅台迎宾":"茅台";
            var p=baseProduct(name,brand,"ALCOHOL",LIQUOR_URL);p.put("bottleMl",ml);p.put("abv",abv);p.put("variant",abv.stripTrailingZeros().toPlainString()+"%vol · "+ml.stripTrailingZeros().toPlainString()+"ml");
            var c=new LinkedHashMap<String,Object>();c.put("id","jiuxian-"+items.group(1));c.put("product",p);c.put("price",price(amount,LIQUOR_URL,"酒仙公开页面展示价"));c.put("sourceLabel","酒仙 · 页面展示价");c.put("sourceUrl",LIQUOR_URL);c.put("note",NOTE);result.add(c);
        }return result;
    }
}
