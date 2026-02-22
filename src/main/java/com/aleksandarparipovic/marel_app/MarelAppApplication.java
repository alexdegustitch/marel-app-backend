package com.aleksandarparipovic.marel_app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@SpringBootApplication
@EnableAspectJAutoProxy
public class MarelAppApplication {

	public static void main(String[] args) {
		SpringApplication.run(MarelAppApplication.class, args);
	}

}
