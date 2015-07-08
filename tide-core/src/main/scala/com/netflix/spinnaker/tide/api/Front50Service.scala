package com.netflix.spinnaker.tide.api

import com.netflix.spinnaker.tide.actor.aws.AwsApi.SecurityGroup
import retrofit.http._

trait Front50Service {

  @Headers(Array("Accept: application/json"))
  @GET("/pipelines")
  def getAllPipelines: List[Pipeline]


  @Headers (Array ("Accept: application/json") )
  @POST ("/pipelines/batchUpdate") def addPipelines (@Body pipelines: List[PipelineState]): String

}
