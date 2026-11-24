package cn.ziji.journal;

import cn.ziji.data.DataRepository;
import cn.ziji.data.DataService;
import cn.ziji.data.DataValidator;
import cn.ziji.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class JournalTermService {
    private final JdbcTemplate jdbc;
    private final DataRepository repository;
    private final DataService data;

    public JournalTermService(JdbcTemplate jdbc, DataRepository repository, DataService data) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.data = data;
    }

    private enum Kind {
        TAG("tag", "tags", List.of("journal", "tasks")),
        PERSON("person", "people", List.of("journal"));

        final String key;
        final String field;
        final List<String> collections;

        Kind(String key, String field, List<String> collections) {
            this.key = key;
            this.field = field;
            this.collections = collections;
        }

        static Kind read(Map<String,Object> request) {
            for (var kind : values()) if (kind.key.equals(request.get("kind"))) return kind;
            throw new IllegalArgumentException("请选择标签或同行人");
        }

        String name(Object value) {
            if (value instanceof String text) {
                var normalized = DataValidator.normalizeStrings(List.of(text), this == TAG);
                if (!normalized.isEmpty()) return normalized.get(0);
            }
            throw new IllegalArgumentException(this == TAG ? "请填写标签名称" : "请填写同行人姓名");
        }
    }

    private record Choices(LinkedHashSet<String> added, LinkedHashSet<String> hidden) {
        static Choices read(Object raw, Kind kind) {
            if (raw instanceof Map<?,?> vocabulary && vocabulary.get(kind.key) instanceof Map<?,?> entry)
                return new Choices(names(entry.get("added")), names(entry.get("hidden")));
            return new Choices(new LinkedHashSet<>(), new LinkedHashSet<>());
        }

        private static LinkedHashSet<String> names(Object raw) {
            var result = new LinkedHashSet<String>();
            if (raw instanceof List<?> list)
                for (var value : list) if (value instanceof String text) result.add(text);
            return result;
        }

        void enable(String name) {
            hidden.remove(name);
            added.add(name);
        }

        Map<String,Object> value() {
            return Map.of("added", List.copyOf(added), "hidden", List.copyOf(hidden));
        }
    }

    static List<Map<String,Object>> visibleTags(List<Map<String,Object>> used, Object vocabulary) {
        var choices = Choices.read(vocabulary, Kind.TAG);
        var tags = new LinkedHashMap<String,Map<String,Object>>();
        for (var tag : used) tags.put((String) tag.get("name"), tag);
        for (String name : choices.added) tags.putIfAbsent(name, Map.of("name", name, "count", 0));
        choices.hidden.forEach(tags::remove);
        return List.copyOf(tags.values());
    }

    static List<String> visiblePeople(List<String> used, Object vocabulary) {
        var choices = Choices.read(vocabulary, Kind.PERSON);
        var people = new LinkedHashSet<>(used);
        people.addAll(choices.added);
        people.removeAll(choices.hidden);
        return List.copyOf(people);
    }

    private static Map<String,Object> vocabularyWith(Object raw, Kind kind, Choices choices) {
        var vocabulary = new LinkedHashMap<String,Object>();
        for (Kind entry : Kind.values())
            vocabulary.put(entry.key, entry == kind ? choices.value() : Choices.read(raw, entry).value());
        return vocabulary;
    }

    @Transactional
    public Map<String,Object> add(Map<String,Object> request) {
        Kind kind = Kind.read(request);
        String name = kind.name(request.get("name"));
        repository.lockSettings();
        Object vocabulary = data.settings().get("journalVocabulary");
        var choices = Choices.read(vocabulary, kind);
        choices.enable(name);
        data.updateSettings(Map.of("journalVocabulary", vocabularyWith(vocabulary, kind, choices)));
        return Map.of("kind", kind.key, "name", name);
    }

    private record RowId(String collection, String id) {}
    private record Match(RowId key, Map<String,Object> row) {
        Map<String,Object> preview() {
            String date = "";
            for (String field : List.of("startDate", "plannedDate", "dueDate", "createdAt")) {
                if (row.get(field) == null) continue;
                date = row.get(field).toString();
                if (date.length() > 10) date = date.substring(0, 10);
                break;
            }
            return Map.of("id", key.id, "collection", key.collection, "title", Objects.toString(row.get("title"), ""),
                    "date", date, "deleted", Boolean.TRUE.equals(row.get("deleted")), "version", row.get("version"));
        }
    }
    private record Plan(Kind kind, String from, String to, List<Match> matches, List<Map<String,Object>> rows,
                        Object vocabulary, Choices choices, List<Object> pins, List<Object> renamedPins, String token) {
        boolean available() {
            return !choices.hidden.contains(from) && (choices.added.contains(from) || !matches.isEmpty());
        }
    }

    private Plan plan(Map<String,Object> request) {
        Kind kind = Kind.read(request);
        String from = kind.name(request.get("from"));
        String to = request.get("to") == null ? null : kind.name(request.get("to"));
        if (from.equals(to)) throw new IllegalArgumentException("新名称与原名称相同");

        var matches = new ArrayList<Match>();
        for (String collection : kind.collections) {
            var rows = jdbc.query("SELECT * FROM zj_" + collection + " WHERE JSON_CONTAINS(COALESCE("
                    + kind.field + ",JSON_ARRAY()),JSON_QUOTE(?)) ORDER BY id",
                    (rs,n) -> repository.mapRow(collection, rs), from);
            for (var row : rows) matches.add(new Match(new RowId(collection, row.get("id").toString()), row));
        }
        var rows = matches.stream().map(Match::preview).toList();
        var settings = data.settings();
        Object vocabulary = settings.get("journalVocabulary");
        var choices = Choices.read(vocabulary, kind);
        List<Object> pins = settings.get("journalPinnedFilters") instanceof List<?> list ? new ArrayList<>(list) : List.of();
        List<Object> renamedPins = to == null ? pins : renamePins(kind, pins, from, to);
        String signature = repository.json(Arrays.asList(kind.key, from, to, rows, choices.value(), pins));
        return new Plan(kind, from, to, matches, rows, vocabulary, choices, pins, renamedPins, JournalQueryService.digest(signature));
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> preview(Map<String,Object> request) {
        var plan = plan(request);
        if (!plan.available()) throw new IllegalArgumentException("此名称已从列表移除，请刷新列表");
        return Map.of("previewToken", plan.token, "rows", plan.rows, "count", plan.rows.size(),
                "pinnedChanged", !plan.pins.equals(plan.renamedPins));
    }

    private static Set<RowId> selected(Object raw, List<Match> matches) {
        if (!(raw instanceof List<?> decisions) || decisions.size() != matches.size())
            throw new IllegalArgumentException("请逐条选择修改或保留原记录");
        Set<RowId> missing = new HashSet<>(), selected = new HashSet<>();
        for (var match : matches) missing.add(match.key);
        for (Object value : decisions) {
            if (!(value instanceof Map<?,?> entry) || !(entry.get("collection") instanceof String collection)
                    || !(entry.get("id") instanceof String id) || !(entry.get("change") instanceof Boolean change))
                throw new IllegalArgumentException("每条记录都需要明确选择修改或保留");
            var key = new RowId(collection, id);
            if (!missing.remove(key)) throw new IllegalArgumentException("选择中有重复或无关的记录");
            if (change) selected.add(key);
        }
        return selected;
    }

    @Transactional(isolation=Isolation.SERIALIZABLE)
    public Map<String,Object> apply(Map<String,Object> request) {
        if (!(request.get("previewToken") instanceof String token) || token.length() != 64)
            throw new IllegalArgumentException("请先查看受影响的记录");
        repository.lockSettings();
        var plan = plan(request);
        if (!token.equals(plan.token) || !plan.available()) throw new ApiException(409, "记录或候选项已变化，请重新查看并选择");
        var selected = selected(request.get("decisions"), plan.matches);
        String action = (plan.to == null ? "REMOVE_" : "RENAME_") + plan.kind.name();
        for (var match : plan.matches) {
            if (!selected.contains(match.key)) continue;
            var values = new LinkedHashSet<String>();
            for (Object value : (List<?>) match.row.get(plan.kind.field)) {
                if (!plan.from.equals(value)) values.add((String) value);
                else if (plan.to != null) values.add(plan.to);
            }
            updateRow(repository, match.key.collection, match.row, plan.kind.field, List.copyOf(values), action);
        }
        int retained = plan.matches.size() - selected.size();
        plan.choices.added.remove(plan.from);
        // Templates and later imports may still contain this name. Only an explicit add re-enables it.
        plan.choices.hidden.add(plan.from);
        if (plan.to != null) plan.choices.enable(plan.to);
        var changes = new LinkedHashMap<String,Object>();
        changes.put("journalVocabulary", vocabularyWith(plan.vocabulary, plan.kind, plan.choices));
        boolean pinnedChanged = retained == 0 && !plan.pins.equals(plan.renamedPins);
        if (pinnedChanged) changes.put("journalPinnedFilters", plan.renamedPins);
        data.updateSettings(changes);
        return Map.of("updated", selected.size(), "retained", retained, "pinnedChanged", pinnedChanged);
    }

    private static void updateRow(DataRepository repository, String collection, Map<String,Object> before,
                                  String field, List<String> values, String action) {
        long version = DataService.version(before.get("version"));
        var after = new LinkedHashMap<>(before);
        after.put(field, values);
        after.put("version", version + 1);
        after.put("updatedAt", DataValidator.now());
        if (!repository.update(collection, after, version)) throw ApiException.conflict();
        repository.audit(action, collection, before.get("id").toString(), before, after);
    }

    private static List<Object> renamePins(Kind kind, List<Object> pins, String from, String to) {
        if (kind == Kind.TAG) return JournalQueryService.renamePinned(pins, from, to);
        var result = new ArrayList<Object>();
        for (Object value : pins) {
            if (value instanceof Map<?,?> pin && pin.get("query") instanceof Map<?,?> query && from.equals(query.get("person"))) {
                var nextQuery = new LinkedHashMap<Object,Object>(query);
                nextQuery.put("person", to);
                var nextPin = new LinkedHashMap<Object,Object>(pin);
                nextPin.put("query", nextQuery);
                result.add(nextPin);
            } else result.add(value);
        }
        return result;
    }
}
