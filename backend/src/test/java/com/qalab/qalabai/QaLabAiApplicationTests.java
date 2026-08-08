package com.qalab.qalabai;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.properties.hibernate.dialect=com.qalab.qalabai.NoInsertReturningH2Dialect"
})
class QaLabAiApplicationTests {

    @Test
    void contextLoads() {
    }
}
