package com.campusreserve.reservation.service;

import com.campusreserve.reservation.dto.req.CreateResourceRequest;
import com.campusreserve.reservation.dto.req.CreateSlotRequest;
import com.campusreserve.reservation.dto.resp.ResourceResponse;
import com.campusreserve.reservation.dto.resp.SlotResponse;
import java.util.List;

public interface ResourceManagementService {

    ResourceResponse createResource(CreateResourceRequest request);

    ResourceResponse publishResource(Long resourceId);

    ResourceResponse closeResource(Long resourceId);

    List<ResourceResponse> listAllResources();

    List<SlotResponse> listSlots(Long resourceId);

    SlotResponse createOpenSlot(CreateSlotRequest request);
}
