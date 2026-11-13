package vn.edu.hsf302.lawrag.security.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import vn.edu.hsf302.lawrag.security.web.ChatRateLimitInterceptor;

@Configuration
public class RateLimitConfig implements WebMvcConfigurer {

    private final ChatRateLimitInterceptor interceptor;

    public RateLimitConfig(ChatRateLimitInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/chat/**");
    }
}
