package cn.ziji.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalSecurityFilter extends OncePerRequestFilter {
    public static final String CSRF="ziji.csrf";
    public static final String AUTH="ziji.authenticated";
    private final Set<String> origins;
    private final ObjectMapper json;
    public LocalSecurityFilter(@Value("${ziji.allowed-origins}") String allowed,ObjectMapper json){this.origins=new HashSet<>(Arrays.asList(allowed.split(",")));this.json=json;}
    public static String token(HttpSession session) {
        Object value=session.getAttribute(CSRF);if(value==null){value=UUID.randomUUID().toString()+UUID.randomUUID();session.setAttribute(CSRF,value);}return value.toString();
    }
    private void reject(HttpServletResponse response,int status,String message)throws IOException{
        response.setStatus(status);response.setContentType("application/json;charset=UTF-8");json.writeValue(response.getOutputStream(),Map.of("message",message));
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        response.setHeader("X-Content-Type-Options","nosniff");response.setHeader("X-Frame-Options","DENY");response.setHeader("Referrer-Policy","no-referrer");
        String host=request.getServerName();
        if(!Set.of("127.0.0.1","localhost","::1","[::1]").contains(host)){reject(response,403,"仅允许本机地址访问");return;}
        String path=request.getRequestURI();if(!path.startsWith("/api/")){chain.doFilter(request,response);return;}
        response.setHeader("Cache-Control","no-store");
        String origin=request.getHeader("Origin");String fetchSite=request.getHeader("Sec-Fetch-Site");
        String ownOrigin=request.getScheme()+"://"+host+(request.getServerPort()==80?"":":"+request.getServerPort());
        if("cross-site".equals(fetchSite)||(origin!=null&&!origins.contains(origin)&&!origin.equals(ownOrigin))){reject(response,403,"请求来源不受信任");return;}
        boolean write=!Set.of("GET","HEAD","OPTIONS").contains(request.getMethod());
        HttpSession session=request.getSession(false);
        if(write) {
            String provided=request.getHeader("X-CSRF-Token");Object expected=session==null?null:session.getAttribute(CSRF);
            if(provided==null||expected==null||!MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8),expected.toString().getBytes(StandardCharsets.UTF_8))){reject(response,403,"会话校验已过期，请刷新页面后重试");return;}
            String content=request.getContentType();
            if(!request.getMethod().equals("DELETE")&&(content==null||!content.toLowerCase(Locale.ROOT).startsWith("application/json"))){reject(response,400,"请使用 JSON 提交数据");return;}
        }
        boolean publicAuth=Set.of("/api/auth/status","/api/auth/login","/api/auth/setup").contains(path);
        if(!publicAuth&&(session==null||!Boolean.TRUE.equals(session.getAttribute(AUTH)))){reject(response,401,"请先解锁本机应用");return;}
        chain.doFilter(request,response);
    }
}
