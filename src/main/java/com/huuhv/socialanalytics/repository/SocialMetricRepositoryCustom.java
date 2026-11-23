package com.huuhv.socialanalytics.repository;

import com.huuhv.socialanalytics.entity.SocialMetric;

import java.util.List;

/** Thao tác ghi số lượng lớn của {@link SocialMetricRepository}, không đi qua persistence context. */
public interface SocialMetricRepositoryCustom {

    /**
     * Ghi nhiều snapshot bằng INSERT nhiều dòng (mỗi câu tối đa {@value SocialMetricRepositoryImpl#ROWS_PER_STATEMENT}
     * dòng). {@code saveAll} với id {@code IDENTITY} gửi 1 câu INSERT cho mỗi snapshot và giữ mọi entity trong
     * persistence context. Entity truyền vào không được quản lý, không được gán id; chỉ dùng id của {@code post}. Chạy
     * trong transaction của caller.
     *
     * @return số dòng đã ghi
     */
    int insertAll(List<SocialMetric> snapshots);
}
