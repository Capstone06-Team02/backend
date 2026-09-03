package capstone2.voisk.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void 추천_서비스의_503_상태와_안내문을_그대로_응답한다() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/recommend");
        ResponseStatusException exception = new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "추천을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."
        );

        var response = handler.handleResponseStatus(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry(
                "error",
                "추천을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."
        );
    }
}
