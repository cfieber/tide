package com.netflix.spinnaker.tide

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.web.SpringBootServletInitializer
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration

@Configuration
@ComponentScan(basePackages = Array("com.netflix.spinnaker.tide", "com.netflix.spinnaker.config"))
@EnableAutoConfiguration
class Main extends SpringBootServletInitializer {
  override def configure(application: SpringApplicationBuilder): SpringApplicationBuilder = {
    application.sources(classOf[Main])
  }
}

object Main extends SpringBootServletInitializer {

  val DEFAULT_PROPS: Map[String, String] = Map(
  "netflix.environment"     -> System.getProperty("netflix.environment", "test"),
  "netflix.account"         -> System.getProperty("netflix.environment", "test"),
  "netflix.stack"           -> System.getProperty("netflix.stack", "test"),
  "spring.config.location"  -> s"${System.getProperties.get("user.home")}/.spinnaker/",
  "spring.config.name"      -> "tide",
  "spring.profiles.active"  -> s"${System.getProperty("netflix.environment", "test")},local"
  )

  applyDefaults()

  def applyDefaults() {
    DEFAULT_PROPS.foreach { case (k, v) =>
      System.setProperty(k, System.getProperty(k, v))
    }
  }

  def main(args: Array[String]) {
    val sources: Array[AnyRef] = Array(classOf[Main])
    SpringApplication.run(sources, args)
  }

}
