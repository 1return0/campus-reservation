package com.campusreserve.notification.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campusreserve.notification.dao.entity.StudentNotificationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface StudentNotificationMapper extends BaseMapper<StudentNotificationEntity> {

    @Update("""
            UPDATE student_notification
            SET read_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{notificationId}
              AND recipient_reference = #{recipientReference}
              AND read_at IS NULL
            """)
    int markReadByRecipient(
            @Param("notificationId") Long notificationId,
            @Param("recipientReference") String recipientReference
    );
}
