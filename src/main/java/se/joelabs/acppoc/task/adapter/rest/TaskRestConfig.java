package se.joelabs.acppoc.task.adapter.rest;

import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registrerar {@link RestOriginFilter} för panelens REST-API med container-matchat URL-mönster (NFR-10). */
@Configuration
class TaskRestConfig {

    @Bean
    FilterRegistrationBean<RestOriginFilter> restOriginFilter(@Value("${poc.web.allowed-origins}") String[] origins) {
        var registration = new FilterRegistrationBean<>(new RestOriginFilter(Set.of(origins)));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(0);
        return registration;
    }
}
