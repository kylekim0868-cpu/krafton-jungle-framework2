package com.miniredis.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "miniredis.data-dir=build/test-data/integration", // 실제 data/ 디렉터리를 오염시키지 않음
        "miniredis.rdb.interval-seconds=0"
})
@AutoConfigureMockMvc
class RedisControllerIntegrationTest {

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void flushBefore() throws Exception {
        mvc.perform(delete("/api/redis")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST → GET → DELETE → GET(404) 전체 CRUD 흐름")
    void crudFlow() throws Exception {
        mvc.perform(post("/api/redis").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"k1\",\"value\":\"v1\",\"ttl\":60}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value("k1"))
                .andExpect(jsonPath("$.value").value("v1"));

        mvc.perform(get("/api/redis/k1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("k1"))
                .andExpect(jsonPath("$.value").value("v1"))
                .andExpect(jsonPath("$.remainingTTL").value(greaterThanOrEqualTo(59)))
                .andExpect(jsonPath("$.remainingTTL").value(lessThanOrEqualTo(60)));

        // 갱신(Upsert)
        mvc.perform(post("/api/redis").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"k1\",\"value\":\"v2\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/redis/k1"))
                .andExpect(jsonPath("$.value").value("v2"))
                .andExpect(jsonPath("$.remainingTTL").value(-1));

        mvc.perform(delete("/api/redis/k1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("deleted"));

        mvc.perform(get("/api/redis/k1")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/redis/k1")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("key 누락 / value 누락 / 잘못된 JSON → 400")
    void badRequests() throws Exception {
        mvc.perform(post("/api/redis").contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"v\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/redis").contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"  \",\"value\":\"v\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/redis").contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"k\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/redis").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("TTL 만료 후 GET → 404")
    void ttlExpiryReturns404() throws Exception {
        mvc.perform(post("/api/redis").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"temp\",\"value\":\"t\",\"ttl\":1}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/redis/temp")).andExpect(status().isOk());

        Thread.sleep(1_500);

        mvc.perform(get("/api/redis/temp")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("FLUSH 후 전체 조회 시 빈 목록")
    void flushEmptiesStore() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/redis").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"key\":\"f" + i + "\",\"value\":" + i + "}"))
                    .andExpect(status().isCreated());
        }
        mvc.perform(get("/api/redis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(5))
                .andExpect(jsonPath("$.entries", hasSize(5)));

        mvc.perform(delete("/api/redis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("flushed"));

        mvc.perform(get("/api/redis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.entries", hasSize(0)));
    }

    @Test
    @DisplayName("slow-db: 1회차 Cache Miss(~500ms), 2회차 Cache Hit(즉시)")
    void slowDbCacheAside() throws Exception {
        mvc.perform(get("/api/slow-db").param("id", "it"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cached").value(false))
                .andExpect(jsonPath("$.elapsed").value(greaterThanOrEqualTo(500)));

        mvc.perform(get("/api/slow-db").param("id", "it"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cached").value(true))
                .andExpect(jsonPath("$.elapsed").value(lessThanOrEqualTo(50)));
    }

    @Test
    @DisplayName("RDB 수동 저장 API")
    void manualSave() throws Exception {
        mvc.perform(post("/api/redis").contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"s\",\"value\":\"v\"}")).andExpect(status().isCreated());
        mvc.perform(post("/api/redis/save"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
    }
}
