package dev.secondbrain;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SecondBrainApplicationTest {

    private final ConfigurableApplicationContext applicationContext;

    @Autowired
    SecondBrainApplicationTest(ConfigurableApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Test
    void startsWithoutDatabaseOrModelCredentials() {
        Assertions.assertThat(applicationContext.isActive()).isTrue();
    }
}
