package com.aryan.flash_sale_gateway;

import org.springframework.web.bind.annotation.*;

@RestController
public class TokenController {

    private final JwtUtil jwtUtil;

    public TokenController(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @GetMapping("/token")
    public String getToken(@RequestParam String username) {
        return jwtUtil.generateToken(username);
    }

}