package com.sandeep.eventrabackend.controller;

import com.sandeep.eventrabackend.dto.response.ApiMetaResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/meta")
@Tag(name = "Metadata", description = "Public metadata for API discovery")
public class MetaController {

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirements
    @Operation(
            summary = "Get public API metadata",
            description = "Returns the stable service identifier, public API version, and build version."
    )
    @ApiResponse(
            responseCode = "200",
            description = "API metadata returned successfully",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiMetaResponse.class)
            )
    )
    public ApiMetaResponse getMeta() {
        return new ApiMetaResponse("eventra-backend", "v1", "0.0.1-SNAPSHOT");
    }
}
