package com.ara.core.net.api

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Streaming

/**
 * Encrypted file relay API — spec §6 / Phase 6.
 *
 * The relay stores opaque encrypted chunks only (short-lived cache, purged on
 * delivery confirmation or after 7 days undelivered). The per-file AES key is
 * distributed inside the E2EE message envelope, never via this API.
 */
interface FilesApi {

    @POST("files/init")
    suspend fun initUpload(@Body body: FileInitRequest): FileInitResponse

    @PUT("files/{fileId}/chunks/{index}")
    suspend fun uploadChunk(
        @Path("fileId") fileId: String,
        @Path("index") index: Int,
        @Body chunk: RequestBody,
    )

    @POST("files/{fileId}/complete")
    suspend fun completeUpload(@Path("fileId") fileId: String)

    @Streaming
    @GET("files/{fileId}/chunks/{index}")
    suspend fun downloadChunk(
        @Path("fileId") fileId: String,
        @Path("index") index: Int,
    ): ResponseBody

    /** Called by the recipient once all chunks are downloaded — server purges the file. */
    @DELETE("files/{fileId}")
    suspend fun ackDelivery(@Path("fileId") fileId: String)

    @Serializable
    data class FileInitRequest(val sizeBytes: Long, val chunkCount: Int, val chunkSize: Int)

    @Serializable
    data class FileInitResponse(val fileId: String, val chunkSize: Int)
}
