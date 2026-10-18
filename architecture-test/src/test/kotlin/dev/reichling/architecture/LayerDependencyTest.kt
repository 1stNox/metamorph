package dev.reichling.architecture

import com.lemonappdev.konsist.api.architecture.KoArchitectureCreator.assertArchitecture
import org.junit.jupiter.api.Test

class LayerDependencyTest {
    @Test
    fun `layers depend only in the allowed direction`() {
        // Konsist's non-strict dependsOn() restricts nothing: only dependsOnNothing() and
        // doesNotDependOn() make a rule fail. So every dependency that is not allowed is forbidden
        // explicitly.
        Architecture.productionFiles.assertArchitecture {
            Architecture.layers.forEach { it.konsist.include() }

            Architecture.allowedDependencies.forEach { (layer, allowed) ->
                val forbidden = Architecture.layers - layer - allowed
                // Konsist rejects dependsOnNothing() combined with doesNotDependOn() on one layer.
                if (allowed.isEmpty()) {
                    layer.konsist.dependsOnNothing()
                } else if (forbidden.isNotEmpty()) {
                    layer.konsist.doesNotDependOn(forbidden.map { it.konsist }.toSet())
                }
            }
        }
    }
}
