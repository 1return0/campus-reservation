package com.campusreserve.reservation.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.campusreserve.reservation.domain.ResourceStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@TableName("reservable_resource")
public class ReservableResourceEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String resourceCode;
    private String resourceName;
    private String resourceType;
    private Integer noShowGraceMinutes;
    private Integer minParticipants;
    private Integer maxParticipants;
    private ResourceStatus status;
    @Version
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
