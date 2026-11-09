package com.ieltsai.ieltsaibackend.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class User {

    private Long id;

    private String username;

    private String email;

    private String password;

    private LocalDateTime createdAt;
}
