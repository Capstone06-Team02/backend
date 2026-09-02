package capstone2.voisk.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

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

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> handleNoResource(NoResourceFoundException e, HttpServletRequest request) {
        log.warn("[GlobalExceptionHandler] {} {}, cause: {}",
            request.getMethod(), request.getRequestURI(), e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", "요청한 리소스를 찾을 수 없습니다."));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(
            ResponseStatusException e,
            HttpServletRequest request
    ) {
        log.warn("[GlobalExceptionHandler] {} {}, status: {}, cause: {}",
                request.getMethod(), request.getRequestURI(), e.getStatusCode(), e.getReason());
        String message = e.getReason() == null || e.getReason().isBlank()
                ? "요청을 처리하지 못했습니다."
                : e.getReason();
        return ResponseEntity.status(e.getStatusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", message));
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
