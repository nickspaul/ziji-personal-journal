package cn.ziji.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JournalDataRepositoryTest {
    private final DataRepository repository=new DataRepository(mock(JdbcTemplate.class),new SchemaRegistry(),new ObjectMapper());
    private ResultSet row() throws Exception {
        var rs=mock(ResultSet.class);when(rs.getString("id")).thenReturn(UUID.randomUUID().toString());when(rs.getLong("version")).thenReturn(1L);
        when(rs.getTimestamp("createdAt")).thenReturn(Timestamp.valueOf("2025-10-20 14:00:00"));when(rs.getTimestamp("updatedAt")).thenReturn(Timestamp.valueOf("2025-10-20 14:00:00"));
        return rs;
    }
    @Test void typedJsonAndTimesAreReadAsEditableBusinessValues() throws Exception {
        var rs=row();when(rs.getString("title")).thenReturn("工作进展");when(rs.getDate("startDate")).thenReturn(java.sql.Date.valueOf("2025-10-20"));when(rs.getTime("startTime")).thenReturn(java.sql.Time.valueOf("14:05:00"));
        when(rs.getString("people")).thenReturn("[\"Alice\",\"小王\"]");when(rs.getString("tags")).thenReturn("[\"项目 a\",\"出差\"]");
        var result=repository.mapRow("journal",rs);assertEquals("14:05",result.get("startTime"));assertEquals("2025-10-20",result.get("startDate"));
        assertEquals(List.of("Alice","小王"),result.get("people"));assertEquals(List.of("项目 a","出差"),result.get("tags"));
        assertNull(result.get("endTime"));assertNull(result.get("endDate"));
    }
    @Test void emptyArraysStayEmptyAndInvalidStoredArraysAreNotSilentlyCoerced() throws Exception {
        var rs=row();assertEquals(List.of(),repository.mapRow("journal",rs).get("tags"));
        when(rs.getString("tags")).thenReturn("null");assertEquals(List.of(),repository.mapRow("journal",rs).get("tags"));
        when(rs.getString("tags")).thenReturn("[42]");assertThrows(IllegalStateException.class,()->repository.mapRow("journal",rs));
        when(rs.getString("tags")).thenReturn("{\"tag\":\"出差\"}");assertThrows(IllegalStateException.class,()->repository.mapRow("journal",rs));
    }
}
