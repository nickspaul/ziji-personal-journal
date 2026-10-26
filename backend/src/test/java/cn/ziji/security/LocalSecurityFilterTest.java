package cn.ziji.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class LocalSecurityFilterTest {
    final LocalSecurityFilter filter=new LocalSecurityFilter("http://127.0.0.1:17890,http://localhost:5173",new ObjectMapper());
    MockHttpServletRequest request(String method,String path){var r=new MockHttpServletRequest(method,path);r.setServerName("127.0.0.1");r.setServerPort(17890);return r;}
    @Test void anonymousBusinessApiRejected()throws Exception{var r=request("GET","/api/bootstrap");var s=new MockHttpServletResponse();filter.doFilter(r,s,(a,b)->fail());assertEquals(401,s.getStatus());}
    @Test void rejectsCrossSiteEvenWithSession()throws Exception{var r=request("GET","/api/auth/status");r.addHeader("Origin","https://evil.example");var s=new MockHttpServletResponse();filter.doFilter(r,s,(a,b)->fail());assertEquals(403,s.getStatus());}
    @Test void writeRequiresCsrf()throws Exception{var r=request("POST","/api/data/smoking");r.getSession().setAttribute(LocalSecurityFilter.AUTH,true);r.setContentType("application/json");var s=new MockHttpServletResponse();filter.doFilter(r,s,(a,b)->fail());assertEquals(403,s.getStatus());}
    @Test void allowsAuthenticatedSameOriginJsonWrite()throws Exception{var r=request("POST","/api/data/smoking");r.getSession().setAttribute(LocalSecurityFilter.AUTH,true);r.addHeader("X-CSRF-Token",LocalSecurityFilter.token(r.getSession()));r.addHeader("Origin","http://127.0.0.1:17890");r.setContentType("application/json");var s=new MockHttpServletResponse();var called=new AtomicBoolean();filter.doFilter(r,s,(a,b)->called.set(true));assertTrue(called.get());}
    @Test void rejectsDnsRebindingHost()throws Exception{var r=request("GET","/api/auth/status");r.setServerName("evil.example");var s=new MockHttpServletResponse();filter.doFilter(r,s,(a,b)->fail());assertEquals(403,s.getStatus());}
}
