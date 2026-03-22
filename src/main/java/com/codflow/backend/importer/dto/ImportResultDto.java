package com.codflow.backend.importer.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class ImportResultDto {
    private int totalRows;
    private int imported;
    private int skipped;
    private int errors;
    private List<String> errorMessages;
    private List<String> skippedMessages;
}
