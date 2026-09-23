package com.utewedding.app.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorViewResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.ModelAndView;

/** Supplies the browser-specific 429 page while leaving other Boot errors unchanged. */
@Component
public class RateLimitErrorViewResolver implements ErrorViewResolver {
    @Override
    public ModelAndView resolveErrorView(HttpServletRequest request, HttpStatus status,
                                         Map<String, Object> model) {
        if (status.value() != 429) return null;
        Map<String, Object> attributes = new HashMap<>(model);
        Object retryAfter = request.getAttribute("rateLimitRetryAfter");
        if (retryAfter != null) attributes.put("retryAfter", retryAfter);
        return new ModelAndView("error/429", attributes, status);
    }
}
