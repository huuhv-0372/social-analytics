package com.huuhv.socialanalytics.client.social;

import com.huuhv.socialanalytics.enums.Provider;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Mock Graph API của Facebook: nhiều like/comment, ít share hơn.
 */
@Component
public class FacebookMockClient extends AbstractMockSocialClient {

    public FacebookMockClient(Clock clock) {
        super(Provider.FACEBOOK, clock);
    }

    @Override
    protected Growth growth(Metric metric) {
        return switch (metric) {
            case LIKES -> new Growth(50, 500, 5, 30);
            case SHARES -> new Growth(5, 50, 1, 5);
            case COMMENTS -> new Growth(10, 100, 2, 10);
            case FOLLOWERS -> new Growth(1_000, 10_000, 1, 20);
        };
    }
}
