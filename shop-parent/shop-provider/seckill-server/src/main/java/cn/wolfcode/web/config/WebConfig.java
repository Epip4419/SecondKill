package cn.wolfcode.web.config;

import cn.wolfcode.common.web.interceptor.FeignRequestInterceptor;
import cn.wolfcode.common.web.interceptor.RequireLoginInterceptor;
import cn.wolfcode.common.web.resolver.UserInfoMethodArgumentResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;


@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Bean
    public RequireLoginInterceptor requireLoginInterceptor(StringRedisTemplate redisTemplate){
        return new RequireLoginInterceptor(redisTemplate);
    }
    @Bean
    public FeignRequestInterceptor feignRequestInterceptor(){
        return new FeignRequestInterceptor();
    }

    @Bean
    public UserInfoMethodArgumentResolver userInfoMethodArgumentResolver(){
        return new UserInfoMethodArgumentResolver();
    }
    @Autowired
    private RequireLoginInterceptor requireLoginInterceptor;
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(requireLoginInterceptor)
                .addPathPatterns("/**");
    }
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> argumentResolvers) {
        argumentResolvers.add(userInfoMethodArgumentResolver());
    }
}
