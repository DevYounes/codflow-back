package com.codflow.backend.team.dto;

import com.codflow.backend.team.enums.Role;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class UserDto {
    private Long id;
    private String username;
    private String email;
    private String firstName;
    private String lastName;
    private String fullName;
    private Role role;
    private String phone;
    private boolean active;
    private LocalDateTime createdAt;
}
