package vn.edu.hsf302.lawrag.observability.config;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.util.EnumSet;
import vn.edu.hsf302.lawrag.observability.filter.RequestIdFilter;

@Configuration
public class RequestIdFilterConfig {

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilterRegistration() {
        FilterRegistrationBean<RequestIdFilter> registration =
                new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setDispatcherTypes(EnumSet.of(
                DispatcherType.REQUEST,
                DispatcherType.ASYNC,
                DispatcherType.ERROR));
        return registration;
    }
}
