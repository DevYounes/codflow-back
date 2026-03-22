package com.codflow.backend.team.entity;

import com.codflow.backend.common.entity.BaseEntity;
import com.codflow.backend.team.enums.Role;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "users")
public class User extends BaseEntity {

    @Column(unique = true, nullable = false, length = 50)
    private String username;

    @Column(unique = true, nullable = false, length = 100)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role = Role.AGENT;

    @Column(length = 20)
    private String phone;

    @Column(nullable = false)
    private boolean active = true;

    public String getFullName() {
        return firstName + " " + lastName;
    }
}
