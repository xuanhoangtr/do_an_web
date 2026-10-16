package com.utewedding.app.controller;
import com.utewedding.app.service.RequestContext;import com.utewedding.app.service.impl.ShippingWebhookService;import jakarta.servlet.http.HttpServletRequest;import java.util.*;import org.springframework.http.*;import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/shipping/providers") public class ShippingWebhookController{
 private final ShippingWebhookService service;public ShippingWebhookController(ShippingWebhookService s){service=s;}
 @PostMapping(value="/{provider}/webhook",consumes=MediaType.ALL_VALUE) public ResponseEntity<ShippingWebhookService.Result> webhook(@PathVariable String provider,@RequestHeader HttpHeaders headers,@RequestBody byte[] body,HttpServletRequest request){Map<String,String> flattened=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);headers.forEach((k,v)->flattened.put(k,String.join(",",v)));return ResponseEntity.ok(service.receive(provider,flattened,body,new RequestContext(request.getRemoteAddr(),request.getHeader("User-Agent"),request.getHeader("X-Request-ID"),"SYSTEM")));}
}
