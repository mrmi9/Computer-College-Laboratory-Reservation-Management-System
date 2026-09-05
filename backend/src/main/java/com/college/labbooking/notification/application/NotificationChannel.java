package com.college.labbooking.notification.application;

public interface NotificationChannel {
    String name();

    void deliver(OutboxDispatcher.OutboxMessage message) throws Exception;
}
