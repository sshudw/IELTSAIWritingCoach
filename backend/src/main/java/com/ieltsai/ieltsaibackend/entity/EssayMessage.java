package com.ieltsai.ieltsaibackend.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class EssayMessage {

    private Long id;

    private Long essayId;

    private String role;

    private String content;

    private LocalDateTime createdAt;
}
