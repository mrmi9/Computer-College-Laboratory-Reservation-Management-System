package com.college.labbooking.notification.application;

import com.college.labbooking.common.api.PageView;
import com.college.labbooking.common.exception.AppException;
import com.college.labbooking.security.CurrentUser;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
    private final JdbcTemplate jdbcTemplate;

    public NotificationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public PageView<NotificationView> list(CurrentUser user, boolean unreadOnly, int page, int size) {
        String unread = unreadOnly ? " and read_at is null" : "";
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from notification where recipient_id=?" + unread, Long.class, user.id());
        List<NotificationView> items = jdbcTemplate.query(
                "select id,notification_type,title,content,related_type,related_id,read_at,created_at "
                        + "from notification where recipient_id=?" + unread + " order by created_at desc limit ? offset ?",
                (rs, row) -> new NotificationView(rs.getLong("id"), rs.getString("notification_type"),
                        rs.getString("title"), rs.getString("content"), rs.getString("related_type"),
                        nullableLong(rs, "related_id"), rs.getObject("read_at", OffsetDateTime.class),
                        rs.getObject("created_at", OffsetDateTime.class)),
                user.id(), size, (long) page * size);
        return new PageView<>(items, page, size, total == null ? 0 : total);
    }

    @Transactional
    public NotificationView markRead(long id, CurrentUser user) {
        int updated = jdbcTemplate.update("update notification set read_at=coalesce(read_at,now()) where id=? and recipient_id=?",
                id, user.id());
        if (updated == 0) throw new AppException(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "通知不存在");
        return list(user, false, 0, 100).items().stream().filter(item -> item.id() == id).findFirst().orElseThrow();
    }

    @Transactional
    public int markAllRead(CurrentUser user) {
        return jdbcTemplate.update("update notification set read_at=now() where recipient_id=? and read_at is null", user.id());
    }

    private Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public record NotificationView(long id, String type, String title, String content, String relatedType,
            Long relatedId, OffsetDateTime readAt, OffsetDateTime createdAt) {}
}
