package com.parul.llmgateway.tenant;

import java.io.IOException;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String TENANT_ID_ATTRIBUTE = "tenantId";

    private final ApiKeyService apiKeyService;

    public ApiKeyAuthFilter(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String apiKey = request.getHeader("X-API-Key");
        if (apiKey == null || apiKey.isBlank()) {
            writeUnauthorized(request, response, "Missing API key");
            return;
        }

        Optional<Long> tenantId = apiKeyService.findTenantIdForKey(apiKey);
        if (tenantId.isEmpty()) {
            writeUnauthorized(request, response, "Invalid or revoked API key");
            return;
        }

        request.setAttribute(TENANT_ID_ATTRIBUTE, tenantId.get());
        chain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletRequest request, HttpServletResponse response,
                                   String detail) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/problem+json");
        response.getWriter().write(
                "{\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"" + detail
                        + "\",\"instance\":\"" + request.getRequestURI() + "\"}");
    }
}