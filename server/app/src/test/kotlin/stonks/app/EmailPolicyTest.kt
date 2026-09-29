package stonks.app

import stonks.app.auth.EmailCheck
import stonks.app.auth.EmailPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EmailPolicyTest {
    private val policy = EmailPolicy()

    private fun ok(e: String) = assertIs<EmailCheck.Ok>(policy.check(e))

    @Test
    fun `gmail aliases fold onto one canonical address`() {
        assertEquals("bananas@gmail.com", ok("Bananas@Gmail.com").canonical)
        assertEquals("bananas@gmail.com", ok("ba.na.nas@gmail.com").canonical)
        assertEquals("bananas@gmail.com", ok("bananas+abc@googlemail.com").canonical)
        assertEquals(true, ok("bananas+abc@gmail.com").hasPlusTag)
        assertEquals(false, ok("ba.nanas@gmail.com").hasPlusTag)
    }

    @Test
    fun `dots are significant outside gmail`() {
        assertEquals("ba.nanas@outlook.com", ok("ba.nanas+x@outlook.com").canonical)
    }

    @Test
    fun `unknown providers, custom domains and junk are rejected`() {
        assertIs<EmailCheck.Rejected>(policy.check("me@mycompany.io"))
        assertIs<EmailCheck.Rejected>(policy.check("someone@mailinator.com"))
        assertIs<EmailCheck.Rejected>(policy.check("not an email"))
        assertIs<EmailCheck.Rejected>(policy.check("+tag@gmail.com"))
    }
}
