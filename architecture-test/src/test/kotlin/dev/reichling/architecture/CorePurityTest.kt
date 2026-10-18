package dev.reichling.architecture

import org.junit.jupiter.api.Test

class CorePurityTest {
    private val allowedPrefixes = listOf("kotlin", "java", Architecture.CORE_PACKAGE)

    @Test
    fun `core imports only from kotlin, java and core`() {
        val violations =
            Architecture.filesIn(Architecture.CORE_PACKAGE).flatMap { file ->
                file.imports
                    .filter { import -> allowedPrefixes.none { import.name.isUnder(it) } }
                    .map { import -> "${file.path}: imports ${import.name}" }
            }

        assertNoViolations(
            "Files in ${Architecture.CORE_PACKAGE}.. may import only from " +
                "${allowedPrefixes.joinToString { "$it.." }}.",
            violations,
        )
    }
}
