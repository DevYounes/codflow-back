package com.codflow.backend.analytics.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AgentPerformanceDto {
    private Long agentId;
    private String agentName;
    private String agentUsername;
    private long totalAssigned;
    private long confirmed;
    private long cancelled;
    private long doublon;
    private long pending;
    private double confirmationRate;
    private double cancellationRate;
    private long callsToday;
}
