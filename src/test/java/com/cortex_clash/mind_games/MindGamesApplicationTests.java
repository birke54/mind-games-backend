package com.cortex_clash.mind_games;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MindGamesApplicationTests {

    @Test
    void contextLoads() {
    }

}
