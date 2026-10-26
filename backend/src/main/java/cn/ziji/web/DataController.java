package cn.ziji.web;

import cn.ziji.data.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api")
public class DataController {
    private final DataService data;
    private final DataRepository repository;
    public DataController(DataService data,DataRepository repository){this.data=data;this.repository=repository;}
    @GetMapping("/bootstrap") public Map<String,Object> bootstrap(){Map<String,Object> result=new LinkedHashMap<>();result.put("collections",data.state());result.put("settings",data.settings());result.put("schema",data.schema());result.put("health",data.list("health",false));return result;}
    @GetMapping("/data/{collection}") public List<Map<String,Object>> list(@PathVariable String collection,@RequestParam(defaultValue="false") boolean deleted){return data.list(collection,deleted);}
    @GetMapping("/data/{collection}/{id}") public Map<String,Object> detail(@PathVariable String collection,@PathVariable String id){return data.require(collection,id);}
    @PostMapping("/data/{collection}") public Map<String,Object> create(@PathVariable String collection,@RequestBody Map<String,Object> input){return data.create(collection,input);}
    @PutMapping("/data/{collection}/{id}") public Map<String,Object> update(@PathVariable String collection,@PathVariable String id,@RequestBody Map<String,Object> input){return data.update(collection,id,input);}
    @DeleteMapping("/data/{collection}/{id}") public Map<String,Object> delete(@PathVariable String collection,@PathVariable String id,@RequestParam long version){return data.delete(collection,id,version);}
    @PostMapping("/data/{collection}/{id}/restore") public Map<String,Object> restore(@PathVariable String collection,@PathVariable String id,@RequestBody Map<String,Object> input){return data.restore(collection,id,DataService.version(input.get("version")));}
    @GetMapping("/settings") public Map<String,Object> settings(){return data.settings();}
    @PutMapping("/settings") public Map<String,Object> settings(@RequestBody Map<String,Object> input){return data.updateSettings(input);}
    @GetMapping("/analytics") public Map<String,Object> analytics(@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to){return data.analytics(from,to);}
    @GetMapping("/analytics/summary") public Map<String,Object> lifetimeSummary(){return data.lifetimeSummary();}
    @GetMapping("/audit") public List<Map<String,Object>> audit(@RequestParam(defaultValue="500") int limit){return repository.auditList(limit);}
}
