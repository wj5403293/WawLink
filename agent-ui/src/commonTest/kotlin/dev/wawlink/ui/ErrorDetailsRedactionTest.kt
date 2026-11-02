package dev.wawlink.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ErrorDetailsRedactionTest {
    @Test
    fun redactsCommonCredentialFragmentsButPreservesDiagnosticContext() {
        val source = "Authorization: Bearer abc123; Authorization: Basic dXNlcjpwYXNz; " +
            "request=https://alice:password@example.com/v1; password: \"two words\"; " +
            "errno=ENOENT path=/workspace/note.txt"
        assertEquals(
            "Authorization: Bearer ***; Authorization: Basic ***; " +
                "request=https://***@example.com/v1; password: ***; " +
                "errno=ENOENT path=/workspace/note.txt",
            redactErrorDetails(source),
        )
    }
}
