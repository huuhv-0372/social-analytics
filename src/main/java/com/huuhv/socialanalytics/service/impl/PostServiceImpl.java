package com.huuhv.socialanalytics.service.impl;

import com.huuhv.socialanalytics.dto.request.PostRequest;
import com.huuhv.socialanalytics.dto.response.PageResponse;
import com.huuhv.socialanalytics.dto.response.PostResponse;
import com.huuhv.socialanalytics.entity.Post;
import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.exception.BadRequestException;
import com.huuhv.socialanalytics.exception.DuplicateResourceException;
import com.huuhv.socialanalytics.exception.ResourceNotFoundException;
import com.huuhv.socialanalytics.repository.PostRepository;
import com.huuhv.socialanalytics.service.CurrentUserService;
import com.huuhv.socialanalytics.service.PostService;
import com.huuhv.socialanalytics.util.PageableUtils;
import com.huuhv.socialanalytics.util.SocialUrlPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostServiceImpl implements PostService {

    private static final Set<String> SORTABLE_PROPERTIES = Set.of("createdAt", "updatedAt", "publishedAt", "provider");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");
    private static final String RESOURCE = "Post";

    private final PostRepository postRepository;
    private final CurrentUserService currentUserService;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PostResponse> findAll(Pageable pageable) {
        Long userId = currentUserService.getCurrentUser().getId();
        Pageable safePageable = PageableUtils.withAllowedSort(pageable, SORTABLE_PROPERTIES, DEFAULT_SORT);
        return PageResponse.from(postRepository.findByUserId(userId, safePageable).map(PostResponse::from));
    }

    @Override
    @Transactional(readOnly = true)
    public PostResponse findById(Long id) {
        return PostResponse.from(findOwnedPost(id));
    }

    @Override
    @Transactional
    public PostResponse create(PostRequest request) {
        User owner = currentUserService.getCurrentUser();
        validateUrl(request);
        if (postRepository.existsByProviderAndExternalId(request.provider(), request.externalId())) {
            throw new DuplicateResourceException("Post with this provider and externalId already exists");
        }
        Post post = Post.builder().user(owner).build();
        applyRequest(post, request);
        Post saved = postRepository.save(post);
        log.info("User {} created post {}", owner.getId(), saved.getId());
        return PostResponse.from(saved);
    }

    @Override
    @Transactional
    public PostResponse update(Long id, PostRequest request) {
        Post post = findOwnedPost(id);
        validateUrl(request);
        if (postRepository.existsByProviderAndExternalIdAndIdNot(request.provider(), request.externalId(), id)) {
            throw new DuplicateResourceException("Post with this provider and externalId already exists");
        }
        applyRequest(post, request);
        // flush để updatedAt (@UpdateTimestamp) có giá trị mới trong response
        return PostResponse.from(postRepository.saveAndFlush(post));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        Post post = findOwnedPost(id);
        // Snapshot social_metrics bị xoá theo nhờ FK ON DELETE CASCADE
        postRepository.delete(post);
        log.info("User {} deleted post {}", post.getUser().getId(), id);
    }

    private Post findOwnedPost(Long id) {
        Long userId = currentUserService.getCurrentUser().getId();
        return postRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE));
    }

    private static void validateUrl(PostRequest request) {
        if (!SocialUrlPolicy.isAllowed(request.provider(), request.url())) {
            throw new BadRequestException("url must be an https link on a domain of the selected provider");
        }
    }

    private static void applyRequest(Post post, PostRequest request) {
        post.setProvider(request.provider());
        post.setExternalId(request.externalId());
        post.setUrl(request.url());
        post.setContent(request.content());
        post.setPublishedAt(request.publishedAt());
    }
}
