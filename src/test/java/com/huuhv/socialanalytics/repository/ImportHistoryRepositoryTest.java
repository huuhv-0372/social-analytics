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
import org.springframework.data.domain.Limit;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

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
    void marksPendingImportProcessedOnlyOnce() {
        Long id = saveHistory(10, 6, 2, 2).getId();

        int first = importHistoryRepository.markProcessed(id, NOW);
        int second = importHistoryRepository.markProcessed(id, NOW.plusSeconds(5));

        // Lần 2 (message trùng): đã PROCESSED → 0 dòng, processed_at giữ mốc lần đầu
        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        ImportHistory stored = reload(id);
        assertThat(stored.getStatus()).isEqualTo(ImportStatus.PROCESSED);
        assertThat(stored.getProcessedAt()).isEqualTo(NOW);
        assertThat(stored.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void markingUnknownImportUpdatesNothing() {
        assertThat(importHistoryRepository.markProcessed(Long.MAX_VALUE, NOW)).isZero();
        assertThat(importHistoryRepository.markDeadLettered(Long.MAX_VALUE, NOW)).isZero();
    }

    @Test
    void marksPendingImportDeadLetteredWithoutProcessedAt() {
        Long id = saveHistory(10, 6, 2, 2).getId();

        assertThat(importHistoryRepository.markDeadLettered(id, NOW)).isEqualTo(1);

        ImportHistory stored = reload(id);
        assertThat(stored.getStatus()).isEqualTo(ImportStatus.DEAD_LETTERED);
        assertThat(stored.getProcessedAt()).isNull();
        assertThat(stored.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void processedImportIsNotMarkedDeadLettered() {
        // Bản sao message lỗi vào DLQ sau khi bản gốc đã xử lý xong → giữ PROCESSED
        Long id = saveHistory(10, 6, 2, 2).getId();
        importHistoryRepository.markProcessed(id, NOW);

        assertThat(importHistoryRepository.markDeadLettered(id, NOW.plusSeconds(5))).isZero();
        assertThat(reload(id).getStatus()).isEqualTo(ImportStatus.PROCESSED);
    }

    @Test
    void deadLetteredImportCanStillBeProcessedOnceWhenMessageIsResent() {
        Long id = saveHistory(10, 6, 2, 2).getId();
        importHistoryRepository.markDeadLettered(id, NOW);

        assertThat(importHistoryRepository.markProcessed(id, NOW.plusMinutes(1))).isEqualTo(1);
        assertThat(importHistoryRepository.markProcessed(id, NOW.plusMinutes(2))).isZero();
        assertThat(reload(id).getStatus()).isEqualTo(ImportStatus.PROCESSED);
    }

    @Test
    void findsOnlyPendingImportsNotTouchedSinceCutoffOldestFirst() {
        Long recent = saveHistoryUpdatedAt(NOW.minusMinutes(1));
        Long older = saveHistoryUpdatedAt(NOW.minusMinutes(30));
        Long oldest = saveHistoryUpdatedAt(NOW.minusMinutes(60));
        Long processed = saveHistoryUpdatedAt(NOW.minusMinutes(90));
        importHistoryRepository.markProcessed(processed, NOW.minusMinutes(90));
        Long deadLettered = saveHistoryUpdatedAt(NOW.minusMinutes(90));
        importHistoryRepository.markDeadLettered(deadLettered, NOW.minusMinutes(90));

        List<Long> ids = importHistoryRepository.findPendingIdsUpdatedBefore(NOW.minusMinutes(10), Limit.of(10));

        // PROCESSED / DEAD_LETTERED / PENDING mới ghi không được gửi lại; lâu chưa thử nhất đứng trước
        assertThat(ids).containsExactly(oldest, older).doesNotContain(recent);
        assertThat(importHistoryRepository.findPendingIdsUpdatedBefore(NOW.minusMinutes(10), Limit.of(1)))
                .containsExactly(oldest);
    }

    @Test
    void touchPendingMovesUpdatedAtOnlyWhilePending() {
        Long pending = saveHistoryUpdatedAt(NOW.minusMinutes(30));
        Long processed = saveHistoryUpdatedAt(NOW.minusMinutes(30));
        importHistoryRepository.markProcessed(processed, NOW.minusMinutes(20));

        assertThat(importHistoryRepository.touchPending(pending, NOW)).isEqualTo(1);
        assertThat(importHistoryRepository.touchPending(processed, NOW)).isZero();
        assertThat(importHistoryRepository.touchPending(Long.MAX_VALUE, NOW)).isZero();

        assertThat(reload(pending)).satisfies(history -> {
            assertThat(history.getStatus()).isEqualTo(ImportStatus.PENDING);
            assertThat(history.getUpdatedAt()).isEqualTo(NOW);
        });
        // Vừa gửi lại → chưa đủ min-age, lần chạy kế tiếp không gửi nữa
        assertThat(importHistoryRepository.findPendingIdsUpdatedBefore(NOW.minusMinutes(10), Limit.of(10)))
                .doesNotContain(pending);
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

    /** UPDATE hàng loạt không cập nhật persistence context → đọc lại từ DB. */
    private ImportHistory reload(Long id) {
        entityManager.clear();
        return importHistoryRepository.findById(id).orElseThrow();
    }

    /** Import PENDING có {@code updated_at} cho trước (@PreUpdate không chạy với UPDATE JPQL → đặt bằng touchPending). */
    private Long saveHistoryUpdatedAt(LocalDateTime updatedAt) {
        Long id = saveHistory(1, 1, 0, 0).getId();
        importHistoryRepository.touchPending(id, updatedAt);
        return id;
    }

    private ImportHistory saveHistory(int total, int created, int updated, int failed) {
        ImportHistory history = importHistoryRepository.saveAndFlush(ImportHistory.builder()
                .user(owner).totalRows(total).createdRows(created).updatedRows(updated).failedRows(failed)
                .status(ImportStatus.PENDING).build());
        entityManager.clear();
        return history;
    }
}
