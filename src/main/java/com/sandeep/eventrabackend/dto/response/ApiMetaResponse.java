package com.sandeep.eventrabackend.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Stable public metadata for the Eventra backend API")
public record ApiMetaResponse(
        @Schema(description = "Stable service identifier", example = "eventra-backend")
        String service,
        @Schema(description = "Stable public API version", example = "v1")
        String apiVersion
) {
}
