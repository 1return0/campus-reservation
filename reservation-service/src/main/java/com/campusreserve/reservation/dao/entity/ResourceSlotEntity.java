package com.campusreserve.reservation.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.campusreserve.reservation.domain.SlotStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@TableName("resource_slot")
public class ResourceSlotEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long resourceId;
    private LocalDateTime startsAt;
    private LocalDateTime endsAt;
    private Integer capacity;
    private Integer availableCapacity;
    private SlotStatus status;
    private String eventTitle;
    private String speakerName;
    private String activityDescription;
    @Version
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
