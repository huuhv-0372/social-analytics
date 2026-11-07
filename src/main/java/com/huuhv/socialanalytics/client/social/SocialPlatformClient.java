package com.huuhv.socialanalytics.client.social;

import com.huuhv.socialanalytics.enums.Provider;

import java.util.List;

/**
 * Client lấy số liệu tương tác của bài viết từ 1 mạng xã hội. Hiện chỉ có bản mock ({@link FacebookMockClient},
 * {@link TwitterMockClient}); client thật là 1 implementation khác của interface này, caller không phải đổi.
 * Chọn implementation theo {@link Provider} qua {@link SocialPlatformClients}.
 * <p>
 * Quy ước cho mọi implementation:
 * <ul>
 *     <li>{@code accessToken} là bản rõ — <b>không bao giờ</b> được log hay đưa vào message của exception.</li>
 *     <li>Gọi mạng phải có connect/read timeout (CLAUDE.md A10).</li>
 *     <li>Kết quả là dữ liệu không tin cậy: caller validate trước khi lưu (OWASP A08).</li>
 * </ul>
 */
public interface SocialPlatformClient {

    /** Số id tối đa mỗi lần gọi (giới hạn phổ biến của API mạng xã hội, vd X lookup tối đa 100 tweet). */
    int MAX_IDS_PER_REQUEST = 100;

    Provider provider();

    /**
     * Số liệu <b>hiện tại</b> (giá trị tuyệt đối, không phải phần chênh lệch) của các bài viết.
     *
     * @param accessToken token của tài khoản sở hữu bài viết
     * @param externalIds id bài viết phía provider, tối đa {@link #MAX_IDS_PER_REQUEST}
     * @return số liệu của các bài tìm thấy; bài không tồn tại / không truy cập được bị bỏ khỏi kết quả
     * @throws InvalidAccessTokenException token rỗng, hết hạn hoặc bị thu hồi — user cần đăng nhập lại
     * @throws SocialPlatformException     lỗi khác khi gọi API (timeout, lỗi phía provider...)
     * @throws IllegalArgumentException    quá {@link #MAX_IDS_PER_REQUEST} id
     */
    List<PostMetrics> fetchMetrics(String accessToken, List<String> externalIds);
}
