package com.aleksandarparipovic.marel_app.work_log.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class CreateUpdateWorkLogsRequest {

    private List<WorkLogFormDto> create;
    private List<WorkLogFormDto> update;
    private List<WorkLogFormDto> deleted;
}