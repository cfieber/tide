package com.netflix.spinnaker.tide.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JSR310Module;
import com.fasterxml.jackson.module.scala.DefaultScalaModule$;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import retrofit.RestAdapter;

import javax.annotation.PostConstruct;
import java.util.Collection;

import static com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT;
import static com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY;
import static com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS;

@Configuration
public class BaseConfig {

  @Autowired
  ListableBeanFactory beanFactory;

  @PostConstruct
  public void configureObjectMappers() {
    getBeans(ObjectMapper.class).forEach(this::configureObjectMapper);
  }

  public void configureObjectMapper(ObjectMapper objectMapper) {
    objectMapper.registerModule(DefaultScalaModule$.MODULE$)
      .registerModule(new JSR310Module())
      .disable(WRITE_DATES_AS_TIMESTAMPS)
      .enable(ACCEPT_SINGLE_VALUE_AS_ARRAY)
      .enable(ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
  }

  private <T> Collection<T> getBeans(Class<T> type) {
    return BeanFactoryUtils.beansOfTypeIncludingAncestors(beanFactory, type).values();
  }

  @ConfigurationProperties
  @Bean
  RestAdapter.LogLevel retrofitLogLevel(@Value("${retrofit.log.level:FULL}") String logLevel) {
    return RestAdapter.LogLevel.valueOf(logLevel);
  }
}
