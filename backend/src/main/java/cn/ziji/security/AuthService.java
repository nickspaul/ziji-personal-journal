package cn.ziji.security;

import cn.ziji.data.DataRepository;
import cn.ziji.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
public class AuthService {
    private final JdbcTemplate jdbc;
    private final DataRepository repository;
    private final BCryptPasswordEncoder passwordEncoder=new BCryptPasswordEncoder(12);
    private int failedAttempts=0;
    private long lockedUntil=0;
    public AuthService(JdbcTemplate jdbc,DataRepository repository){this.jdbc=jdbc;this.repository=repository;}
    public boolean configured(){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT COUNT(*)>0 FROM zj_auth WHERE id=1",Boolean.class));}
    private void validate(String password){if(password==null||password.length()<8||password.getBytes(StandardCharsets.UTF_8).length>72)throw new IllegalArgumentException("口令至少 8 个字符，UTF-8 长度不超过 72 字节");}
    @Transactional
    public synchronized void setup(String password) {
        validate(password);if(configured())throw new ApiException(409,"本机口令已经设置，请直接解锁");
        jdbc.update("INSERT INTO zj_auth(id,passwordHash,createdAt) VALUES (1,?,NOW(6))",passwordEncoder.encode(password));
        repository.audit("AUTH_SETUP","auth","1",null,null);
    }
    public synchronized void login(String password) {
        if(System.currentTimeMillis()<lockedUntil)throw new ApiException(429,"尝试较多，请稍后再试");
        List<String> hashes=jdbc.queryForList("SELECT passwordHash FROM zj_auth WHERE id=1",String.class);
        if(hashes.isEmpty())throw new ApiException(409,"请先设置本机口令");
        if(password==null||password.getBytes(StandardCharsets.UTF_8).length>72||!passwordEncoder.matches(password,hashes.get(0))) {
            failedAttempts++;if(failedAttempts>=5){lockedUntil=System.currentTimeMillis()+30000;failedAttempts=0;}
            throw new ApiException(401,"口令不正确");
        }
        failedAttempts=0;lockedUntil=0;
    }
}
