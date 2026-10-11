package com.campusreserve.reservation.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("slot_cancellation")
public class SlotCancellationEntity {
    @TableId(type = IdType.INPUT)
    private Long slotId;
    private String operatorReference;
    private String reason;
    private Integer confirmedCount;
    private Integer waitlistedCount;
    private LocalDateTime cancelledAt;
}
