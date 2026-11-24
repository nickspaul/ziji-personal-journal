package cn.ziji.journal;

import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/journal")
public class JournalController {
    private final JournalQueryService service;
    private final TaskEffortService effort;
    private final JournalTermService terms;
    public JournalController(JournalQueryService service,TaskEffortService effort,JournalTermService terms){this.service=service;this.effort=effort;this.terms=terms;}
    @GetMapping("/query")public Map<String,Object> query(@RequestParam MultiValueMap<String,String> parameters){return service.query(parameters);}
    @GetMapping("/facets")public Map<String,Object> facets(@RequestParam(required=false) String q){return service.facets(q);}
    @GetMapping("/tasks/{id}/effort")public Map<String,Object> effort(@PathVariable String id){return effort.forTask(id);}
    @PostMapping("/tags/preview")public Map<String,Object> preview(@RequestBody Map<String,Object> request){return service.previewTag(request);}
    @PostMapping("/tags/rename")public Map<String,Object> rename(@RequestBody Map<String,Object> request){return service.renameTag(request);}
    @PostMapping("/terms/add")public Map<String,Object> addTerm(@RequestBody Map<String,Object> request){return terms.add(request);}
    @PostMapping("/terms/preview")public Map<String,Object> previewTerm(@RequestBody Map<String,Object> request){return terms.preview(request);}
    @PostMapping("/terms/apply")public Map<String,Object> applyTerm(@RequestBody Map<String,Object> request){return terms.apply(request);}
}
