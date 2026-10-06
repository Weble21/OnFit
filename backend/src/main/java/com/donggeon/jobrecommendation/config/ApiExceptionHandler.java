package com.donggeon.jobrecommendation.config;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Returns RFC 9457 problem details whose {@code detail} the frontend can show as is.
 * ResponseStatusException reasons pass through the base handler unchanged.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> handleConflict(DataIntegrityViolationException ex, WebRequest request) {
        var body = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "동시에 변경된 데이터가 있습니다. 최신 상태를 확인한 뒤 다시 시도해 주세요.");
        return handleExceptionInternal(ex, body, new HttpHeaders(), HttpStatus.CONFLICT, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        // Exception messages (including SQL or submitted text) can contain personal data and secrets.
        log.error("api_failure exception_type={}", ex.getClass().getName());
        var body = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.");
        body.setTitle("서버 오류");
        return handleExceptionInternal(ex, body, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        var response = super.handleExceptionInternal(ex, body, headers, status, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem && MDC.get("requestId") != null) {
            problem.setProperty("requestId", MDC.get("requestId"));
        }
        return response;
    }

    private static final Map<String, String> FIELD_LABELS = Map.ofEntries(
            Map.entry("targetRoles", "희망 직무"),
            Map.entry("preferredLocations", "희망 지역"),
            Map.entry("skills", "기술"),
            Map.entry("certificates", "자격증"),
            Map.entry("projects", "프로젝트"),
            Map.entry("experiences", "경력"),
            Map.entry("name", "이름"),
            Map.entry("description", "설명"),
            Map.entry("techStack", "기술 스택"),
            Map.entry("projectUrl", "프로젝트 URL"),
            Map.entry("companyName", "회사명"),
            Map.entry("roleName", "담당 직무"),
            Map.entry("startedOn", "시작일"),
            Map.entry("endedOn", "종료일")
    );

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        FieldError error = ex.getBindingResult().getFieldError();
        String detail = error == null
                ? "입력값을 확인해 주세요."
                : fieldLabel(error.getField()) + " 입력값을 확인해 주세요.";
        ex.getBody().setTitle("잘못된 요청");
        ex.getBody().setDetail(detail);
        return handleExceptionInternal(ex, ex.getBody(), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex,
                                                        HttpHeaders headers, HttpStatusCode status,
                                                        WebRequest request) {
        String detail = "요청 경로의 값 형식을 확인해 주세요.";
        if (ex instanceof MethodArgumentTypeMismatchException argument && argument.getRequiredType() == Long.class) {
            detail = switch (argument.getName()) {
                case "jobId" -> "공고 ID는 숫자로 입력해 주세요.";
                case "recommendationId" -> "추천 ID는 숫자로 입력해 주세요.";
                default -> detail;
            };
        }
        var body = createProblemDetail(ex, status, detail, null, null, request);
        body.setTitle("잘못된 요청");
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex,
                                                                          HttpHeaders headers, HttpStatusCode status,
                                                                          WebRequest request) {
        var body = createProblemDetail(ex, status, "10MB 이하의 파일을 선택해 주세요.", null, null, request);
        body.setTitle("파일 크기 초과");
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        var body = createProblemDetail(ex, status, "요청 형식이 올바르지 않습니다. 날짜와 목록 형식을 확인해 주세요.",
                null, null, request);
        body.setTitle("잘못된 요청");
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    private static String fieldLabel(String path) {
        int dot = path.indexOf('.');
        String parent = dot < 0 ? path : path.substring(0, dot);
        int bracket = parent.indexOf('[');
        if (bracket >= 0) {
            String label = FIELD_LABELS.getOrDefault(parent.substring(0, bracket), "입력 항목");
            String index = parent.substring(bracket + 1, parent.indexOf(']', bracket));
            try {
                label += " " + (Integer.parseInt(index) + 1) + "번";
            } catch (NumberFormatException ignored) {
                // A malformed index should not expose the internal field path.
            }
            return dot < 0 ? label : label + "의 " + FIELD_LABELS.getOrDefault(path.substring(dot + 1), "입력 항목");
        }
        return FIELD_LABELS.getOrDefault(parent, "입력 항목");
    }
}
