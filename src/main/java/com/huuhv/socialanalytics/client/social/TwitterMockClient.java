package com.huuhv.socialanalytics.client.social;

import com.huuhv.socialanalytics.enums.Provider;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Mock API v2 của X (Twitter): share (repost) tăng nhanh, comment (reply) ít.
 */
@Component
public class TwitterMockClient extends AbstractMockSocialClient {

    public TwitterMockClient(Clock clock) {
        super(Provider.TWITTER, clock);
    }

    @Override
    protected Growth growth(Metric metric) {
        return switch (metric) {
            case LIKES -> new Growth(20, 300, 3, 20);
            case SHARES -> new Growth(10, 150, 2, 15);
            case COMMENTS -> new Growth(2, 40, 1, 4);
            case FOLLOWERS -> new Growth(500, 20_000, 1, 40);
        };
    }
}
