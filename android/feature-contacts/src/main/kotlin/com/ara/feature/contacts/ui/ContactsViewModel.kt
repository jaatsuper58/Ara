package com.ara.feature.contacts.ui

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.ara.core.common.AccountRepository
import com.ara.core.common.AppResult
import com.ara.core.db.dao.ContactDao
import com.ara.core.db.entity.ContactEntity
import com.ara.feature.contacts.data.ContactsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val repository: ContactsRepository,
    private val contactDao: ContactDao,
    private val accountRepository: AccountRepository,
) : ViewModel() {

    val contacts: StateFlow<List<ContactEntity>> = repository.observeContacts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _myQr = MutableStateFlow<Bitmap?>(null)
    val myQr: StateFlow<Bitmap?> = _myQr

    private val _addResult = MutableStateFlow<String?>(null)
    val addResult: StateFlow<String?> = _addResult

    init {
        viewModelScope.launch {
            accountRepository.current()?.araId?.let { id -> _myQr.value = generateQr(repository.qrPayload(id)) }
        }
    }

    fun addContact(araId: String, displayName: String?) = viewModelScope.launch {
        when (val result = repository.addByAraId(araId, displayName)) {
            is AppResult.Success -> _addResult.value = null
            is AppResult.Failure -> _addResult.value = result.error.message
        }
    }

    fun toggleBlocked(contact: ContactEntity) = viewModelScope.launch {
        repository.setBlocked(contact.araId, !contact.isBlocked)
    }

    fun toggleFavorite(contact: ContactEntity) = viewModelScope.launch {
        repository.setFavorite(contact.araId, !contact.isFavorite)
    }

    fun onScanResult(payload: String) {
        repository.parseQr(payload)?.let { qr ->
            addContact(qr, null)
        } ?: run { _addResult.value = "Unrecognized QR payload" }
    }

    fun consumeAddResult() { _addResult.value = null }

    private fun generateQr(payload: String, size: Int = 512): Bitmap {
        val bits = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bits[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        return bitmap
    }
}
