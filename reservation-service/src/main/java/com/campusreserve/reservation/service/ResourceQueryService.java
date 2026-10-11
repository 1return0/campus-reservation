package com.campusreserve.reservation.service;

import com.campusreserve.reservation.dto.resp.ResourceResponse;
import com.campusreserve.reservation.dto.resp.SlotResponse;

import java.util.List;

public interface ResourceQueryService {

    List<ResourceResponse> listPublishedResources();

    List<SlotResponse> listOpenSlots(Long resourceId);
}
