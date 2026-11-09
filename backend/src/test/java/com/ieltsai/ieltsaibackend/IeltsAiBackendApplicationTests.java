package com.ieltsai.ieltsaibackend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "evaluation.worker.enabled=false")
class IeltsAiBackendApplicationTests {

    @Test
    void contextLoads() {
    }

}
