// Đọc CSRF token từ <meta name="_csrf"> / <meta name="_csrf_header"> trong layout và trả header để gửi kèm fetch.
// Dùng: fetch(url, { method: 'POST', headers: { ...csrfHeaders(), 'Content-Type': 'application/json' }, body })
(function (global) {
    'use strict';

    function csrfHeaders() {
        const token = document.querySelector('meta[name="_csrf"]');
        const header = document.querySelector('meta[name="_csrf_header"]');
        if (!token || !header || !token.content || !header.content) {
            return {};
        }
        return { [header.content]: token.content };
    }

    global.csrfHeaders = csrfHeaders;
})(window);
