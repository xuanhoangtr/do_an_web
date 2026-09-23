package com.utewedding.app.config;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorViewResolver;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.ModelAndView;

/** Servlet error dispatches and controller exceptions share the same status-specific layouts. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StatusErrorViewResolver implements ErrorViewResolver {
    @Override
    public ModelAndView resolveErrorView(HttpServletRequest request, HttpStatus status, Map<String, Object> model) {
        if (status != HttpStatus.FORBIDDEN && status != HttpStatus.NOT_FOUND) return null;
        var view = new ModelAndView("error/" + status.value(), model);
        view.setStatus(status);
        return view;
    }
}
