package com.aleksandarparipovic.marel_app.manufacturing_time_request.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * Optional body for assignment. When {@code assigneeUserId} is omitted the caller
 * claims the request for themselves, which is the common case.
 */
@Getter
@Setter
public class ManufacturingTimeRequestAssignRequest {
    private Long assigneeUserId;
}
