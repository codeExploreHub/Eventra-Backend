package com.sandeep.eventrabackend.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MetaControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void getMeta_withoutAuthentication_shouldReturnStableExactSchema() throws Exception {
        mockMvc.perform(get("/api/meta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", aMapWithSize(3)))
                .andExpect(jsonPath("$.service").value("eventra-backend"))
                .andExpect(jsonPath("$.apiVersion").value("v1"))
                .andExpect(jsonPath("$.buildVersion").value("0.0.1-SNAPSHOT"));
    }

    @Test
    void openApi_withoutAuthentication_shouldDocumentPublicMetaSchema() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/meta'].get.security", hasSize(0)))
                .andExpect(jsonPath("$.paths['/api/meta'].get.responses['200'].content['application/json'].schema['$ref']")
                        .value("#/components/schemas/ApiMetaResponse"))
                .andExpect(jsonPath("$.components.schemas.ApiMetaResponse.properties", aMapWithSize(3)))
                .andExpect(jsonPath("$.components.schemas.ApiMetaResponse.properties.service").exists())
                .andExpect(jsonPath("$.components.schemas.ApiMetaResponse.properties.apiVersion").exists())
                .andExpect(jsonPath("$.components.schemas.ApiMetaResponse.properties.buildVersion").exists())
                .andExpect(jsonPath("$.components.schemas.ApiMetaResponse.required",
                        containsInAnyOrder("service", "apiVersion", "buildVersion")))
                .andExpect(jsonPath("$.components.schemas.ApiMetaResponse.additionalProperties").value(false));
    }
}
