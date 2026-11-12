package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.dto.response.ImportCounts;
import com.huuhv.socialanalytics.repository.UserImportStatisticRepository;
import com.huuhv.socialanalytics.service.ImportStatisticsService.ApplyResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImportStatisticsServiceTest {

    private static final long IMPORT_ID = 21L;
    private static final long OWNER_ID = 7L;
    // Server +07: thời điểm ghi DB vẫn là giờ UTC
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T04:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));
    private static final LocalDateTime NOW_UTC = LocalDateTime.of(2026, 11, 10, 4, 0);
    private static final LocalDateTime IMPORTED_AT = LocalDateTime.of(2026, 11, 10, 3, 59, 58);

    @Mock
    private ImportHistoryService importHistoryService;

    @Mock
    private UserImportStatisticRepository userImportStatisticRepository;

    private ImportStatisticsService service;

    @BeforeEach
    void setUp() {
        service = new ImportStatisticsService(importHistoryService, userImportStatisticRepository, CLOCK);
    }

    @Test
    void appliesPendingImportToOwnerStatisticsUsingCountsFromDatabase() {
        givenPendingImport();

        ApplyResult result = service.applyImport(IMPORT_ID, OWNER_ID);

        assertThat(result).isEqualTo(ApplyResult.APPLIED);
        verify(importHistoryService).markProcessed(IMPORT_ID, NOW_UTC);
        verify(userImportStatisticRepository).addImport(OWNER_ID, 10, 8, 2, IMPORTED_AT, NOW_UTC);
    }

    @Test
    void duplicateMessageForProcessedImportDoesNotAddAgain() {
        when(importHistoryService.markProcessed(IMPORT_ID, NOW_UTC)).thenReturn(false);
        when(importHistoryService.exists(IMPORT_ID)).thenReturn(true);

        ApplyResult result = service.applyImport(IMPORT_ID, OWNER_ID);

        assertThat(result).isEqualTo(ApplyResult.ALREADY_PROCESSED);
        verify(importHistoryService, never()).findCounts(anyLong());
        verifyNothingAdded();
    }

    @Test
    void unknownImportIsIgnored() {
        when(importHistoryService.exists(IMPORT_ID)).thenReturn(false);

        assertThat(service.applyImport(IMPORT_ID, OWNER_ID)).isEqualTo(ApplyResult.NOT_FOUND);
        verifyNothingAdded();
    }

    @Test
    void ownerFromMessageIsNotTrustedWhenItDiffersFromHistory() {
        givenPendingImport();

        // Message giả mạo / sai: userId khác chủ sở hữu thật → thống kê vẫn cộng cho owner lưu trong DB
        ApplyResult result = service.applyImport(IMPORT_ID, 999L);

        assertThat(result).isEqualTo(ApplyResult.APPLIED);
        verify(userImportStatisticRepository).addImport(eq(OWNER_ID), anyInt(), anyInt(), anyInt(), any(), any());
        verify(userImportStatisticRepository, never())
                .addImport(eq(999L), anyInt(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void messageWithoutUserIdIsStillAppliedToStoredOwner() {
        givenPendingImport();

        assertThat(service.applyImport(IMPORT_ID, null)).isEqualTo(ApplyResult.APPLIED);
        verify(userImportStatisticRepository).addImport(OWNER_ID, 10, 8, 2, IMPORTED_AT, NOW_UTC);
    }

    @Test
    void databaseErrorPropagatesSoTransactionRollsBackAndMessageIsRedelivered() {
        givenPendingImport();
        doThrow(new QueryTimeoutException("lock wait timeout")).when(userImportStatisticRepository)
                .addImport(anyLong(), anyInt(), anyInt(), anyInt(), any(), any());

        assertThatThrownBy(() -> service.applyImport(IMPORT_ID, OWNER_ID))
                .isInstanceOf(QueryTimeoutException.class);
    }

    private void givenPendingImport() {
        when(importHistoryService.markProcessed(IMPORT_ID, NOW_UTC)).thenReturn(true);
        when(importHistoryService.findCounts(IMPORT_ID))
                .thenReturn(Optional.of(new ImportCounts(OWNER_ID, 10, 8, 2, IMPORTED_AT)));
    }

    private void verifyNothingAdded() {
        verify(userImportStatisticRepository, never())
                .addImport(anyLong(), anyInt(), anyInt(), anyInt(), any(), any());
    }
}
