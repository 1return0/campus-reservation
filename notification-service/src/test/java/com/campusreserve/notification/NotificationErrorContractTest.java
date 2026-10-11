package com.campusreserve.notification;

import com.campusreserve.common.exception.GlobalExceptionHandler;
import com.campusreserve.notification.controller.front.StudentNotificationController;
import com.campusreserve.notification.service.StudentNotificationQueryService;
import com.campusreserve.security.CampusAuthenticatedPrincipal;
import com.campusreserve.security.CampusJwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StudentNotificationController.class)
@AutoConfigureMockMvc(addFilters = false)
class NotificationErrorContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private StudentNotificationQueryService notificationQueryService;

    @MockitoBean
    private CampusJwtService jwtService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void another_students_notification_uses_the_shared_not_found_contract() throws Exception {
        assertThat(context.getBeansOfType(GlobalExceptionHandler.class)).hasSize(1);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new CampusAuthenticatedPrincipal("student-1001", Set.of("STUDENT")),
                        null
                )
        );
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "notification-not-found"))
                .when(notificationQueryService).markRead(31L, "student-1001");

        mockMvc.perform(patch("/api/v1/notifications/31/read"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("notification-not-found"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
