package com.huuhv.socialanalytics.service.impl;

import com.huuhv.socialanalytics.dto.request.SocialMetricRequest;
import com.huuhv.socialanalytics.dto.response.PageResponse;
import com.huuhv.socialanalytics.dto.response.SocialMetricResponse;
import com.huuhv.socialanalytics.entity.Post;
import com.huuhv.socialanalytics.entity.SocialMetric;
import com.huuhv.socialanalytics.exception.BadRequestException;
import com.huuhv.socialanalytics.exception.ResourceNotFoundException;
import com.huuhv.socialanalytics.repository.PostRepository;
import com.huuhv.socialanalytics.repository.SocialMetricRepository;
import com.huuhv.socialanalytics.service.CurrentUserService;
import com.huuhv.socialanalytics.service.SocialMetricService;
import com.huuhv.socialanalytics.util.PageableUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class SocialMetricServiceImpl implements SocialMetricService {

    private static final Set<String> SORTABLE_PROPERTIES = Set.of("collectedAt", "likes", "shares", "comments", "followers");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "collectedAt");

    private final SocialMetricRepository socialMetricRepository;
    private final PostRepository postRepository;
    private final CurrentUserService currentUserService;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<SocialMetricResponse> findByPost(Long postId, LocalDateTime from, LocalDateTime to,
                                                         Pageable pageable) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BadRequestException("from must not be after to");
        }
        Post post = findOwnedPost(postId);
        Pageable safePageable = PageableUtils.withAllowedSort(pageable, SORTABLE_PROPERTIES, DEFAULT_SORT);
        return PageResponse.from(socialMetricRepository.search(post.getId(), from, to, safePageable)
                .map(SocialMetricResponse::from));
    }

    @Override
    @Transactional(readOnly = true)
    public SocialMetricResponse findLatest(Long postId) {
        Post post = findOwnedPost(postId);
        return socialMetricRepository.findTopByPostIdOrderByCollectedAtDesc(post.getId())
                .map(SocialMetricResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Metric"));
    }

    @Override
    @Transactional(readOnly = true)
    public SocialMetricResponse findById(Long id) {
        return SocialMetricResponse.from(findOwnedMetric(id));
    }

    @Override
    @Transactional
    public SocialMetricResponse create(SocialMetricRequest request) {
        Post post = findOwnedPost(request.postId());
        SocialMetric saved = socialMetricRepository.save(SocialMetric.builder()
                .post(post)
                .likes(request.likes())
                .shares(request.shares())
                .comments(request.comments())
                .followers(request.followers())
                .collectedAt(request.collectedAt())
                .build());
        return SocialMetricResponse.from(saved);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        SocialMetric metric = findOwnedMetric(id);
        socialMetricRepository.delete(metric);
        log.info("Deleted metric {} of post {}", id, metric.getPost().getId());
    }

    private Post findOwnedPost(Long postId) {
        Long userId = currentUserService.getCurrentUser().getId();
        return postRepository.findByIdAndUserId(postId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Post"));
    }

    private SocialMetric findOwnedMetric(Long id) {
        Long userId = currentUserService.getCurrentUser().getId();
        return socialMetricRepository.findByIdAndPostUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Metric"));
    }
}
