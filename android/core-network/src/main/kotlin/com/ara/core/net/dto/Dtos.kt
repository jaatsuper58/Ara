package com.ara.core.net.dto

import com.ara.core.crypto.keys.KeyBundle
import kotlinx.serialization.Serializable

@Serializable
data class DeviceDto(
    val deviceId: String,
    val platform: String = "android",
    val name: String? = null,
    val publicKey: String? = null,
    val pushToken: String? = null,
)

@Serializable
data class CreateAccountRequest(
    /** Client-proposed Ara ID; the server honors it when free, else generates. */
    val araId: String? = null,
    val recoveryEmail: String? = null,
    val publicKeyBundle: KeyBundle,
    val device: DeviceDto,
)

@Serializable
data class AccountResponse(
    val araId: String,
    val accessToken: String? = null,
    val refreshToken: String? = null,
)

@Serializable
data class OtpRequest(val phone: String)

@Serializable
data class OtpVerifyRequest(val phone: String, val code: String)

@Serializable
data class SessionRequest(
    val araId: String,
    val device: DeviceDto,
)

@Serializable
data class SessionResponse(
    val araId: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
)

@Serializable
data class RefreshSessionRequest(val araId: String, val refreshToken: String)

@Serializable
data class PreKeyUploadRequest(
    val signedPreKey: KeyBundle? = null,
    val oneTimePreKeys: List<OneTimePreKeyUploadDto> = emptyList(),
)

@Serializable
data class OneTimePreKeyUploadDto(val id: Int, val publicKey: String)

@Serializable
data class PushTokenRequest(val token: String, val deviceId: String)

// ---------- groups ----------

@Serializable
data class CreateGroupRequest(
    val name: String,
    val description: String? = null,
    val memberLimit: Int = 256,
)

@Serializable
data class GroupDto(
    val id: String,
    val name: String,
    val description: String? = null,
    val photoUrl: String? = null,
    val ownerId: String,
    val memberLimit: Int = 256,
    val members: List<GroupMemberDto> = emptyList(),
    val createdAt: Long = 0,
)

@Serializable
data class GroupMemberDto(
    val araId: String,
    val role: String = "MEMBER",
    val joinedAt: Long = 0,
    val muted: Boolean = false,
)

@Serializable
data class AddMembersRequest(val members: List<String>)

@Serializable
data class PatchGroupRequest(
    val name: String? = null,
    val description: String? = null,
    val photoUrl: String? = null,
)
