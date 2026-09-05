package com.college.labbooking.notification.application;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxDispatcher {
    private static final int MAX_ATTEMPTS = 5;
    private final JdbcTemplate jdbcTemplate;
    private final NotificationChannel channel;
    private final Clock clock;

    public OutboxDispatcher(JdbcTemplate jdbcTemplate, NotificationChannel channel, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.channel = channel;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.jobs.outbox-delay-ms:10000}")
    @Transactional
    public void scheduledDispatch() {
        dispatchBatch(clock.instant(), channel);
    }

    @Transactional
    public int dispatchBatch(Instant now, NotificationChannel deliveryChannel) {
        List<OutboxMessage> messages = jdbcTemplate.query(
                "select id,aggregate_type,aggregate_id,event_type,payload::text,retry_count from outbox_event "
                        + "where ((status in ('PENDING','FAILED') and retry_count<? and next_attempt_at<=?) "
                        + "or (status='PROCESSING' and claimed_at<?)) order by created_at for update skip locked limit 50",
                (rs, row) -> new OutboxMessage((UUID) rs.getObject("id"), rs.getString("aggregate_type"),
                        rs.getString("aggregate_id"), rs.getString("event_type"), rs.getString("payload"),
                        rs.getInt("retry_count")),
                MAX_ATTEMPTS, OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(now.minusSeconds(300), ZoneOffset.UTC));
        int delivered = 0;
        for (OutboxMessage message : messages) {
            jdbcTemplate.update("update outbox_event set status='PROCESSING',claimed_at=? where id=?",
                    OffsetDateTime.ofInstant(now, ZoneOffset.UTC), message.id());
            int attempt = message.retryCount() + 1;
            try {
                deliveryChannel.deliver(message);
                jdbcTemplate.update("insert into outbox_delivery_attempt "
                                + "(event_id,channel,attempt_no,outcome) values (?,?,?,'SUCCESS')",
                        message.id(), deliveryChannel.name(), attempt);
                jdbcTemplate.update("update outbox_event set status='DELIVERED',retry_count=?,delivered_at=?,"
                                + "last_error=null where id=?",
                        attempt, OffsetDateTime.ofInstant(now, ZoneOffset.UTC), message.id());
                delivered++;
            } catch (Exception exception) {
                String summary = abbreviate(exception.getClass().getSimpleName() + ": " + exception.getMessage(), 1000);
                String outcome = attempt >= MAX_ATTEMPTS ? "FAILED" : "RETRY";
                jdbcTemplate.update("insert into outbox_delivery_attempt "
                                + "(event_id,channel,attempt_no,outcome,error_summary) values (?,?,?,?,?)",
                        message.id(), deliveryChannel.name(), attempt, outcome, summary);
                long delaySeconds = Math.min(3600, 1L << Math.min(attempt, 10));
                jdbcTemplate.update("update outbox_event set status='FAILED',retry_count=?,next_attempt_at=?,"
                                + "claimed_at=null,last_error=? where id=?",
                        attempt, OffsetDateTime.ofInstant(now.plusSeconds(delaySeconds), ZoneOffset.UTC), summary, message.id());
            }
        }
        return delivered;
    }

    private String abbreviate(String value, int max) {
        if (value == null) return "Unknown delivery error";
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record OutboxMessage(UUID id, String aggregateType, String aggregateId, String eventType,
            String payload, int retryCount) {}
}
