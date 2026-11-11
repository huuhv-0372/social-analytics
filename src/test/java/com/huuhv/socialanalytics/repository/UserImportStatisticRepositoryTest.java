package com.huuhv.socialanalytics.repository;

import com.huuhv.socialanalytics.TestcontainersConfiguration;
import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.entity.UserImportStatistic;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Native upsert cộng dồn thống kê — cú pháp {@code INSERT ... AS new ON DUPLICATE KEY UPDATE} chỉ kiểm được trên MySQL. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class UserImportStatisticRepositoryTest {

    private static final LocalDateTime T1 = LocalDateTime.of(2026, 11, 10, 1, 0);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 11, 10, 2, 0);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 3, 0);

    @Autowired
    private UserImportStatisticRepository repository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    private User owner;
    private User other;

    @BeforeEach
    void setUp() {
        owner = userRepository.save(User.builder().displayName("Owner").build());
        other = userRepository.save(User.builder().displayName("Other").build());
    }

    @Test
    void firstImportCreatesRow() {
        repository.addImport(owner.getId(), 10, 8, 2, T1, NOW);

        assertThat(load(owner)).satisfies(statistic -> {
            assertThat(statistic.getTotalImports()).isEqualTo(1);
            assertThat(statistic.getTotalRows()).isEqualTo(10);
            assertThat(statistic.getSuccessRows()).isEqualTo(8);
            assertThat(statistic.getFailedRows()).isEqualTo(2);
            assertThat(statistic.getLastImportAt()).isEqualTo(T1);
            assertThat(statistic.getUpdatedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void nextImportsAccumulateAndKeepLatestImportTimeEvenWhenProcessedOutOfOrder() {
        repository.addImport(owner.getId(), 10, 8, 2, T2, NOW);
        // Import cũ hơn được xử lý sau (concurrency của listener) → last_import_at không lùi lại
        repository.addImport(owner.getId(), 5, 5, 0, T1, NOW.plusMinutes(1));

        assertThat(load(owner)).satisfies(statistic -> {
            assertThat(statistic.getTotalImports()).isEqualTo(2);
            assertThat(statistic.getTotalRows()).isEqualTo(15);
            assertThat(statistic.getSuccessRows()).isEqualTo(13);
            assertThat(statistic.getFailedRows()).isEqualTo(2);
            assertThat(statistic.getLastImportAt()).isEqualTo(T2);
            assertThat(statistic.getUpdatedAt()).isEqualTo(NOW.plusMinutes(1));
        });
    }

    @Test
    void statisticsAreKeptPerUser() {
        repository.addImport(owner.getId(), 10, 8, 2, T1, NOW);
        repository.addImport(other.getId(), 3, 3, 0, T2, NOW);

        assertThat(load(owner).getTotalRows()).isEqualTo(10);
        assertThat(load(other).getTotalRows()).isEqualTo(3);
    }

    @Test
    void userWithoutImportHasNoRow() {
        assertThat(repository.findById(owner.getId())).isEmpty();
    }

    private UserImportStatistic load(User user) {
        entityManager.clear();
        return repository.findById(user.getId()).orElseThrow();
    }
}
