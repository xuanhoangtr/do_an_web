package com.utewedding.app.controller;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.ModelAndView;

@Controller
public class AccessDeniedWebController {
    @RequestMapping("/access-denied")
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ModelAndView forbidden() {
        var view = new ModelAndView("error/403");
        view.addObject("status", 403);
        view.addObject("errorMessage", "Bạn không có quyền thực hiện thao tác này.");
        return view;
    }
}
