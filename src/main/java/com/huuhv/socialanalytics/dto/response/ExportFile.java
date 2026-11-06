package com.huuhv.socialanalytics.dto.response;

import java.io.IOException;
import java.io.OutputStream;

/**
 * File sinh ra để client tải về. Nội dung được ghi thẳng vào {@link OutputStream} của response khi controller stream
 * (không gom cả file vào {@code byte[]} trong heap). Mọi kiểm tra (quyền, giới hạn dòng, tần suất) đã chạy xong trước khi
 * tạo object này, nên lỗi nghiệp vụ vẫn trả về được mã lỗi đúng trước khi response bắt đầu gửi.
 *
 * @param filename tên file do server đặt (không lấy từ input người dùng)
 * @param content  ghi nội dung file vào stream; gọi đúng 1 lần
 */
public record ExportFile(String filename, Content content) {

    @FunctionalInterface
    public interface Content {
        void writeTo(OutputStream out) throws IOException;
    }
}
