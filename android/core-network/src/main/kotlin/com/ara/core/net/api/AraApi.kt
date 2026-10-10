package com.ara.core.net.api

import com.ara.core.crypto.wire.KeyBundle
import com.ara.core.net.dto.AccountResponse
import com.ara.core.net.dto.AddMembersRequest
import com.ara.core.net.dto.CreateAccountRequest
import com.ara.core.net.dto.CreateGroupRequest
import com.ara.core.net.dto.DeviceDto
import com.ara.core.net.dto.GroupDto
import com.ara.core.net.dto.OtpRequest
import com.ara.core.net.dto.OtpVerifyRequest
import com.ara.core.net.dto.PatchGroupRequest
import com.ara.core.net.dto.PreKeyUploadRequest
import com.ara.core.net.dto.PushTokenRequest
import com.ara.core.net.dto.RefreshSessionRequest
import com.ara.core.net.dto.SessionRequest
import com.ara.core.net.dto.SessionResponse
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

/** Auth REST surface — spec §6. */
interface AuthApi {

    @POST("account")
    suspend fun createAccount(@Body body: CreateAccountRequest): AccountResponse

    @POST("account/otp")
    suspend fun requestOtp(@Body body: OtpRequest): AccountResponse

    @POST("account/otp/verify")
    suspend fun verifyOtp(@Body body: OtpVerifyRequest): AccountResponse

    @POST("session")
    suspend fun createSession(@Body body: SessionRequest): SessionResponse

    @GET("users/{araId}/keybundle")
    suspend fun keyBundle(@Path("araId") araId: String): KeyBundle

    @POST("prekeys")
    suspend fun uploadPreKeys(@Body body: PreKeyUploadRequest)

    @POST("devices")
    suspend fun registerDevice(@Body body: DeviceDto): DeviceDto

    @POST("devices/push-token")
    suspend fun updatePushToken(@Body body: PushTokenRequest)

    @GET("devices/me")
    suspend fun myDevices(): List<DeviceDto>
}

/** Groups REST surface — spec §6. */
interface GroupsApi {

    @POST("groups")
    suspend fun createGroup(@Body body: CreateGroupRequest): GroupDto

    @PATCH("groups/{id}")
    suspend fun patchGroup(@Path("id") id: String, @Body body: PatchGroupRequest): GroupDto

    @GET("groups/{id}")
    suspend fun group(@Path("id") id: String): GroupDto

    @POST("groups/{id}/members")
    suspend fun addMembers(@Path("id") id: String, @Body body: AddMembersRequest): GroupDto

    @DELETE("groups/{id}/members/{userId}")
    suspend fun removeMember(@Path("id") id: String, @Path("userId") userId: String): GroupDto
}
