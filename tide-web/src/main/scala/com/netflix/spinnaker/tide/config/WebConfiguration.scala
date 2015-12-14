package com.netflix.spinnaker.tide.config

import javax.servlet.http.HttpServletResponse
import javax.servlet._
import org.springframework.context.annotation.{Bean, Configuration, ComponentScan}
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter

@Configuration
@ComponentScan(basePackages = Array("com.netflix.spinnaker.tide.controllers"))
class WebConfiguration extends WebMvcConfigurerAdapter {

  @Bean
  def simpleCORSFilter(): Filter = {
    new Filter() {
      def doFilter(req: ServletRequest, res: ServletResponse, chain: FilterChain)  {
        def response: HttpServletResponse = res.asInstanceOf[HttpServletResponse]
        response.setHeader("Access-Control-Allow-Origin", "*")
        response.setHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS, DELETE")
        response.setHeader("Access-Control-Max-Age", "3600")
        response.setHeader("Access-Control-Allow-Headers", "x-requested-with, content-type")
        chain.doFilter(req, res)
      }

      def init(filterConfig: FilterConfig) {}

      def destroy() {}
    }
  }
}