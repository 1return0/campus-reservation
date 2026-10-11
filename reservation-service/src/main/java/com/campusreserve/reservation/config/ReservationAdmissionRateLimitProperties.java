package com.campusreserve.reservation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "campus-reserve.admission.rate-limit")
public class ReservationAdmissionRateLimitProperties {

    private long windowMillis = 1_000;
    private long perSlotMaxRequests = 80;
    private long perUserPerSlotMaxRequests = 3;

    public long getWindowMillis() {
        return windowMillis;
    }

    public void setWindowMillis(long windowMillis) {
        this.windowMillis = windowMillis;
    }

    public long getPerSlotMaxRequests() {
        return perSlotMaxRequests;
    }

    public void setPerSlotMaxRequests(long perSlotMaxRequests) {
        this.perSlotMaxRequests = perSlotMaxRequests;
    }

    public long getPerUserPerSlotMaxRequests() {
        return perUserPerSlotMaxRequests;
    }

    public void setPerUserPerSlotMaxRequests(long perUserPerSlotMaxRequests) {
        this.perUserPerSlotMaxRequests = perUserPerSlotMaxRequests;
    }
}
