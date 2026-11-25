package cn.ziji.journal;

import cn.ziji.data.DataService;
import cn.ziji.data.DataRepository;
import cn.ziji.data.DataValidator;
import cn.ziji.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
public class QuickJournalController {
    private final DataService data;
    private final DataRepository repository;
    private final JdbcTemplate jdbc;

    public QuickJournalController(DataService data, DataRepository repository, JdbcTemplate jdbc) {
        this.data = data;
        this.repository = repository;
        this.jdbc = jdbc;
    }

    @GetMapping("/api/journal/quick-entry/expense-categories")
    public List<String> expenseCategories() {
        return jdbc.queryForList("""
                SELECT category COLLATE utf8mb4_bin AS name FROM zj_ledger
                WHERE deleted=FALSE AND direction='EXPENSE' AND (debtKind IS NULL OR debtKind='CREDIT')
                GROUP BY name ORDER BY MAX(date) DESC, name
                """, String.class);
    }

    public record Drafts(String requestId, Map<String,Object> journal, Map<String,Object> ledger) {}

    @PostMapping("/api/journal/quick-entry")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String,Object> save(@RequestBody Drafts drafts) {
        String token = DataValidator.uuid(drafts.requestId());
        if (drafts.journal() == null && drafts.ledger() == null)
            throw new IllegalArgumentException("请至少选择一项草稿");
        // Keep the selection immutable across retries, including concurrent retries of
        // journal-only and ledger-only requests. Reuse the local singleton row as a lock.
        repository.lockSettings();
        String journalId = idFor(token, "journal");
        String ledgerId = idFor(token, "ledger");
        boolean hasJournal = data.find("journal", journalId) != null;
        boolean hasLedger = data.find("ledger", ledgerId) != null;
        if ((hasJournal || hasLedger) && (hasJournal != (drafts.journal() != null) || hasLedger != (drafts.ledger() != null)))
            throw new ApiException(409, "这次补记已经保存，勾选项与上次不同。请关闭并核对记录。");
        Map<String,Object> result = new LinkedHashMap<>();
        if (drafts.journal() != null)
            result.put("journal", create("journal", journalId, drafts.journal()));
        if (drafts.ledger() != null) {
            if (!"EXPENSE".equals(drafts.ledger().get("direction")))
                throw new IllegalArgumentException("请选择本人实际支付的支出");
            Object debtKind = drafts.ledger().get("debtKind");
            if (debtKind != null && !"CREDIT".equals(debtKind))
                throw new IllegalArgumentException("还款或期初欠款请在账本中核对后保存");
            result.put("ledger", create("ledger", ledgerId, drafts.ledger()));
        }
        return result;
    }

    private static String idFor(String token, String collection) {
        return UUID.nameUUIDFromBytes(("quick-entry/" + token + "/" + collection).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private Map<String,Object> create(String collection, String id, Map<String,Object> draft) {
        if (!java.util.Collections.disjoint(Set.of("id", "version", "createdAt", "updatedAt", "deleted"), draft.keySet()))
            throw new IllegalArgumentException("补记草稿不能指定历史记录");
        Map<String,Object> fields = new LinkedHashMap<>(draft);
        fields.put("id", id);
        return data.create(collection, fields);
    }
}
