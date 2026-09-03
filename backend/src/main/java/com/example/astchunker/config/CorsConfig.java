package com.example.astchunker.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CHU Y: file frontend (java-structure-chunker.html) hien dang chay doc lap
 * (mo truc tiep bang trinh duyet hoac serve o domain khac backend) -> BAT BUOC
 * cau hinh CORS thi fetch() moi goi duoc API nay, neu khong trinh duyet se chan
 * voi loi "CORS policy" ngay ca khi backend tra ve 200 OK.
 *
 * Khi len production: thay allowedOrigins("*") bang domain that cua frontend
 * (vi du "https://your-frontend.com"), KHONG de "*" cung voi allowCredentials(true).
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("*")
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*");
    }
}
