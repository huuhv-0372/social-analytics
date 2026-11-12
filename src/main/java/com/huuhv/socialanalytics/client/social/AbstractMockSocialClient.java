package com.huuhv.socialanalytics.client.social;

import com.huuhv.socialanalytics.enums.Provider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;

/**
 * Mock API mạng xã hội: số liệu <b>ngẫu nhiên, có xu hướng tăng</b> nhưng không lưu trạng thái.
 * <p>
 * Với mỗi bài và mỗi chỉ số, gọi {@code h} = số giờ đã qua kể từ {@link #EPOCH} (theo {@link Clock}):
 * <pre>value(h) = base + rate * h + noise(h),   noise(h) thuộc [0, rate)</pre>
 * {@code base}, {@code rate} cố định theo (provider, externalId, chỉ số); {@code noise} ngẫu nhiên theo từng giờ.
 * Vì {@code noise < rate} nên {@code value(h + 1) > value(h)}: số liệu luôn tăng qua từng giờ, khởi động lại app không
 * bị tụt so với snapshot đã lưu, cùng 1 giờ gọi lại ra cùng số (test cố định được bằng {@link Clock}).
 * <p>
 * Dùng {@link SplittableRandom} (không cần {@code SecureRandom}: số liệu giả, không phục vụ bảo mật).
 * Không gọi mạng, không log token.
 */
abstract class AbstractMockSocialClient implements SocialPlatformClient {

    /** Mốc tính số giờ; thời điểm trước mốc coi như giờ 0. */
    static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");

    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;

    /** Các chỉ số mock sinh ra. */
    protected enum Metric { LIKES, SHARES, COMMENTS, FOLLOWERS }

    /**
     * Hệ số sinh số cho 1 chỉ số: giá trị ban đầu (tại {@link #EPOCH}) trong {@code [minBase, maxBase]}, mức tăng
     * trung bình mỗi giờ trong {@code [minRate, maxRate]} với {@code minRate >= 1}.
     */
    protected record Growth(long minBase, long maxBase, long minRate, long maxRate) {

        protected Growth {
            if (minBase < 0 || maxBase < minBase || minRate < 1 || maxRate < minRate) {
                throw new IllegalArgumentException("Invalid growth profile");
            }
        }
    }

    private final Provider provider;
    private final Clock clock;

    protected AbstractMockSocialClient(Provider provider, Clock clock) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Hệ số riêng của từng nền tảng. */
    protected abstract Growth growth(Metric metric);

    @Override
    public final Provider provider() {
        return provider;
    }

    @Override
    public List<PostMetrics> fetchMetrics(String accessToken, List<String> externalIds) {
        Objects.requireNonNull(externalIds, "externalIds");
        if (accessToken == null || accessToken.isBlank()) {
            throw new InvalidAccessTokenException(provider + " rejected the access token");
        }
        if (externalIds.size() > MAX_IDS_PER_REQUEST) {
            throw new IllegalArgumentException("At most " + MAX_IDS_PER_REQUEST + " ids per request");
        }
        long hours = hoursSinceEpoch();
        return externalIds.stream()
                // Id rỗng coi như bài không tồn tại → bỏ khỏi kết quả như API thật
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .map(id -> new PostMetrics(id,
                        value(id, Metric.LIKES, hours),
                        value(id, Metric.SHARES, hours),
                        value(id, Metric.COMMENTS, hours),
                        value(id, Metric.FOLLOWERS, hours)))
                .toList();
    }

    private long hoursSinceEpoch() {
        Duration elapsed = Duration.between(EPOCH, clock.instant());
        return elapsed.isNegative() ? 0 : elapsed.toHours();
    }

    private long value(String externalId, Metric metric, long hours) {
        Growth growth = growth(metric);
        long seed = seed(externalId, metric);
        SplittableRandom fixed = new SplittableRandom(seed);
        long base = between(fixed, growth.minBase(), growth.maxBase());
        long rate = between(fixed, growth.minRate(), growth.maxRate());
        // Seed khác nhau cho mỗi giờ → noise "ngẫu nhiên" nhưng lặp lại được
        long noise = new SplittableRandom(seed ^ (hours * GOLDEN_GAMMA)).nextLong(rate);
        return base + rate * hours + noise;
    }

    /** Seed ổn định giữa các lần chạy JVM ({@link String#hashCode()} có công thức cố định trong đặc tả Java). */
    private long seed(String externalId, Metric metric) {
        return ((long) provider.ordinal() << 40) ^ ((long) externalId.hashCode() << 8) ^ metric.ordinal();
    }

    private static long between(SplittableRandom random, long min, long max) {
        return min == max ? min : random.nextLong(min, max + 1);
    }
}
