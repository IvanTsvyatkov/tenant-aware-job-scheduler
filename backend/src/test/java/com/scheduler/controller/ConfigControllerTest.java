package com.scheduler.controller;

import com.scheduler.service.ConcurrencyCapManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ConfigControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // Configured caps mirror what would come from application.yml
        ConcurrencyCapManager capManager = new ConcurrencyCapManager(5, 2, 2);
        ConfigController controller = new ConfigController(capManager);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void getConcurrencyCaps_ReturnsConfiguredValues() throws Exception {
        mockMvc.perform(get("/config/concurrency"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.globalMax", is(5)))
                .andExpect(jsonPath("$.tenantMax", is(2)))
                .andExpect(jsonPath("$.targetMax", is(2)));
    }
}
