package com.huuhv.socialanalytics.client.social;

import com.huuhv.socialanalytics.enums.Provider;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Áp dụng chung cho {@link FacebookMockClient} và {@link TwitterMockClient}.
 */
class MockSocialClientTest {

    private static final String TOKEN = "mock-access-token";
    private static final Instant NOW = Instant.parse("2026-11-07T10:30:00Z");

    static Stream<Arguments> clients() {
        return Stream.of(
                Arguments.of(Provider.FACEBOOK, (Function<Clock, SocialPlatformClient>) FacebookMockClient::new),
                Arguments.of(Provider.TWITTER, (Function<Clock, SocialPlatformClient>) TwitterMockClient::new));
    }

    @ParameterizedTest
    @MethodSource("clients")
    void reportsItsProvider(Provider provider, Function<Clock, SocialPlatformClient> factory) {
        assertThat(factory.apply(clockAt(NOW)).provider()).isEqualTo(provider);
    }

    @ParameterizedTest
    @MethodSource("clients")
    void sameHourReturnsSameMetrics(Provider provider, Function<Clock, SocialPlatformClient> factory) {
        List<PostMetrics> first = factory.apply(clockAt(NOW)).fetchMetrics(TOKEN, List.of("p1", "p2"));
        List<PostMetrics> laterSameHour = factory.apply(clockAt(NOW.plus(Duration.ofMinutes(20))))
                .fetchMetrics(TOKEN, List.of("p1", "p2"));

        assertThat(laterSameHour).isEqualTo(first);
    }

    @ParameterizedTest
    @MethodSource("clients")
    void metricsIncreaseEveryHour(Provider provider, Function<Clock, SocialPlatformClient> factory) {
        List<PostMetrics> series = new ArrayList<>();
        for (int hour = 0; hour < 200; hour++) {
            series.add(factory.apply(clockAt(NOW.plus(Duration.ofHours(hour))))
                    .fetchMetrics(TOKEN, List.of("1234567890")).getFirst());
        }

        IntStream.range(1, series.size()).forEach(i -> {
            PostMetrics previous = series.get(i - 1);
            PostMetrics current = series.get(i);
            assertThat(current.likes()).isGreaterThan(previous.likes());
            assertThat(current.shares()).isGreaterThan(previous.shares());
            assertThat(current.comments()).isGreaterThan(previous.comments());
            assertThat(current.followers()).isGreaterThan(previous.followers());
        });
    }

    @ParameterizedTest
    @MethodSource("clients")
    void hourlyGrowthIsNotConstant(Provider provider, Function<Clock, SocialPlatformClient> factory) {
        // "Ngẫu nhiên": mức tăng mỗi giờ thay đổi, không phải đường thẳng
        long[] likes = IntStream.range(0, 50)
                .mapToLong(hour -> factory.apply(clockAt(NOW.plus(Duration.ofHours(hour))))
                        .fetchMetrics(TOKEN, List.of("p1")).getFirst().likes())
                .toArray();
        long distinctDeltas = IntStream.range(1, likes.length).mapToLong(i -> likes[i] - likes[i - 1]).distinct().count();

        assertThat(distinctDeltas).isGreaterThan(1);
    }

    @ParameterizedTest
    @MethodSource("clients")
    void differentPostsGetDifferentMetrics(Provider provider, Function<Clock, SocialPlatformClient> factory) {
        List<PostMetrics> metrics = factory.apply(clockAt(NOW)).fetchMetrics(TOKEN, List.of("p1", "p2"));

        assertThat(metrics).extracting(PostMetrics::externalId).containsExactly("p1", "p2");
        assertThat(metrics.get(0)).usingRecursiveComparison().ignoringFields("externalId")
                .isNotEqualTo(metrics.get(1));
    }

    @ParameterizedTest
    @MethodSource("clients")
    void metricsAreNotNegativeBeforeEpoch(Provider provider, Function<Clock, SocialPlatformClient> factory) {
        PostMetrics metrics = factory.apply(clockAt(Instant.parse("2020-01-01T00:00:00Z")))
                .fetchMetrics(TOKEN, List.of("p1")).getFirst();

        assertThat(List.of(metrics.likes(), metrics.shares(), metrics.comments(), metrics.followers()))
                .allMatch(value -> value >= 0);
    }

    @ParameterizedTest
    @MethodSource("clients")
    void blankAndDuplicateIdsAreSkipped(Provider provider, Function<Clock, SocialPlatformClient> factory) {
        List<PostMetrics> metrics = factory.apply(clockAt(NOW))
                .fetchMetrics(TOKEN, Arrays.asList("p1", " ", null, "p1", "p2"));

        assertThat(metrics).extracting(PostMetrics::externalId).containsExactly("p1", "p2");
    }

    @ParameterizedTest
    @MethodSource("clients")
    void emptyIdListReturnsEmptyResult(Provider provider, Function<Clock, SocialPlatformClient> factory) {
        assertThat(factory.apply(clockAt(NOW)).fetchMetrics(TOKEN, List.of())).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("clients")
    void rejectsMoreIdsThanAllowedPerRequest(Provider provider, Function<Clock, SocialPlatformClient> factory) {
        List<String> ids = IntStream.rangeClosed(1, SocialPlatformClient.MAX_IDS_PER_REQUEST + 1)
                .mapToObj(String::valueOf).toList();

        assertThatThrownBy(() -> factory.apply(clockAt(NOW)).fetchMetrics(TOKEN, ids))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void blankTokenIsRejected(String token) {
        for (SocialPlatformClient client : List.of(new FacebookMockClient(clockAt(NOW)),
                new TwitterMockClient(clockAt(NOW)))) {
            assertThatThrownBy(() -> client.fetchMetrics(token, List.of("p1")))
                    .isInstanceOf(InvalidAccessTokenException.class)
                    .isInstanceOf(SocialPlatformException.class);
            assertThatThrownBy(() -> client.fetchMetrics("  ", List.of("p1")))
                    .isInstanceOf(InvalidAccessTokenException.class);
        }
    }

    private static Clock clockAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }
}
