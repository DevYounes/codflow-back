package com.codflow.backend.team.entity;

import com.codflow.backend.common.entity.BaseEntity;
import com.codflow.backend.salary.enums.CommissionType;
import com.codflow.backend.salary.enums.SalaryType;
import com.codflow.backend.team.enums.Role;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

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

    // ----- Configuration salariale -----

    @Enumerated(EnumType.STRING)
    @Column(name = "salary_type", nullable = false, length = 30)
    private SalaryType salaryType = SalaryType.FIXE;

    @Column(name = "fixed_salary", precision = 12, scale = 2)
    private BigDecimal fixedSalary;

    @Enumerated(EnumType.STRING)
    @Column(name = "commission_type", length = 30)
    private CommissionType commissionType;

    @Column(name = "commission_per_confirmed", precision = 10, scale = 2)
    private BigDecimal commissionPerConfirmed;

    @Column(name = "commission_per_delivered", precision = 10, scale = 2)
    private BigDecimal commissionPerDelivered;

    public String getFullName() {
        return firstName + " " + lastName;
    }
}
