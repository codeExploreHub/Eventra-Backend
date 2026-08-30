package com.sandeep.eventrabackend.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Username availability for the authenticated user")
public record UsernameAvailabilityResponse(
        @Schema(description = "Trimmed username candidate", example = "john_doe")
        String username,
        @Schema(description = "Whether the candidate is available to the authenticated user")
        boolean available
) {
}
