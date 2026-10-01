package com.storagehub;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = StoragehubApplication.class)
@ActiveProfiles("test")
class StoragehubApplicationTests {

	@Test
	void contextLoads() {
	}

}
