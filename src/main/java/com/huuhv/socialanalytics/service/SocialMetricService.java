package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.dto.request.SocialMetricRequest;
import com.huuhv.socialanalytics.dto.response.PageResponse;
import com.huuhv.socialanalytics.dto.response.SocialMetricResponse;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;

/**
 * Snapshot metric của bài viết. Snapshot là bất biến nên không có thao tác cập nhật.
 */
public interface SocialMetricService {

    PageResponse<SocialMetricResponse> findByPost(Long postId, LocalDateTime from, LocalDateTime to, Pageable pageable);

    SocialMetricResponse findLatest(Long postId);

    SocialMetricResponse findById(Long id);

    SocialMetricResponse create(SocialMetricRequest request);

    void delete(Long id);
}
