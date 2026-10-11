package com.campusreserve.reservation.service;

import com.campusreserve.reservation.dto.req.CreateStudyRoomBookingRequest;
import com.campusreserve.reservation.dto.req.CreateStudyRoomWindowRequest;
import com.campusreserve.reservation.dto.req.UpdateStudyRoomOccupancyRequest;
import com.campusreserve.reservation.dto.req.ReplaceStudyRoomWindowRequest;
import com.campusreserve.reservation.dto.resp.StudyRoomBookingResponse;
import com.campusreserve.reservation.dto.resp.StudyRoomWindowResponse;
import com.campusreserve.reservation.dto.resp.StudyRoomAvailabilityResponse;

import java.time.LocalDate;
import com.campusreserve.security.CampusAuthenticatedPrincipal;

import java.util.List;

public interface StudyRoomService {
    StudyRoomWindowResponse openWindow(CreateStudyRoomWindowRequest request);
    List<StudyRoomWindowResponse> listWindows(Long resourceId);
    StudyRoomAvailabilityResponse availability(Long resourceId, LocalDate date);
    StudyRoomWindowResponse closeWindowForMaintenance(Long windowId, String reason);
    StudyRoomWindowResponse shortenWindow(Long windowId, ReplaceStudyRoomWindowRequest request);
    void updateOccupancy(Long resourceId, UpdateStudyRoomOccupancyRequest request);
    StudyRoomBookingResponse book(CreateStudyRoomBookingRequest request, String idempotencyKey,
                                   CampusAuthenticatedPrincipal principal);
    StudyRoomBookingResponse cancel(Long bookingId, CampusAuthenticatedPrincipal principal);
    StudyRoomBookingResponse get(Long bookingId, CampusAuthenticatedPrincipal principal);
    List<StudyRoomBookingResponse> listMine(CampusAuthenticatedPrincipal principal);
    List<StudyRoomBookingResponse> workQueue();
    StudyRoomBookingResponse checkIn(Long bookingId, String operator, String studentNumber);
    StudyRoomBookingResponse complete(Long bookingId, String operator);
    StudyRoomBookingResponse markNoShow(Long bookingId, String operator);
    /** Marks a bounded batch of confirmed room bookings whose grace period has elapsed. */
    int autoMarkNoShows(int batchSize);
}
