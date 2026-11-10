package com.huuhv.socialanalytics.entity;

import com.huuhv.socialanalytics.enums.Provider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Bài viết trên mạng xã hội. Metric lấy qua {@code SocialMetricRepository}, không map @OneToMany ngược.
 */
@Entity
@Table(name = "posts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Post extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Chủ sở hữu — Service phải kiểm tra trước mọi thao tác theo id (chống IDOR)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    private Provider provider;

    @Column(name = "external_id", nullable = false, length = 100)
    private String externalId;

    @Column(name = "url", nullable = false, length = 500)
    private String url;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    // Giờ UTC (util/UtcDateTimes)
    @Column(name = "published_at")
    private LocalDateTime publishedAt;
}
