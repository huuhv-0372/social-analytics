package com.huuhv.socialanalytics.repository;

import com.huuhv.socialanalytics.TestcontainersConfiguration;
import com.huuhv.socialanalytics.dto.response.ImportCounts;
import com.huuhv.socialanalytics.entity.ImportHistory;
import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.enums.ImportStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ImportHistoryRepositoryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 4, 0, 0, 123_456_000);

    @Autowired
    private ImportHistoryRepository importHistoryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    private User owner;

    @BeforeEach
    void setUp() {
        owner = userRepository.save(User.builder().displayName("Owner").build());
    }

    @Test
    void transitionsPendingImportOnlyOnce() {
        Long id = saveHistory(10, 6, 2, 2).getId();

        int first = importHistoryRepository.transitionStatus(id, ImportStatus.PENDING, ImportStatus.PROCESSED, NOW);
        int second = importHistoryRepository.transitionStatus(id, ImportStatus.PENDING, ImportStatus.PROCESSED, NOW);

        // Lần 2 (message trùng) không khớp điều kiện status = PENDING → 0 dòng
        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        entityManager.clear();
        ImportHistory stored = importHistoryRepository.findById(id).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ImportStatus.PROCESSED);
        assertThat(stored.getProcessedAt()).isEqualTo(NOW);
        assertThat(stored.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void transitionOfUnknownImportUpdatesNothing() {
        assertThat(importHistoryRepository.transitionStatus(Long.MAX_VALUE, ImportStatus.PENDING,
                ImportStatus.PROCESSED, NOW)).isZero();
    }

    @Test
    void findsCountsWithSuccessRowsAndOwner() {
        ImportHistory history = saveHistory(10, 6, 2, 2);

        ImportCounts counts = importHistoryRepository.findCountsById(history.getId()).orElseThrow();

        assertThat(counts).isEqualTo(new ImportCounts(owner.getId(), 10, 8, 2, history.getCreatedAt()));
    }

    @Test
    void rowCountsMustAddUpToTotal() {
        // CHECK ck_import_histories_rows: total = created + updated + failed
        assertThatThrownBy(() -> saveHistory(10, 6, 2, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private ImportHistory saveHistory(int total, int created, int updated, int failed) {
        ImportHistory history = importHistoryRepository.saveAndFlush(ImportHistory.builder()
                .user(owner).totalRows(total).createdRows(created).updatedRows(updated).failedRows(failed)
                .status(ImportStatus.PENDING).build());
        entityManager.clear();
        return history;
    }
}
