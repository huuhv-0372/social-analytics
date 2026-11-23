package com.huuhv.socialanalytics.client.social;

import com.huuhv.socialanalytics.enums.Provider;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SocialPlatformClientsTest {

    private final Clock clock = Clock.systemUTC();
    private final FacebookMockClient facebook = new FacebookMockClient(clock);
    private final TwitterMockClient twitter = new TwitterMockClient(clock);

    @Test
    void selectsClientByProvider() {
        SocialPlatformClients clients = new SocialPlatformClients(List.of(twitter, facebook));

        assertThat(clients.get(Provider.FACEBOOK)).isSameAs(facebook);
        assertThat(clients.get(Provider.TWITTER)).isSameAs(twitter);
    }

    @Test
    void failsWhenAProviderHasNoClient() {
        assertThatThrownBy(() -> new SocialPlatformClients(List.of(facebook)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TWITTER");
    }

    @Test
    void failsWhenAProviderHasTwoClients() {
        assertThatThrownBy(() -> new SocialPlatformClients(List.of(facebook, twitter, new FacebookMockClient(clock))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FACEBOOK");
    }

    @Test
    void rejectsNullProvider() {
        SocialPlatformClients clients = new SocialPlatformClients(List.of(facebook, twitter));

        assertThatThrownBy(() -> clients.get(null)).isInstanceOf(NullPointerException.class);
    }
}
