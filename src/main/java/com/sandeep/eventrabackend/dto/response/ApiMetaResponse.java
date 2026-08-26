package com.sandeep.eventrabackend.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
        description = "Stable public metadata for the Eventra backend API",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE
)
public record ApiMetaResponse(
        @Schema(
                description = "Stable service identifier",
                example = "eventra-backend",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        String service,
        @Schema(
                description = "Stable public API version",
                example = "v1",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        String apiVersion
) {
}
