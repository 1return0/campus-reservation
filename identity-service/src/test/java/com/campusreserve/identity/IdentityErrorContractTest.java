package com.campusreserve.identity;

import com.campusreserve.common.exception.BusinessException;
import com.campusreserve.common.exception.GlobalExceptionHandler;
import com.campusreserve.identity.controller.front.IdentityController;
import com.campusreserve.identity.dto.req.RegistrationRequest;
import com.campusreserve.identity.service.IdentityService;
import com.campusreserve.security.CampusJwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(IdentityController.class)
@AutoConfigureMockMvc(addFilters = false)
class IdentityErrorContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private IdentityService identityService;

    @MockitoBean
    private CampusJwtService jwtService;

    @Test
    void duplicate_username_uses_the_shared_conflict_contract() throws Exception {
        assertThat(context.getBeansOfType(GlobalExceptionHandler.class)).hasSize(1);
        doThrow(new BusinessException(
                HttpStatus.CONFLICT, "username-already-exists", "username-already-exists"
        )).when(identityService).registerStudent(any(RegistrationRequest.class));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"student_1001","password":"Password123","displayName":"Student","studentNumber":"S1001"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("username-already-exists"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
