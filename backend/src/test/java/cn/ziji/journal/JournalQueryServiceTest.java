package cn.ziji.journal;

import cn.ziji.data.DataRepository;
import cn.ziji.data.DataService;
import cn.ziji.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.util.LinkedMultiValueMap;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JournalQueryServiceTest {
    private static LocalDate day(String value){return LocalDate.parse(value);}
    private static LinkedMultiValueMap<String,String> parameters(String... pairs){var p=new LinkedMultiValueMap<String,String>();for(int i=0;i<pairs.length;i+=2)p.add(pairs[i],pairs[i+1]);return p;}

    @Test void chineseWordsAndWildcardsStayLiteralAndParameterized() {
        var plan=JournalQueryService.plan(parameters("q","出差 50%_完成! ' OR 1=1"));
        assertFalse(plan.where().contains("出差"));assertFalse(plan.where().contains("OR 1=1"));
        assertTrue(plan.arguments().contains("%出差%"));assertTrue(plan.arguments().contains("%50!%!_完成!!%"));
        assertEquals("%x!!y!%!_%",JournalQueryService.like("X!Y%_"));
    }

    @Test void exactTagTokensAndSelectedTagsAreNormalizedWithoutLosingSpaces() {
        var plan=JournalQueryService.plan(parameters("q","#出差 #\"Project A\" 工作","tags"," #PROJECT A ","tags","project a","tagMode","any"));
        assertEquals(2,Collections.frequency(plan.arguments(),"project a"));
        assertTrue(plan.arguments().contains("出差"));assertTrue(plan.arguments().contains("%工作%"));
        assertEquals("project a",JournalQueryService.tag(" ##Project A "));
    }

    @Test void intervalSearchIncludesAnEventThatStartedBeforeTheSelectedDate() {
        var p=JournalQueryService.plan(parameters("from","2026-10-21","to","2026-10-21"));
        assertTrue(p.where().contains("COALESCE(r.endDate,r.startDate)>=?"));assertTrue(p.where().contains("r.startDate<=?"));
        assertEquals(List.of(false,"2026-10-21","2026-10-21"),p.arguments());
    }

    @Test void queryRejectsInjectedIdentifiersInvalidLimitsAndReversedDates() {
        assertThrows(IllegalArgumentException.class,()->JournalQueryService.plan(parameters("collection","journal; DELETE FROM zj_auth")));
        assertThrows(IllegalArgumentException.class,()->JournalQueryService.plan(parameters("collection","tasks","dateField","plannedDate) OR 1=1")));
        assertThrows(IllegalArgumentException.class,()->JournalQueryService.plan(parameters("size","50000")));
        assertThrows(IllegalArgumentException.class,()->JournalQueryService.plan(parameters("page","0")));
        assertThrows(IllegalArgumentException.class,()->JournalQueryService.plan(parameters("from","2026-10-22","to","2026-10-20")));
        assertThrows(IllegalArgumentException.class,()->JournalQueryService.plan(parameters("collection","tasks","view","dates")));
    }

    @Test void taskStateAndDateMeaningRemainSeparateFromJournalDates() {
        var p=JournalQueryService.plan(parameters("collection","tasks","status","OPEN","dateField","dueDate","to","2026-10-26","unscheduled","true","important","true"));
        assertTrue(p.where().contains("r.status IN ('TODO','DOING','PAUSED')"));assertTrue(p.where().contains("r.dueDate<=?"));
        assertTrue(p.where().contains("r.plannedDate IS NULL"));assertTrue(p.where().contains("r.important=TRUE"));
        assertFalse(p.where().contains("startDate"));
        var overdue=JournalQueryService.plan(parameters("collection","tasks","overdue","true"));
        assertTrue(overdue.where().contains("r.status IN ('TODO','DOING','PAUSED') AND r.dueDate<?"));
    }

    @Test void deadlineAndCompletionHistorySortByTheirDatesBeforeImportance() {
        var due=JournalQueryService.plan(parameters("collection","tasks","dateField","dueDate","order","asc"));
        assertEquals("(r.dueDate IS NULL) ASC,r.dueDate ASC,r.important DESC,r.createdAt ASC,r.id ASC",JournalQueryService.ordering(due));
        var completed=JournalQueryService.plan(parameters("collection","tasks","status","DONE","dateField","completedDate","order","desc"));
        assertTrue(JournalQueryService.ordering(completed).startsWith("(r.completedDate IS NULL) ASC,r.completedDate DESC,r.important DESC"));
        var planned=JournalQueryService.plan(parameters("collection","tasks","dateField","plannedDate"));
        assertTrue(JournalQueryService.ordering(planned).startsWith("r.important DESC,"));
    }

    @SuppressWarnings("unchecked")
    @Test void matchingDaysAreDeduplicatedAcrossAllEventsAndClippedToTheFilter() {
        var ranges=List.of(new JournalQueryService.DateRange(day("2026-10-20"),day("2026-10-22")),
            new JournalQueryService.DateRange(day("2026-10-21"),null),new JournalQueryService.DateRange(day("2026-10-22"),day("2026-10-25")));
        var response=JournalQueryService.datePage(ranges,day("2026-10-21"),day("2026-10-23"),1,20,true);
        assertEquals(3L,response.get("total"));
        assertEquals(List.of(Map.of("date","2026-10-21","count",2L),Map.of("date","2026-10-22","count",2L),Map.of("date","2026-10-23","count",1L)),response.get("dates"));
        assertEquals(List.of(),response.get("items"));
    }

    @SuppressWarnings("unchecked")
    @Test void hugeIntervalsAreCountedWithoutGeneratingEveryDayAndPaginationIsStable() {
        var ranges=List.of(new JournalQueryService.DateRange(day("1000-01-01"),day("2026-10-26")));
        var first=JournalQueryService.datePage(ranges,null,null,1,20,false);
        var second=JournalQueryService.datePage(ranges,null,null,2,20,false);
        var firstDates=(List<Map<String,Object>>)first.get("dates");var secondDates=(List<Map<String,Object>>)second.get("dates");
        assertEquals(20,firstDates.size());assertEquals(20,secondDates.size());
        assertEquals("2026-10-26",firstDates.get(0).get("date"));assertEquals("2026-10-06",secondDates.get(0).get("date"));
        assertTrue((long)first.get("total")>300000);assertTrue(firstDates.stream().noneMatch(secondDates::contains));
    }

    @Test void anEmptyDateResultAlwaysReturnsFirstPage() {
        var response=JournalQueryService.datePage(List.of(),null,null,99,20,false);
        assertEquals(0L,response.get("total"));assertEquals(1,response.get("page"));assertEquals(List.of(),response.get("dates"));
    }

    @Test void pinnedRenameKeepsUserNamesAndOrdinarySearchWords() {
        List<Object> pinned=List.of(Map.of("id","pin","name","我的出差记录","query",Map.of("collection","journal","tags",List.of("出差","项目 a"),"q","出差 #出差 #\"其他 标签\"")));
        var result=JournalQueryService.renamePinned(pinned,"出差","项目 a");
        var entry=(Map<?,?>)result.get(0);var query=(Map<?,?>)entry.get("query");
        assertEquals("我的出差记录",entry.get("name"));assertEquals(List.of("项目 a"),query.get("tags"));
        assertEquals("出差 #\"项目 a\" #\"其他 标签\"",query.get("q"));
        var parsed=JournalQueryService.plan(parameters("q",query.get("q").toString()));
        assertTrue(parsed.arguments().contains("项目 a"));assertTrue(parsed.arguments().contains("其他 标签"));
        assertEquals(List.of("出差","项目 a"),((Map<?,?>)((Map<?,?>)pinned.get(0)).get("query")).get("tags"));
    }

    @Test void unrelatedIncompleteHashTokenInSavedSearchSurvivesRename() {
        var pin=Map.of("name","测试","query",Map.of("q","## #项目"));
        var result=JournalQueryService.renamePinned(List.of(pin),"项目","工作");
        assertEquals(List.of(Map.of("name","测试","query",Map.of("q","## #工作"))),result);
    }

    private record Fixture(JournalQueryService service,JdbcTemplate jdbc,DataRepository repository,DataService data,Map<String,Object> row) {}
    @SuppressWarnings({"unchecked","rawtypes"})
    private Fixture tagFixture() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var repository=mock(DataRepository.class);var data=mock(DataService.class);
        var row=new LinkedHashMap<String,Object>();row.putAll(Map.of("id","ef7525d6-c85d-41f0-bb04-7c48ed1d010a","version",1L,"deleted",true,"title","旧经历","content","正文不变","tags",List.of("出差","客户")));
        doAnswer(call->{String sql=call.getArgument(0);return sql.contains("zj_journal")&&((List<?>)row.get("tags")).contains("出差")?List.of(row):List.of();}).when(jdbc).query(anyString(),any(RowMapper.class),any(Object[].class));
        when(data.settings()).thenReturn(Map.of("journalPinnedFilters",List.of()));
        when(data.require("journal",row.get("id").toString())).thenReturn(row);
        var json=new ObjectMapper();when(repository.json(any())).thenAnswer(call->json.writeValueAsString(call.getArgument(0)));
        when(repository.update(eq("journal"),anyMap(),eq(1L))).thenReturn(true);
        return new Fixture(new JournalQueryService(jdbc,repository,data,mock(TaskEffortService.class)),jdbc,repository,data,row);
    }

    @Test void retiredBulkEndpointCannotRenameWithoutIndividualDecisions() throws Exception {
        var f=tagFixture();var request=new LinkedHashMap<String,Object>(Map.of("from","出差","to","差旅"));
        var preview=f.service.previewTag(request);assertEquals(1,preview.get("count"));assertEquals(1L,preview.get("trashCount"));
        request.put("previewToken",preview.get("previewToken"));
        assertThrows(ApiException.class,()->f.service.renameTag(request));
        verify(f.repository,never()).update(anyString(),anyMap(),anyLong());
    }

    @Test void staleIndividualPreviewIsRejectedBeforeAnyRowsOrSettingsAreWritten() throws Exception {
        var f=tagFixture();var terms=new JournalTermService(f.jdbc,f.repository,f.data);
        var request=new LinkedHashMap<String,Object>(Map.of("kind","tag","from","出差","to","差旅"));
        request.put("previewToken",terms.preview(request).get("previewToken"));f.row.put("version",2L);
        request.put("decisions",List.of(Map.of("collection","journal","id",f.row.get("id"),"change",true)));
        assertThrows(ApiException.class,()->terms.apply(request));verify(f.repository,never()).update(anyString(),anyMap(),anyLong());
        verify(f.data,never()).updateSettings(anyMap());
    }

    @Test void sameNormalizedTagIsANoop() throws Exception {
        var f=tagFixture();var request=new LinkedHashMap<String,Object>(Map.of("from"," #Project ","to","project"));
        var preview=f.service.previewTag(request);assertEquals(0,preview.get("count"));request.put("previewToken",preview.get("previewToken"));
        assertEquals(0,f.service.renameTag(request).get("updated"));verify(f.repository,never()).update(anyString(),anyMap(),anyLong());
        verify(f.repository,never()).audit(anyString(),anyString(),anyString(),any(),any());verify(f.data,never()).updateSettings(anyMap());
    }

    @Test @SuppressWarnings({"unchecked","rawtypes"})
    void selectiveTagRenameMergesOnlyAcceptedRowsAndKeepsOriginalSearch() throws Exception {
        var f=tagFixture();var terms=new JournalTermService(f.jdbc,f.repository,f.data);
        f.row.put("tags",List.of("出差","差旅","客户"));
        var task=Map.<String,Object>of("id","other","version",3L,"title","会议","tags",List.of("出差"),"plannedDate","2026-11-24");
        doAnswer(call->call.<String>getArgument(0).contains("zj_tasks")?List.of(task):List.of(f.row))
                .when(f.jdbc).query(anyString(),any(RowMapper.class),any(Object[].class));
        var pins=List.of(Map.of("id","pin","query",Map.of("tags",List.of("出差"),"q","出差 #出差")));
        when(f.data.settings()).thenReturn(Map.of("journalPinnedFilters",pins));
        var request=new LinkedHashMap<String,Object>(Map.of("kind","tag","from","出差","to","差旅"));
        var preview=terms.preview(request);assertEquals(2,preview.get("count"));assertEquals(true,preview.get("pinnedChanged"));
        var rows=(List<Map<String,Object>>)preview.get("rows");assertEquals(true,rows.get(0).get("deleted"));assertEquals("2026-11-24",rows.get(1).get("date"));
        request.put("previewToken",preview.get("previewToken"));
        var accept=Map.of("collection","journal","id",f.row.get("id"),"change",true);
        request.put("decisions",List.of(accept));assertThrows(IllegalArgumentException.class,()->terms.apply(request));
        request.put("decisions",List.of(accept,accept));assertThrows(IllegalArgumentException.class,()->terms.apply(request));
        request.put("decisions",List.of(accept,Map.of("collection","tasks","id","other","change","false")));
        assertThrows(IllegalArgumentException.class,()->terms.apply(request));
        verify(f.repository,never()).update(anyString(),anyMap(),anyLong());
        request.put("decisions",List.of(accept,Map.of("collection","tasks","id","other","change",false)));
        assertEquals(Map.of("updated",1,"retained",1,"pinnedChanged",false),terms.apply(request));
        ArgumentCaptor<Map<String,Object>> captured=ArgumentCaptor.forClass(Map.class);
        verify(f.repository).update(eq("journal"),captured.capture(),eq(1L));var after=captured.getValue();
        assertEquals(List.of("差旅","客户"),after.get("tags"));assertEquals(true,after.get("deleted"));
        assertEquals("正文不变",after.get("content"));assertEquals(2L,after.get("version"));
        verify(f.repository).audit("RENAME_TAG","journal",f.row.get("id").toString(),f.row,after);
        verify(f.repository,never()).update(eq("tasks"),anyMap(),anyLong());
        verify(f.data).updateSettings(captured.capture());var settings=captured.getValue();
        assertFalse(settings.containsKey("journalPinnedFilters"));
        assertEquals(List.of(Map.of("name","差旅","count",0)),JournalTermService.visibleTags(
                List.of(Map.of("name","出差","count",1)),settings.get("journalVocabulary")));
    }

    @Test @SuppressWarnings({"unchecked","rawtypes"})
    void templateTagStaysRetiredEvenWhenAllPreviouslyTaggedRowsAreUpdated() throws Exception {
        var f=tagFixture();var terms=new JournalTermService(f.jdbc,f.repository,f.data);
        var request=new LinkedHashMap<String,Object>(Map.of("kind","tag","from","出差","to","差旅"));
        request.put("previewToken",terms.preview(request).get("previewToken"));
        request.put("decisions",List.of(Map.of("id",f.row.get("id"),"collection","journal","change",true)));
        var result=terms.apply(request);assertEquals(0,result.get("retained"));
        ArgumentCaptor<Map<String,Object>> capture=ArgumentCaptor.forClass(Map.class);
        verify(f.data).updateSettings(capture.capture());var settings=capture.getValue();
        assertEquals(List.of(Map.of("name","差旅","count",0)),JournalTermService.visibleTags(
                List.of(Map.of("name","出差","count",1)),settings.get("journalVocabulary")));
    }

    @Test @SuppressWarnings({"unchecked","rawtypes"})
    void removingPersonNeverWidensPinnedSearchAndPreservesRemainingNames() throws Exception {
        var f=tagFixture();var terms=new JournalTermService(f.jdbc,f.repository,f.data);
        f.row.put("people",List.of("Alex","alex"));
        var pins=List.of(Map.of("name","聚餐","query",Map.of("person","Alex")));
        when(f.data.settings()).thenReturn(Map.of("journalPinnedFilters",pins));
        var request=new LinkedHashMap<String,Object>(Map.of("kind","person","from","Alex"));
        var preview=terms.preview(request);assertEquals(1,preview.get("count"));assertEquals(false,preview.get("pinnedChanged"));
        request.put("previewToken",preview.get("previewToken"));
        request.put("decisions",List.of(Map.of("collection","journal","id",f.row.get("id"),"change",true)));
        assertEquals(Map.of("updated",1,"retained",0,"pinnedChanged",false),terms.apply(request));
        ArgumentCaptor<Map<String,Object>> capture=ArgumentCaptor.forClass(Map.class);
        verify(f.repository).update(eq("journal"),capture.capture(),eq(1L));
        assertEquals(List.of("alex"),capture.getValue().get("people"));assertEquals(f.row.get("tags"),capture.getValue().get("tags"));
        verify(f.data).updateSettings(capture.capture());assertFalse(capture.getValue().containsKey("journalPinnedFilters"));
        verify(f.jdbc,never()).query(contains("zj_tasks"),any(RowMapper.class),any(Object[].class));
    }

    @Test @SuppressWarnings({"unchecked","rawtypes"})
    void unusedNamesCanBeAddedAndRenamedIncludingTheirPinnedPersonFilter() throws Exception {
        var f=tagFixture();var terms=new JournalTermService(f.jdbc,f.repository,f.data);
        doReturn(List.of()).when(f.jdbc).query(anyString(),any(RowMapper.class),any(Object[].class));
        ArgumentCaptor<Map<String,Object>> capture=ArgumentCaptor.forClass(Map.class);
        assertEquals(Map.of("kind","person","name","Alex"),terms.add(Map.of("kind","person","name"," Alex ")));
        verify(f.data).updateSettings(capture.capture());var vocabulary=capture.getValue().get("journalVocabulary");
        var pins=List.of(Map.of("name","同事","query",Map.of("person","Alex","q","Alex")));
        when(f.data.settings()).thenReturn(Map.of("journalVocabulary",vocabulary,"journalPinnedFilters",pins));
        var request=new LinkedHashMap<String,Object>(Map.of("kind","person","from","Alex","to","小林"));
        var preview=terms.preview(request);assertEquals(0,preview.get("count"));request.put("previewToken",preview.get("previewToken"));
        request.put("decisions",List.of());assertEquals(true,terms.apply(request).get("pinnedChanged"));
        verify(f.data,times(2)).updateSettings(capture.capture());var finalSettings=capture.getValue();
        assertEquals(List.of("小林"),JournalTermService.visiblePeople(List.of("Alex"),finalSettings.get("journalVocabulary")));
        assertEquals(List.of(Map.of("name","同事","query",Map.of("person","小林","q","Alex"))),finalSettings.get("journalPinnedFilters"));
        when(f.data.settings()).thenReturn(finalSettings);
        terms.add(Map.of("kind","person","name","Alex"));
        verify(f.data,times(3)).updateSettings(capture.capture());
        assertEquals(List.of("Alex","小林"),JournalTermService.visiblePeople(List.of("Alex"),capture.getValue().get("journalVocabulary")));
    }
}
