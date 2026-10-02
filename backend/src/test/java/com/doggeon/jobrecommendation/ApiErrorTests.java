package com.doggeon.jobrecommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.doggeon.jobrecommendation.config.ApiExceptionHandler;
import com.doggeon.jobrecommendation.config.RequestLogFilter;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

class ApiErrorTests {
    @Test
    void unexpectedErrorsHideSecretsAndUseServerGeneratedRequestId() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestLogFilter()).build();
        var result = mvc.perform(get("/failure").header("X-Request-ID", "untrusted-client-id"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().exists("X-Request-ID"))
                .andExpect(jsonPath("$.detail").value("서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."))
                .andReturn().getResponse();
        assertThat(result.getContentAsString()).doesNotContain("secret-password", "untrusted-client-id");
        assertThat(JsonPath.<String>read(result.getContentAsString(), "$.requestId"))
                .isEqualTo(result.getHeader("X-Request-ID"));
        mvc.perform(get("/missing")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("공고를 찾을 수 없습니다."))
                .andExpect(jsonPath("$.requestId").isString());
    }

    @RestController
    @TestComponent
    static class FailingController {
        @GetMapping("/failure")
        String fail() { throw new IllegalStateException("secret-password"); }

        @GetMapping("/missing")
        String missing() { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "공고를 찾을 수 없습니다."); }
    }
}
