package cn.ziji.backup;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api")
public class BackupController {
    private final BackupService backup;
    public BackupController(BackupService backup){this.backup=backup;}
    @GetMapping("/backup") public ResponseEntity<Map<String,Object>> export(){return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=ziji-backup.json").contentType(MediaType.APPLICATION_JSON).body(backup.export());}
    @PostMapping("/backup/preview") public Map<String,Object> preview(@RequestBody Map<String,Object> body){return backup.preview(body);}
    @SuppressWarnings("unchecked")
    @PostMapping("/backup/restore") public Map<String,Object> restore(@RequestBody Map<String,Object> body){if(!(body.get("backup") instanceof Map<?,?>))throw new IllegalArgumentException("缺少备份内容");return backup.restore((Map<String,Object>)body.get("backup"),String.valueOf(body.get("mode")));}
    @GetMapping("/backups") public List<Map<String,Object>> list(){return backup.list();}
}
