package dev.reichling.architecture

import org.junit.jupiter.api.Test

/** Guards the other rules against passing vacuously, for example after a package rename. */
class LayerStructureTest {
    @Test
    fun `every layer contains at least one production file`() {
        val emptyLayers = Architecture.layers.filter { Architecture.filesIn(it).isEmpty() }

        assertNoViolations(
            "Each layer needs production source files, otherwise its rules check nothing. " +
                "Update the package in Architecture.kt if a package was renamed.",
            emptyLayers.map { "The $it is empty" },
        )
    }
}
