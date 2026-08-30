package com.faction.clientportal.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplicationUrlDto {

    @NotBlank(message = "URL is required")
    private String url;

    @NotBlank(message = "Title is required")
    private String title;
}
