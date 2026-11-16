package se.joelabs.acppoc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AcpPocApplication {

    public static void main(String[] args) {
        SpringApplication.run(AcpPocApplication.class, args);
    }
}
