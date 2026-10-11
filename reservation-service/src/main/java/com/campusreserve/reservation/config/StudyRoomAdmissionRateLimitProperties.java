package com.campusreserve.reservation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "campus-reserve.admission.study-room")
public class StudyRoomAdmissionRateLimitProperties {
    private long windowMillis = 1_000;
    private long perRoomDayMaxRequests = 80;
    private long perUserPerRoomDayMaxRequests = 3;

    public long getWindowMillis() { return windowMillis; }
    public void setWindowMillis(long windowMillis) { this.windowMillis = windowMillis; }
    public long getPerRoomDayMaxRequests() { return perRoomDayMaxRequests; }
    public void setPerRoomDayMaxRequests(long value) { this.perRoomDayMaxRequests = value; }
    public long getPerUserPerRoomDayMaxRequests() { return perUserPerRoomDayMaxRequests; }
    public void setPerUserPerRoomDayMaxRequests(long value) { this.perUserPerRoomDayMaxRequests = value; }
}
