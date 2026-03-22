package com.codflow.backend.team.dto;

import com.codflow.backend.team.enums.Role;
import jakarta.validation.constraints.Email;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateUserRequest {
    private String firstName;
    private String lastName;
    @Email
    private String email;
    private String phone;
    private Role role;
    private Boolean active;
    private String newPassword;
}
