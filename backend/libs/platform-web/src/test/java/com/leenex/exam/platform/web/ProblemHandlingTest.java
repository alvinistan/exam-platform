package com.leenex.exam.platform.web;

import static org.hamcrest.Matchers.hasLength;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class ProblemHandlingTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new SampleController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new CorrelationIdFilter())
                .build();
    }

    @Test
    void apiExceptionBecomesProblemWithCode() throws Exception {
        mvc.perform(get("/slug-taken"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("SLUG_TAKEN"))
                .andExpect(jsonPath("$.title").value("URL already in use"))
                .andExpect(jsonPath("$.type").value("https://leenexlabs.example/problems/slug-taken"))
                .andExpect(jsonPath("$.detail").value("ielts-academic is already used by another exam."))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void validationErrorsListEachField() throws Exception {
        mvc.perform(post("/items").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void malformedJsonIsAValidationProblem() throws Exception {
        mvc.perform(post("/items").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void unexpectedErrorsHideInternals() throws Exception {
        mvc.perform(get("/crash"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("database password"))));
    }

    @Test
    void wrongMethodHasCode() throws Exception {
        mvc.perform(post("/crash"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void wellFormedCorrelationIdIsEchoed() throws Exception {
        mvc.perform(get("/slug-taken").header(CorrelationIdFilter.HEADER, "req-12345678"))
                .andExpect(header().string(CorrelationIdFilter.HEADER, "req-12345678"))
                .andExpect(jsonPath("$.correlationId").value("req-12345678"));
    }

    @Test
    void unsafeCorrelationIdIsReplaced() throws Exception {
        mvc.perform(get("/slug-taken").header(CorrelationIdFilter.HEADER, "bad id\r\nX-Injected: 1"))
                .andExpect(header().string(CorrelationIdFilter.HEADER, hasLength(36)));
    }

    @RestController
    static class SampleController {

        record Item(@NotBlank String name) {
        }

        @GetMapping("/slug-taken")
        String slugTaken() {
            throw new ApiException(ProblemCode.SLUG_TAKEN, "ielts-academic is already used by another exam.");
        }

        @PostMapping("/items")
        String create(@Valid @RequestBody Item item) {
            return item.name();
        }

        @GetMapping("/crash")
        String crash() {
            throw new IllegalStateException("database password is hunter2");
        }
    }
}
