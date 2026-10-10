package uk.edge.move.handheld

import org.junit.Test
import org.junit.Assert.assertThrows

class LoyaltyStagingPolicyTest {
    @Test fun permitsOnlyStagingTestPackagesAtStagingOrigin() {
        for (name in listOf("uk.edge.move.handheld.loyaltytest.staging", "uk.edge.move.handheld.loyaltytest.staging.benchmark"))
            LoyaltyStagingPolicy.requireEnvironment(name, "https://move-staging.getedgeportal.app", true)
        for (origin in listOf("https://move.getedgeportal.app", "https://move-staging.getedgeportal.app.evil.example", "http://move-staging.getedgeportal.app", "https://move-staging.getedgeportal.app/"))
            assertThrows(IllegalArgumentException::class.java) { LoyaltyStagingPolicy.requireEnvironment("uk.edge.move.handheld.loyaltytest.staging", origin, true) }
        assertThrows(IllegalArgumentException::class.java) { LoyaltyStagingPolicy.requireEnvironment("uk.edge.move.handheld.staging", "https://move-staging.getedgeportal.app", true) }
        assertThrows(IllegalArgumentException::class.java) { LoyaltyStagingPolicy.requireEnvironment("uk.edge.move.handheld.loyaltytest.staging", "https://move-staging.getedgeportal.app", false) }
    }
}
