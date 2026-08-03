package app.zhijuan.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProductIdentityTest {
    @Test
    fun `product identity is stable`() {
        assertEquals("织卷", ProductIdentity.DISPLAY_NAME)
        assertEquals("app.zhijuan.reader", ProductIdentity.APPLICATION_ID)
    }
}

