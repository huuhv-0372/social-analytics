package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.dto.request.PostRequest;
import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.enums.Provider;
import com.huuhv.socialanalytics.repository.PostRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostUpsertServiceTest {

    @Mock
    private PostRepository postRepository;

    @Mock
    private ImportHistoryService importHistoryService;

    @InjectMocks
    private PostUpsertService postUpsertService;

    private final User owner = User.builder().id(7L).build();

    @Test
    void failedRowsAreComputedOnceAndRecordedWithTheSameValue() {
        when(postRepository.findByUserIdAndProviderAndExternalIdIn(anyLong(), eq(Provider.FACEBOOK), anyCollection()))
                .thenReturn(List.of());
        when(importHistoryService.record(owner, 5, 2, 0, 3)).thenReturn(42L);

        PostUpsertService.Result result = postUpsertService.saveImport(owner,
                List.of(request("fb-1"), request("fb-2")), 5);

        assertThat(result).isEqualTo(new PostUpsertService.Result(42L, 2, 0, 3));
        verify(importHistoryService).record(owner, 5, 2, 0, 3);
    }

    @Test
    void rejectsCountsThatAreNotAnImport() {
        // 0 dòng dữ liệu không phải 1 lần import; số bài ghi không thể vượt số dòng của file
        assertThatThrownBy(() -> postUpsertService.saveImport(owner, List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> postUpsertService.saveImport(owner, List.of(request("fb-1"), request("fb-2")), 1))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(postRepository, importHistoryService);
    }

    private static PostRequest request(String externalId) {
        return new PostRequest(Provider.FACEBOOK, externalId, "https://facebook.com/posts/" + externalId, null, null);
    }
}
