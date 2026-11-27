package com.socialhub.grouppublishingtask.queue;

/** Group-specific names only: no global RabbitMQ bean or listener overrides. */
public final class GroupRabbitMQConfig {
    public static final String EXCHANGE = "socialhub.group.automation.v1";
    public static final String TASK_QUEUE = "socialhub.group.automation.tasks.v1";
    public static final String TASK_ROUTE = "task";
    private GroupRabbitMQConfig() { }
}
