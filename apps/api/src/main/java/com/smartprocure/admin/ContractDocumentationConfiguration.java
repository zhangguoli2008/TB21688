package com.smartprocure.admin;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Serve Swagger assets only. Runtime OpenAPI generation remains disabled. */
@Configuration
@Profile({"demo", "test"})
public class ContractDocumentationConfiguration implements WebMvcConfigurer {
    @Override public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Version is supplied by the pinned springdoc 2.8.17 dependency.
        registry.addResourceHandler("/swagger-ui/vendor/**")
                .addResourceLocations("classpath:/META-INF/resources/webjars/swagger-ui/5.32.2/");
    }
    @Override public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/swagger-ui.html", "/swagger-ui/index.html");
    }
}
