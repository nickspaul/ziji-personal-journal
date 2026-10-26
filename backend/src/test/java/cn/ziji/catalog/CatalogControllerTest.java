package cn.ziji.catalog;

import cn.ziji.data.DataService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CatalogControllerTest {
    CatalogController controller() throws Exception { return new CatalogController(mock(DataService.class),new ObjectMapper()); }
    @Test void offlineCatalogContainsSourcedCandidatesWithoutPersonalRecords() throws Exception {
        var result=controller().search("",false);
        var candidates=(List<?>)result.get("candidates");
        assertTrue(candidates.size()>=18);
        for(Object row:candidates) {
            var c=(Map<?,?>)row;var product=(Map<?,?>)c.get("product");
            assertEquals(false,product.get("verified"));assertNotNull(c.get("sourceUrl"));
            assertFalse(product.containsKey("id"));
        }
    }
    @Test void externalUrlsCannotReachArbitraryHosts() throws Exception {
        var c=controller();
        for(String url:List.of("https://localhost/xiangyan/a.html","https://www.yanjiuhome.com@localhost/xiangyan/a.html","https://www.yanjiuhome.com:443/xiangyan/a.html","https://www.yanjiuhome.com/xiangyan/a.html?x=1"))
            assertThrows(IllegalArgumentException.class,()->c.search(url,false));
        // A known-format URL remains an offline lookup until refresh is explicitly requested.
        assertNotNull(c.search("https://www.yanjiuhome.com/xiangyan/testmissing.html",false));
    }
    @Test void tobaccoParserOnlyCopiesFactsAndKeepsUnknownValuesNull() throws Exception {
        String html="<title>例牌(硬) | 商品</title><script>焦油量 999 mg</script><p>产品规格 焦油量 8.00 mg 烟气烟碱量 0.8 mg 单盒(包)支数 20 参考价格 ￥11.00 / 包</p><p>无依据的广告文案</p>";
        Map<?,?> candidate=ReflectionTestUtils.invokeMethod(controller(),"parseTobacco",html,"https://www.yanjiuhome.com/xiangyan/test.html");
        var product=(Map<?,?>)candidate.get("product");
        assertEquals(new BigDecimal("8.00"),product.get("tarMg"));assertNull(product.get("coMg"));
        assertFalse(product.toString().contains("广告文案"));
    }
    @Test void liquorParserExcludesMultipacksAndDoesNotGuessMissingAbv() throws Exception {
        String html="<li proid=\"1\"><div class=\"name\">53°茅台测试500ml</div><div class=\"price\">¥120</div></li>"
            +"<li proid=\"2\"><div class=\"name\">53°茅台500ml*6整箱</div><div class=\"price\">¥600</div></li>"
            +"<li proid=\"3\"><div class=\"name\">茅台500ml</div><div class=\"price\">¥120</div></li>";
        List<?> candidates=ReflectionTestUtils.invokeMethod(controller(),"parseLiquor",html);
        assertEquals(1,candidates.size());
        var product=(Map<?,?>)((Map<?,?>)candidates.get(0)).get("product");
        assertEquals(new BigDecimal("53"),product.get("abv"));assertEquals(new BigDecimal("500"),product.get("bottleMl"));
    }
}
