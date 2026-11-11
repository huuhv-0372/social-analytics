package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.dto.request.PostRequest;
import com.huuhv.socialanalytics.entity.Post;
import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.enums.Provider;
import com.huuhv.socialanalytics.repository.PostRepository;
import com.huuhv.socialanalytics.util.UtcDateTimes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Ghi hàng loạt bài viết đã validate (dùng cho import). Tách khỏi {@link PostImportService} để transaction chỉ bao
 * bước ghi DB, không giữ connection trong lúc parse file.
 */
@Service
@RequiredArgsConstructor
public class PostUpsertService {

    private final PostRepository postRepository;
    private final ImportHistoryService importHistoryService;

    /**
     * Lưu kết quả <b>1 lần import file</b>: tạo mới hoặc cập nhật theo (provider, externalId) trong phạm vi bài của
     * {@code owner}. Bài đã có chỉ được ghi các giá trị khác null: ô Content / Published At để trống giữ nguyên dữ liệu
     * cũ (URL luôn bắt buộc).
     * <p>
     * Chỉ dùng cho luồng import — mỗi lần gọi là 1 lần import: cùng transaction ghi lịch sử import
     * ({@link ImportHistoryService#record}) → message {@code IMPORT_COMPLETED} được publish sau khi bài viết và lịch sử
     * cùng commit, và được cộng vào thống kê import của user. Lỗi ghi bài → rollback cả 2, không có message. Tính năng
     * ghi bài viết khác (không phải import) không được gọi method này.
     *
     * @param requests  đã validate, không trùng (provider, externalId)
     * @param totalRows tổng số dòng dữ liệu trong file (gồm cả dòng lỗi không nằm trong {@code requests}), &gt; 0
     * @throws IllegalArgumentException khi {@code totalRows} ≤ 0 hoặc nhỏ hơn số bài cần ghi
     */
    @Transactional
    public Result saveImport(User owner, List<PostRequest> requests, int totalRows) {
        if (totalRows <= 0 || requests.size() > totalRows) {
            throw new IllegalArgumentException("Invalid import row count");
        }
        Map<Provider, List<PostRequest>> byProvider = requests.stream()
                .collect(Collectors.groupingBy(PostRequest::provider, LinkedHashMap::new, Collectors.toList()));
        List<Post> toSave = new ArrayList<>();
        int created = 0;
        int updated = 0;
        for (Map.Entry<Provider, List<PostRequest>> entry : byProvider.entrySet()) {
            Provider provider = entry.getKey();
            List<String> externalIds = entry.getValue().stream().map(PostRequest::externalId).toList();
            // Query lọc theo owner → chỉ upsert bài của chính user, không đụng bài user khác
            Map<String, Post> existing = postRepository
                    .findByUserIdAndProviderAndExternalIdIn(owner.getId(), provider, externalIds).stream()
                    .collect(Collectors.toMap(post -> matchKey(provider, post.getExternalId()), Function.identity()));
            for (PostRequest request : entry.getValue()) {
                Post post = existing.get(matchKey(provider, request.externalId()));
                if (post == null) {
                    post = Post.builder().user(owner).provider(provider).externalId(request.externalId()).build();
                    created++;
                } else {
                    updated++;
                }
                post.setUrl(request.url());
                if (request.content() != null) {
                    post.setContent(request.content());
                }
                if (request.publishedAt() != null) {
                    post.setPublishedAt(UtcDateTimes.toUtc(request.publishedAt()));
                }
                toSave.add(post);
            }
        }
        postRepository.saveAll(toSave);
        // Nơi duy nhất tính số dòng lỗi: response API, lịch sử import và thống kê dùng chung giá trị này
        int failed = totalRows - created - updated;
        long importId = importHistoryService.record(owner, totalRows, created, updated, failed);
        return new Result(importId, created, updated, failed);
    }

    /**
     * @param importId id lịch sử import ({@code import_histories})
     * @param failed   số dòng không được lưu (lỗi chuyển kiểu / validate / trùng trong file)
     */
    public record Result(long importId, int created, int updated, int failed) {
    }

    /** Collation của MySQL (utf8mb4_unicode_ci) không phân biệt hoa thường → so khớp externalId cũng vậy. */
    static String matchKey(Provider provider, String externalId) {
        return provider + ":" + externalId.toLowerCase(Locale.ROOT);
    }
}
