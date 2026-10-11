package com.campusreserve.identity.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.campusreserve.identity.domain.CampusRole;
import com.campusreserve.identity.domain.IdentityUserStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@TableName("identity_user")
public class IdentityUserEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String username;
    private String studentNumber;
    private String passwordHash;
    private String displayName;
    private CampusRole roleCode;
    private IdentityUserStatus status;
    private Instant createdAt;
    private Instant updatedAt;
}
