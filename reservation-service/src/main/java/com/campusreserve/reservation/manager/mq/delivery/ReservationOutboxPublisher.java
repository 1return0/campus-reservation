package com.campusreserve.reservation.manager.mq.delivery;

import com.campusreserve.reservation.dao.entity.ReservationOutboxEventEntity;

public interface ReservationOutboxPublisher {

    void publish(ReservationOutboxEventEntity event);
}
