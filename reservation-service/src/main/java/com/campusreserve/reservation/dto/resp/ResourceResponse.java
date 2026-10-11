package com.campusreserve.reservation.dto.resp;

import com.campusreserve.reservation.domain.ResourceStatus;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

public record ResourceResponse(
        @JsonSerialize(using = ToStringSerializer.class) Long resourceId,
        String resourceCode,
        String resourceName,
        String resourceType,
        Integer noShowGraceMinutes,
        ResourceStatus status,
        Integer minParticipants,
        Integer maxParticipants
) {
    public ResourceResponse(Long resourceId, String resourceCode, String resourceName,
                            String resourceType, Integer noShowGraceMinutes, ResourceStatus status) {
        this(resourceId, resourceCode, resourceName, resourceType, noShowGraceMinutes,
                status, null, null);
    }
}
