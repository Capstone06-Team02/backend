package capstone2.voisk.exception;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleBadJson(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
				.contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", "요청 형식이 올바르지 않습니다."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArg(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
				.contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleAll(Exception e, HttpServletRequest request) {
		log.error("[GlobalExceptionHandler] {} {}, cause: {}",
			request.getMethod(), request.getRequestURI(), e.getMessage(), e);

        return ResponseEntity.internalServerError()
				.contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", "서버 오류가 발생했습니다."));
    }
}
