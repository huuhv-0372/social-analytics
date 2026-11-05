package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.dto.request.PostRequest;
import com.huuhv.socialanalytics.dto.response.PageResponse;
import com.huuhv.socialanalytics.dto.response.PostResponse;
import org.springframework.data.domain.Pageable;

/**
 * CRUD bài viết. Mọi thao tác chỉ áp dụng cho bài viết của user đang đăng nhập.
 */
public interface PostService {

    PageResponse<PostResponse> findAll(Pageable pageable);

    PostResponse findById(Long id);

    PostResponse create(PostRequest request);

    PostResponse update(Long id, PostRequest request);

    void delete(Long id);
}
