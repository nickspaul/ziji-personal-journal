package cn.ziji.ledger;

import cn.ziji.data.*;
import cn.ziji.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LedgerReviewServiceTest {
    private final String id=UUID.randomUUID().toString();
    private final DataService data=mock(DataService.class);
    private final LedgerReviewService service=new LedgerReviewService(mock(JdbcTemplate.class),data,mock(DataRepository.class));
    private final LocalDate due=LocalDate.now(DataValidator.ZONE);
    private Map<String,Object> bill(LocalDate next,long version) {
        return Map.of("id",id,"title","月租","amount",new BigDecimal("20.00"),"category","通信","monthDay",BigDecimal.valueOf(due.getDayOfMonth()),"nextDue",next.toString(),"version",version);
    }
    @Test void februaryClampsTheDayWithoutLosingTheMarchAnchor() {
        assertEquals(LocalDate.parse("2025-02-28"),LedgerReviewService.nextMonth(LocalDate.parse("2025-01-31"),31));
        assertEquals(LocalDate.parse("2025-03-31"),LedgerReviewService.nextMonth(LocalDate.parse("2025-02-28"),31));
        assertEquals(LocalDate.parse("2024-02-29"),LedgerReviewService.nextMonth(LocalDate.parse("2024-01-31"),31));
    }
    @Test void confirmingAndRetryingOnePeriodProducesOnlyOnePayment() {
        LocalDate next=LedgerReviewService.nextMonth(due,due.getDayOfMonth());
        when(data.require("bills",id)).thenReturn(bill(due,2),bill(next,3));
        when(data.update(eq("bills"),eq(id),anyMap())).thenReturn(bill(next,3));
        String paymentId=LedgerReviewService.paymentId(id,due);
        var payment=Map.<String,Object>of("id",paymentId,"amount",new BigDecimal("20.00"),"direction","EXPENSE","date",due.toString(),"category","通信","notes","月租");
        when(data.find("ledger",paymentId)).thenReturn(payment);
        var request=new LedgerReviewController.Settlement(2,due,due,false);
        assertEquals(paymentId,service.settle(id,request).get("ledgerId"));
        assertEquals(paymentId,service.settle(id,request).get("ledgerId"));
        verify(data).create("ledger",payment);
        verify(data).update("bills",id,Map.of("version",2L,"nextDue",next.toString()));
    }
    @Test void skippingNeverCreatesALedgerEntryAndStaleConfirmationCannotPayAnotherMonth() {
        LocalDate next=LedgerReviewService.nextMonth(due,due.getDayOfMonth());
        when(data.require("bills",id)).thenReturn(bill(due,2),bill(next,3));
        when(data.update(eq("bills"),eq(id),anyMap())).thenReturn(bill(next,3));
        when(data.find("ledger",LedgerReviewService.paymentId(id,due))).thenReturn(null);
        service.settle(id,new LedgerReviewController.Settlement(2,due,null,true));
        assertThrows(ApiException.class,()->service.settle(id,new LedgerReviewController.Settlement(2,due,due,false)));
        verify(data,never()).create(anyString(),anyMap());
    }
    @Test void staleTripOutOfRangeAndOwnedExpenseAreRejectedBeforeSaving() {
        var trip=Map.<String,Object>of("version",2L,"startDate","2025-10-01","endDate","2025-10-03");
        when(data.require("journal",id)).thenReturn(trip);
        String tx=UUID.randomUUID().toString();
        var changes=List.of(new LedgerReviewController.Selection(tx,1,"PENDING"));
        assertThrows(ApiException.class,()->service.editTrip(id,new LedgerReviewController.TripEdit(1,changes)));
        when(data.require("ledger",tx)).thenReturn(Map.of("date","2025-10-04","direction","EXPENSE"),Map.of("date","2025-10-02","direction","EXPENSE","tripId",tx));
        assertThrows(ApiException.class,()->service.editTrip(id,new LedgerReviewController.TripEdit(2,changes)));
        assertThrows(ApiException.class,()->service.editTrip(id,new LedgerReviewController.TripEdit(2,changes)));
        verify(data,never()).update(anyString(),anyString(),anyMap());
    }
    @Test void olderLedgerFieldsRemainOptionalAndARuleCannotDescribeAnInvalidDate() {
        var validator=new DataValidator(new SchemaRegistry());
        var ledger=validator.normalize("ledger",Map.of("date","2025-10-01","direction","EXPENSE","amount","2.30","category","交通"),null,true);
        assertNull(ledger.get("tripId"));assertNull(ledger.get("reimbursement"));
        assertThrows(IllegalArgumentException.class,()->validator.normalize("ledger",Map.of("tripId",id),ledger,false));
        assertThrows(IllegalArgumentException.class,()->validator.normalize("ledger",Map.of("direction","INCOME","tripId",id,"reimbursement","SELF"),ledger,false));
        var valid=validator.normalize("bills",Map.of("title","月租","amount","2.30","category","通信","monthDay",31,"nextDue","2025-02-28"),null,false);
        assertThrows(IllegalArgumentException.class,()->validator.normalize("bills",Map.of("nextDue","2025-03-28"),valid,false));
    }
}
