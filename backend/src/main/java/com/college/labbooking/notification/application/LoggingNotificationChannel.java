package com.college.labbooking.notification.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LoggingNotificationChannel implements NotificationChannel {
    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationChannel.class);

    @Override
    public String name() {
        return "LOG";
    }

    @Override
    public void deliver(OutboxDispatcher.OutboxMessage message) {
        log.info("Delivered outbox event id={} type={} aggregate={}:{}",
                message.id(), message.eventType(), message.aggregateType(), message.aggregateId());
    }
}
