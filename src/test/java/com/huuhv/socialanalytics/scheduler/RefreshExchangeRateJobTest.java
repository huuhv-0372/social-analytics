package com.huuhv.socialanalytics.scheduler;

import com.huuhv.socialanalytics.service.ExchangeRateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class RefreshExchangeRateJobTest {

    @Mock
    private ExchangeRateService exchangeRateService;

    @InjectMocks
    private RefreshExchangeRateJob job;

    /** Job chỉ nhờ Service làm mới khi đến hạn — Service quyết định có gọi SOAP hay không. */
    @Test
    void delegatesToRefreshIfDue() {
        job.refreshExchangeRateJob();

        verify(exchangeRateService).refreshIfDue();
        verifyNoMoreInteractions(exchangeRateService);
    }
}
