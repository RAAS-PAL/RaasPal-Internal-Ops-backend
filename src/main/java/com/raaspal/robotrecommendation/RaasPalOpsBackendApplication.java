package com.raaspal.robotrecommendation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RaasPalOpsBackendApplication {

	public static void main(String[] args) {
		// Required for Apache POI font/graphics on headless Linux servers
		System.setProperty("java.awt.headless", "true");
		SpringApplication.run(RaasPalOpsBackendApplication.class, args);
	}

}
