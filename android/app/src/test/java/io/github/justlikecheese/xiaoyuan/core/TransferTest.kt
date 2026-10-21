package io.github.justlikecheese.xiaoyuan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 账号迁移格式：和桌面端 transfer.py 互通（VECTOR 由 tests/test_transfer.py 的同一份数据生成）。 */
class TransferTest {
    private val accounts = listOf(
        Account("2002010", "@telecom", "我的号", "p@ss中文", terminalType = -1),
        Account("2002011", "", "", "", terminalType = 2),
    )

    private fun summary(list: List<Account>) = list.map { listOf(it.account, it.isp, it.note, it.password, it.terminalType) }

    @Test
    fun decodesDesktopVector() {
        val got = Transfer.decode(VECTOR, "AB12CD")
        assertEquals(summary(accounts), summary(got))
    }

    @Test
    fun encodesSameAsDesktop() {
        // 同样的 salt、导入码、账号，压缩和加密的结果要和桌面端逐字节一致
        assertEquals(VECTOR, Transfer.encode(accounts, "AB12CD", ByteArray(16) { it.toByte() }))
    }

    @Test
    fun roundTripAndWrongCode() {
        val text = Transfer.encode(accounts, "my-code")
        assertTrue(text.startsWith(Transfer.PREFIX))
        assertFalse("p@ss" in text)
        assertEquals(summary(accounts), summary(Transfer.decode(text, "my-code")))
        val e = assertThrows(Transfer.TransferException::class.java) { Transfer.decode(text, "my-codf") }
        assertEquals("导入码不对", e.message)
    }

    @Test
    fun toleratesLineBreaksAndGarbage() {
        val wrapped = "导入数据：" + VECTOR.chunked(40).joinToString("\n") + "\n请用导入码解开"
        assertEquals(2, Transfer.decode(wrapped, "AB12CD").size)
        assertThrows(Transfer.TransferException::class.java) { Transfer.decode("hello", "AB12CD") }
    }

    companion object {
        const val VECTOR = "XYACC1:AAECAwQFBgcICQoLDA0OD6uLWa-paLukKII1QLF65-Zqr8bbFtQXkuPHjiwhSXetXydjHEQkUiw2UDmw5aj93u1k-lhv7oZzDAcsa4IHiw7I1QzcsdSeYUeUjNA-G-fw3ri9kbYvJtVNaox94DMAFD7ZdIp51VPOWKbKP9q2UNvl3_NYlbbZzhkh9g"
    }
}
