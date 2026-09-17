package com.utewedding.app.controller;

import com.utewedding.app.service.web.FormToken;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

@Component
public class WebConfirm {
    private final FormToken tokens;
    public WebConfirm(FormToken tokens){this.tokens=tokens;}
    public boolean ready(HttpServletRequest request,Model model,String scope,String token,boolean confirmed,
                         Map<String,String> fields,String heading,Object summary) {
        if(fields.size()>16 || fields.values().stream().anyMatch(value->value==null||value.length()>2000)
                ||fields.values().stream().mapToInt(String::length).sum()>8000
                ||fields.keySet().stream().anyMatch(name->name.toLowerCase(Locale.ROOT).contains("password")))
            throw new IllegalArgumentException("Nội dung xác nhận không hợp lệ.");
        if(tokens.confirmed(request,scope,token,fields,confirmed))return true;
        model.addAttribute("heading",heading);model.addAttribute("summary",summary);
        model.addAttribute("fields",fields);model.addAttribute("token",token);
        model.addAttribute("target",request.getRequestURI().substring(request.getContextPath().length()));
        return false;
    }
}
