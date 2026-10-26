package cn.ziji.security;

import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth){this.auth=auth;}
    @GetMapping("/status") public Map<String,Object> status(HttpServletRequest request) {
        HttpSession session=request.getSession(true);return Map.of("configured",auth.configured(),"authenticated",Boolean.TRUE.equals(session.getAttribute(LocalSecurityFilter.AUTH)),"csrfToken",LocalSecurityFilter.token(session));
    }
    @PostMapping("/setup") public Map<String,Object> setup(@RequestBody Map<String,Object> body,HttpServletRequest request){auth.setup(password(body));return unlock(request);}
    @PostMapping("/login") public Map<String,Object> login(@RequestBody Map<String,Object> body,HttpServletRequest request){auth.login(password(body));return unlock(request);}
    private String password(Map<String,Object> body){Object value=body.get("password");if(!(value instanceof String))throw new IllegalArgumentException("请输入口令");return value.toString();}
    private Map<String,Object> unlock(HttpServletRequest request){request.changeSessionId();request.getSession().setAttribute(LocalSecurityFilter.AUTH,true);return status(request);}
    @PostMapping("/logout") public Map<String,Object> logout(HttpServletRequest request){request.getSession().invalidate();return status(request);}
}
