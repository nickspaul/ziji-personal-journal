package cn.ziji.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log=LoggerFactory.getLogger(ApiExceptionHandler.class);
    @ExceptionHandler(ApiException.class) ResponseEntity<?> api(ApiException e){return ResponseEntity.status(e.status()).body(Map.of("message",e.getMessage()));}
    @ExceptionHandler({IllegalArgumentException.class,MethodArgumentTypeMismatchException.class}) ResponseEntity<?> bad(RuntimeException e){return ResponseEntity.badRequest().body(Map.of("message",e.getMessage()==null?"输入格式不正确":e.getMessage()));}
    @ExceptionHandler(HttpMessageNotReadableException.class) ResponseEntity<?> body(){return ResponseEntity.badRequest().body(Map.of("message","请求内容不是有效 JSON"));}
    @ExceptionHandler(DataIntegrityViolationException.class) ResponseEntity<?> constraint(){return ResponseEntity.status(409).body(Map.of("message","数据存在冲突，或关联记录已变更，请刷新后重试"));}
    @ExceptionHandler(Exception.class) ResponseEntity<?> unknown(Exception e){log.error("Request failed: {}",e.getClass().getSimpleName());return ResponseEntity.internalServerError().body(Map.of("message","操作未完成，请检查本机服务状态后重试"));}
}
