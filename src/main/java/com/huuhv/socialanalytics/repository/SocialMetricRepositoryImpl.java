package com.huuhv.socialanalytics.repository;

import com.huuhv.socialanalytics.entity.SocialMetric;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.StringJoiner;

/**
 * Cài đặt {@link SocialMetricRepositoryCustom} bằng native query của JPA (tham gia transaction hiện tại). Câu SQL chỉ
 * ghép các placeholder cố định, mọi giá trị đều là tham số (OWASP A05).
 */
@RequiredArgsConstructor
class SocialMetricRepositoryImpl implements SocialMetricRepositoryCustom {

    /** 6 tham số/dòng → 3.000 tham số/câu, dưới giới hạn 65.535 placeholder của MySQL. */
    static final int ROWS_PER_STATEMENT = 500;

    private static final String INSERT_PREFIX =
            "INSERT INTO social_metrics (post_id, likes, shares, comments, followers, collected_at) VALUES ";
    private static final int COLUMNS = 6;

    private final EntityManager entityManager;

    @Override
    public int insertAll(List<SocialMetric> snapshots) {
        int inserted = 0;
        for (int from = 0; from < snapshots.size(); from += ROWS_PER_STATEMENT) {
            List<SocialMetric> chunk = snapshots.subList(from, Math.min(from + ROWS_PER_STATEMENT, snapshots.size()));
            Query query = entityManager.createNativeQuery(insertSql(chunk.size()));
            int position = 1;
            for (SocialMetric snapshot : chunk) {
                query.setParameter(position++, snapshot.getPost().getId());
                query.setParameter(position++, snapshot.getLikes());
                query.setParameter(position++, snapshot.getShares());
                query.setParameter(position++, snapshot.getComments());
                query.setParameter(position++, snapshot.getFollowers());
                query.setParameter(position++, snapshot.getCollectedAt());
            }
            inserted += query.executeUpdate();
        }
        return inserted;
    }

    private static String insertSql(int rows) {
        StringJoiner values = new StringJoiner(", ", INSERT_PREFIX, "");
        int position = 1;
        for (int row = 0; row < rows; row++) {
            StringJoiner placeholders = new StringJoiner(", ", "(", ")");
            for (int column = 0; column < COLUMNS; column++) {
                placeholders.add("?" + position++);
            }
            values.add(placeholders.toString());
        }
        return values.toString();
    }
}
