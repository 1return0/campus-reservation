package com.campusreserve.reservation.service;

import com.campusreserve.reservation.dto.req.CancelSlotRequest;
import com.campusreserve.reservation.dto.resp.SlotCancellationResponse;

public interface SlotCancellationService {
    SlotCancellationResponse cancelForMaintenance(Long slotId, String operatorReference, CancelSlotRequest request);

    SlotCancellationResponse getCancellation(Long slotId);
}
