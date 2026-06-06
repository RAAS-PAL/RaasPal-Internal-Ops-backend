package com.raaspal.robotrecommendation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RobotRecommendationApiApplication {

	public static void main(String[] args) {
		// Required for Apache POI font/graphics on headless Linux servers (e.g. Render)
		System.setProperty("java.awt.headless", "true");
		SpringApplication.run(RobotRecommendationApiApplication.class, args);
	}

}
