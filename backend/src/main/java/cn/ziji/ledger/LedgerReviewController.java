package cn.ziji.ledger;

import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class LedgerReviewController {
    private final LedgerReviewService service;
    public LedgerReviewController(LedgerReviewService service) { this.service=service; }

    public record Selection(String id,long version,String reimbursement) {}
    public record TripEdit(long version,List<Selection> changes) {}
    public record Settlement(long version,LocalDate dueDate,LocalDate paidDate,boolean skip) {}

    @GetMapping("/trips/options")
    public List<Map<String,Object>> options(@RequestParam LocalDate date,@RequestParam(required=false) String selected) {
        return service.options(date,selected);
    }

    @GetMapping("/trips/{id}")
    public Map<String,Object> trip(@PathVariable String id) { return service.trip(id); }

    @PutMapping("/trips/{id}/expenses")
    public Map<String,Object> editTrip(@PathVariable String id,@RequestBody TripEdit input) { return service.editTrip(id,input); }

    @PostMapping("/bills/{id}/settle")
    public Map<String,Object> settle(@PathVariable String id,@RequestBody Settlement input) { return service.settle(id,input); }
}
