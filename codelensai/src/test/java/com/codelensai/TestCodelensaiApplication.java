package com.codelensai;

import org.springframework.boot.SpringApplication;

public class TestCodelensaiApplication {

	public static void main(String[] args) {
		SpringApplication.from(CodelensaiApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
