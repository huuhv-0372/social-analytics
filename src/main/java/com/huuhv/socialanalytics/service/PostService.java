package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.dto.request.PostRequest;
import com.huuhv.socialanalytics.dto.response.PageResponse;
import com.huuhv.socialanalytics.dto.response.PostReportRow;
import com.huuhv.socialanalytics.dto.response.PostResponse;
import com.huuhv.socialanalytics.entity.Post;
import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.exception.BadRequestException;
import com.huuhv.socialanalytics.exception.DuplicateResourceException;
import com.huuhv.socialanalytics.exception.ResourceNotFoundException;
import com.huuhv.socialanalytics.repository.PostRepository;
import com.huuhv.socialanalytics.util.PageableUtils;
import com.huuhv.socialanalytics.util.SocialUrlPolicy;
import com.huuhv.socialanalytics.util.UtcDateTimes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * CRUD bài viết. Mọi thao tác chỉ áp dụng cho bài viết của user đang đăng nhập.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostService {

    private static final Set<String> SORTABLE_PROPERTIES = Set.of("createdAt", "updatedAt", "publishedAt", "provider");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");
    private static final String RESOURCE = "Post";

    private final PostRepository postRepository;
    private final CurrentUserService currentUserService;

    @Transactional(readOnly = true)
    public PageResponse<PostResponse> findAll(Pageable pageable) {
        Long userId = currentUserService.getCurrentUser().getId();
        Pageable safePageable = PageableUtils.withAllowedSort(pageable, SORTABLE_PROPERTIES, DEFAULT_SORT);
        return PageResponse.from(postRepository.findByUserId(userId, safePageable).map(PostResponse::from));
    }

    @Transactional(readOnly = true)
    public PostResponse findById(Long id) {
        return PostResponse.from(getOwnedPost(id));
    }

    @Transactional
    public PostResponse create(PostRequest request) {
        User owner = currentUserService.getCurrentUser();
        validateUrl(request);
        // Chỉ kiểm tra trùng trong bài của chính user — không lộ bài viết user khác đang theo dõi
        if (postRepository.existsByUserIdAndProviderAndExternalId(owner.getId(), request.provider(), request.externalId())) {
            throw new DuplicateResourceException("Post with this provider and externalId already exists");
        }
        Post post = Post.builder().user(owner).build();
        applyRequest(post, request);
        Post saved = postRepository.save(post);
        log.info("User {} created post {}", owner.getId(), saved.getId());
        return PostResponse.from(saved);
    }

    @Transactional
    public PostResponse update(Long id, PostRequest request) {
        Post post = getOwnedPost(id);
        validateUrl(request);
        if (postRepository.existsByUserIdAndProviderAndExternalIdAndIdNot(
                post.getUser().getId(), request.provider(), request.externalId(), id)) {
            throw new DuplicateResourceException("Post with this provider and externalId already exists");
        }
        applyRequest(post, request);
        // flush để updatedAt (@PreUpdate của AuditableEntity) có giá trị mới trong response
        return PostResponse.from(postRepository.saveAndFlush(post));
    }

    @Transactional
    public void delete(Long id) {
        Post post = getOwnedPost(id);
        // Snapshot social_metrics bị xoá theo nhờ FK ON DELETE CASCADE
        postRepository.delete(post);
        log.info("User {} deleted post {}", post.getUser().getId(), id);
    }

    /**
     * Bài viết thuộc user hiện tại — điểm kiểm tra quyền sở hữu duy nhất (chống IDOR), dùng chung cho service khác.
     * Trả Entity nên chỉ dùng trong tầng Service, không trả ra Controller.
     *
     * @throws ResourceNotFoundException khi không tồn tại hoặc thuộc user khác
     */
    @Transactional(readOnly = true)
    public Post getOwnedPost(Long id) {
        Long userId = currentUserService.getCurrentUser().getId();
        return postRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE));
    }

    /**
     * Như {@link #getOwnedPost(Long)} nhưng không load Entity — dùng khi chỉ cần xác nhận quyền sở hữu.
     */
    @Transactional(readOnly = true)
    public void checkOwnedPost(Long id) {
        Long userId = currentUserService.getCurrentUser().getId();
        if (!postRepository.existsByIdAndUserId(id, userId)) {
            throw new ResourceNotFoundException(RESOURCE);
        }
    }

    /**
     * Dữ liệu báo cáo export của owner (projection, không load Entity): mỗi bài kèm snapshot metric mới nhất.
     * {@code ownerId} lấy từ user đang đăng nhập ở Service gọi tới, không bao giờ từ client.
     */
    @Transactional(readOnly = true)
    public List<PostReportRow> findReportRows(Long ownerId, int limit) {
        return postRepository.findReportRows(ownerId, Limit.of(limit));
    }

    private static void validateUrl(PostRequest request) {
        if (!SocialUrlPolicy.isAllowed(request.provider(), request.url())) {
            throw new BadRequestException("url " + SocialUrlPolicy.VIOLATION_MESSAGE);
        }
    }

    private static void applyRequest(Post post, PostRequest request) {
        post.setProvider(request.provider());
        post.setExternalId(request.externalId());
        post.setUrl(request.url());
        post.setContent(request.content());
        post.setPublishedAt(UtcDateTimes.toUtc(request.publishedAt()));
    }
}
