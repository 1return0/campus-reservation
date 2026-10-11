package com.campusreserve.notification.service;

import com.campusreserve.notification.manager.mq.event.IncomingReservationEvent;

public interface ReservationNotificationService {

    boolean accept(IncomingReservationEvent event);
}
