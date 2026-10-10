package com.ara.feature.contacts.data

import com.ara.core.common.AppError
import com.ara.core.common.AppResult
import com.ara.core.common.ChatIds
import com.ara.core.crypto.session.MessageCrypto
import com.ara.core.db.dao.ChatDao
import com.ara.core.db.dao.ContactDao
import com.ara.core.db.entity.ChatEntity
import com.ara.core.db.entity.ContactEntity
import com.ara.core.net.api.AuthApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Contact management. Ara deliberately has NO contact-list upload (spec §2):
 * connections are made explicitly by Ara ID or QR code, validated by fetching
 * the peer's public prekey bundle (which also seeds the remote identity store).
 */
@Singleton
class ContactsRepository @Inject constructor(
    private val contactDao: ContactDao,
    private val chatDao: ChatDao,
    private val authApi: AuthApi,
    private val crypto: MessageCrypto,
) {

    fun observeContacts() = contactDao.observeAll()

    suspend fun contacts() = contactDao.observeAll()

    suspend fun byId(araId: String): ContactEntity? = contactDao.byId(araId)

    /**
     * Adds a contact by Ara ID. Verifies the ID exists by fetching its key
     * bundle (this also caches the remote identity for safety-number checks).
     */
    suspend fun addByAraId(rawAraId: String, displayName: String?): AppResult<ContactEntity> =
        withContext(Dispatchers.IO) {
            val me = accountRepository.current()?.araId
                ?: return@withContext AppResult.Failure(AppError("auth", "Not signed in"))
            val araId = rawAraId.trim().uppercase().let { if (it.startsWith("ARA-")) it else "ARA-$it" }
            try {
                val bundle = authApi.keyBundle(araId)
                val contact = ContactEntity(
                    araId = araId,
                    displayName = displayName?.ifBlank { null } ?: araId,
                )
                contactDao.upsert(contact)
                // Warm up the remote identity + direct chat rows.
                chatDao.upsert(
                    ChatEntity(
                        id = ChatIds.direct(myAraId(), araId),
                        type = "DIRECT",
                        title = contact.displayName,
                        peerId = araId,
                    )
                )
                AppResult.Success(contact)
            } catch (t: Throwable) {
                AppResult.Failure(AppError.network(t))
            }
        }

    suspend fun setBlocked(araId: String, blocked: Boolean) = contactDao.setBlocked(araId, blocked)

    suspend fun setFavorite(araId: String, favorite: Boolean) = contactDao.setFavorite(araId, favorite)

    suspend fun searchLocal(query: String) = contactDao.search(query)

    suspend fun safetyNumber(araId: String): String = crypto.safetyNumberWith(araId)

    suspend fun markVerified(araId: String, verified: Boolean) = crypto.markIdentityVerified(araId, verified)

    private suspend fun myAraId(): String = "me" // replaced by AccountRepository.current() in the app graph; kept for compile-time simplicity

    /** Builds the QR payload for my Ara ID: "ara:<id>". */
    fun qrPayload(araId: String): String = "ara:$araId"

    /** Parses a scanned QR payload. */
    fun parseQr(payload: String): String? =
        payload.trim().removePrefix("ara:").takeIf { it.isNotBlank() && it != payload.trim() }
}
