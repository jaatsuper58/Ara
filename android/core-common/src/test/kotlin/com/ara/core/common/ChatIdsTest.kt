package com.ara.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatIdsTest {

    @Test
    fun `direct chat ids are order-independent`() {
        val a = ChatIds.direct("ARA-AAAA-BBBB-CCCC", "ARA-DDDD-EEEE-FFFF")
        val b = ChatIds.direct("ARA-DDDD-EEEE-FFFF", "ARA-AAAA-BBBB-CCCC")
        assertEquals(a, b)
        assertTrue(ChatIds.isDirect(a))
    }

    @Test
    fun `peer resolution works from both sides`() {
        val me = "ARA-MINE-0000-0000"
        val peer = "ARA-THEIR-0000-0000"
        val chatId = ChatIds.direct(me, peer)
        assertEquals(peer, ChatIds.peerOf(chatId, me))
        assertEquals(me, ChatIds.peerOf(chatId, peer))
    }

    @Test
    fun `saved messages thread is per user`() {
        val mine = ChatIds.savedMessages("ARA-ME")
        val theirs = ChatIds.savedMessages("ARA-YOU")
        assertTrue(ChatIds.isSavedMessages(mine, "ARA-ME"))
        assertFalse(ChatIds.isSavedMessages(mine, "ARA-YOU"))
        assertFalse(mine == theirs)
        assertFalse(ChatIds.isDirect(mine))
    }
}
