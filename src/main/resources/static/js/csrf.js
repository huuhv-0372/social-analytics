// Đọc CSRF token từ <meta name="_csrf"> / <meta name="_csrf_header"> trong layout và trả header để gửi kèm fetch.
// Dùng: fetch(url, { method: 'POST', headers: { ...csrfHeaders(), 'Content-Type': 'application/json' }, body })
(function (global) {
    'use strict';

    function meta(name) {
        const element = document.querySelector('meta[name="' + name + '"]');
        return element && element.content ? element.content : null;
    }

    // Token đã mask render cùng trang — đủ cho fetch/form trong vòng đời của trang
    function csrfHeaders() {
        const token = meta('_csrf');
        const header = meta('_csrf_header');
        if (!token || !header) {
            return {};
        }
        return { [header]: token };
    }

    // Token raw hiện tại trong cookie (tên ở <meta name="_csrf_cookie">): dùng khi kết nối lại WebSocket sau khi
    // cookie đã đổi (đăng nhập lại ở tab khác) — token trong <meta> lúc đó đã hết hiệu lực. Không có cookie → csrfHeaders().
    function currentCsrfHeaders() {
        const header = meta('_csrf_header');
        const cookieName = meta('_csrf_cookie');
        if (header && cookieName) {
            const prefix = cookieName + '=';
            const cookie = document.cookie.split(';').map((part) => part.trim())
                .find((part) => part.startsWith(prefix));
            if (cookie && cookie.length > prefix.length) {
                try {
                    return { [header]: decodeURIComponent(cookie.substring(prefix.length)) };
                } catch (e) {
                    // Cookie sai định dạng (bị ghi đè từ subdomain / extension) → dùng token trong <meta>
                    console.warn('Malformed CSRF cookie, falling back to the page token');
                }
            }
        }
        return csrfHeaders();
    }

    global.csrfHeaders = csrfHeaders;
    global.currentCsrfHeaders = currentCsrfHeaders;
})(window);
