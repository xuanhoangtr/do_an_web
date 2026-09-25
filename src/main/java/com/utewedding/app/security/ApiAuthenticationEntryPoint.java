package com.utewedding.app.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** Returns a stable JSON 401 for API clients instead of an HTML login redirect. */
@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String EMAIL_NOT_VERIFIED_ATTRIBUTE =
            JwtAuthenticationFilter.EMAIL_NOT_VERIFIED_REQUEST_ATTRIBUTE;

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception) throws IOException, ServletException {
        if (Boolean.TRUE.equals(request.getAttribute(EMAIL_NOT_VERIFIED_ATTRIBUTE))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"status\":403,\"error\":\"Email Not Verified\","
                    + "\"message\":\"EMAIL_NOT_VERIFIED: Vui lòng xác thực email trước khi tiếp tục.\"}");
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"Yêu cầu xác thực hợp lệ.\"}");
    }
}
