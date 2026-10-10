package io.github.gratycodes.samplepayments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SamplePaymentsApplication {

    public static void main(String[] args) {
        SpringApplication.run(SamplePaymentsApplication.class, args);
    }
}
