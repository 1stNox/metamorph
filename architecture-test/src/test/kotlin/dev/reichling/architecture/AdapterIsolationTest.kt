package dev.reichling.architecture

import org.junit.jupiter.api.Test

class AdapterIsolationTest {
    @Test
    fun `adapters do not import each other`() {
        val violations =
            Architecture.filesIn(Architecture.adapter).flatMap { file ->
                val own = adapterOf(file.packagee?.name)
                file.imports
                    .filter { it.name.isUnder(Architecture.ADAPTER_PACKAGE) }
                    .filter { adapterOf(it.name) != own }
                    .map { "${file.path}: adapter '$own' imports ${it.name} (adapter '${adapterOf(it.name)}')" }
            }

        assertNoViolations(
            "An adapter package must not import another adapter package. Adapters interact through ports only.",
            violations,
        )
    }

    /** `dev.reichling.adapter.kafka.x.Y` belongs to adapter `kafka`. */
    private fun adapterOf(name: String?): String =
        name.orEmpty().removePrefix("${Architecture.ADAPTER_PACKAGE}.").substringBefore('.')
}
