package com.huuhv.socialanalytics.controller.api;

import com.huuhv.socialanalytics.dto.request.SocialMetricRequest;
import com.huuhv.socialanalytics.dto.response.PageResponse;
import com.huuhv.socialanalytics.dto.response.SocialMetricResponse;
import com.huuhv.socialanalytics.service.SocialMetricService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.LocalDateTime;

/**
 * Snapshot metric là bất biến (mỗi lần crawl 1 bản ghi) nên không có PUT.
 */
@Tag(name = "Metrics", description = "Snapshot metric theo thời gian của bài viết")
@RestController
@RequestMapping("/api/metrics")
@RequiredArgsConstructor
public class SocialMetricApiController {

    private final SocialMetricService socialMetricService;

    @Operation(summary = "Danh sách snapshot của 1 bài viết, lọc theo khoảng thời gian (ISO-8601, tuỳ chọn)")
    @GetMapping
    public PageResponse<SocialMetricResponse> list(
            @RequestParam @Positive Long postId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @ParameterObject Pageable pageable) {
        return socialMetricService.findByPost(postId, from, to, pageable);
    }

    @Operation(summary = "Snapshot mới nhất của 1 bài viết")
    @GetMapping("/latest")
    public SocialMetricResponse latest(@RequestParam @Positive Long postId) {
        return socialMetricService.findLatest(postId);
    }

    @Operation(summary = "Chi tiết snapshot")
    @GetMapping("/{id}")
    public SocialMetricResponse get(@PathVariable Long id) {
        return socialMetricService.findById(id);
    }

    @Operation(summary = "Ghi nhận snapshot thủ công")
    @PostMapping
    public ResponseEntity<SocialMetricResponse> create(@Valid @RequestBody SocialMetricRequest request) {
        SocialMetricResponse created = socialMetricService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @Operation(summary = "Xoá snapshot")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        socialMetricService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
