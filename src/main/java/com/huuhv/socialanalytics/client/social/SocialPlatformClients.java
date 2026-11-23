package com.huuhv.socialanalytics.client.social;

import com.huuhv.socialanalytics.enums.Provider;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Chọn {@link SocialPlatformClient} theo {@link Provider}. Mỗi provider phải có đúng 1 client: thiếu hoặc trùng
 * → app không khởi động (fail fast), không để lỗi lộ ra lúc job đang chạy.
 */
@Component
public class SocialPlatformClients {

    private final Map<Provider, SocialPlatformClient> clients;

    public SocialPlatformClients(List<SocialPlatformClient> clients) {
        Map<Provider, SocialPlatformClient> byProvider = new EnumMap<>(Provider.class);
        for (SocialPlatformClient client : clients) {
            if (byProvider.putIfAbsent(client.provider(), client) != null) {
                throw new IllegalStateException("More than one SocialPlatformClient for " + client.provider());
            }
        }
        for (Provider provider : Provider.values()) {
            if (!byProvider.containsKey(provider)) {
                throw new IllegalStateException("No SocialPlatformClient for " + provider);
            }
        }
        this.clients = Collections.unmodifiableMap(byProvider);
    }

    public SocialPlatformClient get(Provider provider) {
        return clients.get(Objects.requireNonNull(provider, "provider"));
    }
}
