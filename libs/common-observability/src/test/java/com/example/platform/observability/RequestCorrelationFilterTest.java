package com.example.platform.observability;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RequestCorrelationFilterTest {

    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

    @Test
    void preservesSafeCorrelationIdAndScopesItToTheRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/test");
        request.addHeader(CorrelationIds.HEADER, "request-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) ->
                assertThat(MDC.get(CorrelationIds.MDC_KEY))
                        .isEqualTo("request-123"));

        assertThat(response.getHeader(CorrelationIds.HEADER))
                .isEqualTo("request-123");
        assertThat(MDC.get(CorrelationIds.MDC_KEY)).isNull();
    }

    @Test
    void replacesUnsafeCorrelationIdWithUuid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/test");
        request.addHeader(CorrelationIds.HEADER, "unsafe value");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String generated = response.getHeader(CorrelationIds.HEADER);
        assertThat(generated).isNotBlank().isNotEqualTo("unsafe value");
        assertThat(UUID.fromString(generated).toString()).isEqualTo(generated);
    }

    @Test
    void acceptsLegacyRequestIdButReturnsCanonicalHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/test");
        request.addHeader(CorrelationIds.LEGACY_HEADER, "legacy-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationIds.HEADER)).isEqualTo("legacy-123");
        assertThat(response.getHeader(CorrelationIds.LEGACY_HEADER)).isNull();
    }
}
