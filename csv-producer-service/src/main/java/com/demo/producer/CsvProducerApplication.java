package com.demo.producer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

@SpringBootApplication
public class CsvProducerApplication {
    public static void main(String[] args) {
        SpringApplication.run(CsvProducerApplication.class, args);
    }
}
