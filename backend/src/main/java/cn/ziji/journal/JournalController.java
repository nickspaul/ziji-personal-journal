package cn.ziji.journal;

import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/journal")
public class JournalController {
    private final JournalQueryService service;
    private final TaskEffortService effort;
    public JournalController(JournalQueryService service,TaskEffortService effort){this.service=service;this.effort=effort;}
    @GetMapping("/query")public Map<String,Object> query(@RequestParam MultiValueMap<String,String> parameters){return service.query(parameters);}
    @GetMapping("/facets")public Map<String,Object> facets(@RequestParam(required=false) String q){return service.facets(q);}
    @GetMapping("/tasks/{id}/effort")public Map<String,Object> effort(@PathVariable String id){return effort.forTask(id);}
    @PostMapping("/tags/preview")public Map<String,Object> preview(@RequestBody Map<String,Object> request){return service.previewTag(request);}
    @PostMapping("/tags/rename")public Map<String,Object> rename(@RequestBody Map<String,Object> request){return service.renameTag(request);}
}
