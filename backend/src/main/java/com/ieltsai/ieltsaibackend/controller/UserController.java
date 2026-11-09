package com.ieltsai.ieltsaibackend.controller;

import com.ieltsai.ieltsaibackend.dto.RegisterUserRequest;
import com.ieltsai.ieltsaibackend.entity.User;
import com.ieltsai.ieltsaibackend.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public String registerUser(@Valid @RequestBody RegisterUserRequest request) {
        User user = new User();
        user.setUsername(request.username());
        user.setEmail(request.email());
        user.setPassword(request.password());
        userService.registerUser(user);
        return "User registered successfully";
    }

}
