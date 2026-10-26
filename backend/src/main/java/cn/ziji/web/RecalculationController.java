package cn.ziji.web;

import cn.ziji.data.RecalculationService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/recalculate")
public class RecalculationController {
    private final RecalculationService service;
    public RecalculationController(RecalculationService service){this.service=service;}
    @PostMapping("/preview")public Map<String,Object> preview(@RequestBody Map<String,Object> input){return service.preview(input);}
    @PostMapping public Map<String,Object> apply(@RequestBody Map<String,Object> input){return service.apply(input);}
}
