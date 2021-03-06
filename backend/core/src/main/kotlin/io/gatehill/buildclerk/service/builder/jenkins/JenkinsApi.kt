package io.gatehill.buildclerk.service.builder.jenkins

import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Models the Jenkins API.
 *
 * @author Pete Cornish {@literal <outofcoffee@gmail.com>}
 */
interface JenkinsApi {
    @GET("crumbIssuer/api/xml?xpath=concat(//crumbRequestField,\":\",//crumb)")
    fun fetchCrumb(): Call<ResponseBody>

    @POST("{jobPath}/build")
    fun enqueueBuild(@Path("jobPath", encoded = true) jobPath: String): Call<Void>
}
