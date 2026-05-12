package com.forward.it_software_support_portal.config;

import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// why this class or configuration we created?
// CORS is one of the MOST COMMON real-world frontend/backend issues.
// Every Angular + Spring Boot developer faces this.
// Especially when:
// Frontend	           Backend
// localhost:4200	localhost:9092
// localhost:3000	localhost:8080
// domainA.com	     domainB.com

@Configuration
public class CorsConfig {

    @Bean
    public WebMvcConfigurer corsConfigurer() {

        return new WebMvcConfigurer() {

            @Override
            public void addCorsMappings(@NonNull CorsRegistry registry) {

                registry.addMapping("/**")
                        .allowedOrigins("http://localhost:4200")
                        .allowedMethods(
                                "GET",
                                "POST",
                                "PUT",
                                "DELETE",
                                "OPTIONS"
                        )
                        .allowedHeaders("*")
                        .allowCredentials(true);
            }
        };
    }
}