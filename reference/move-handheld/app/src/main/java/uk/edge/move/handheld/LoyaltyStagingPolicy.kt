package uk.edge.move.handheld

/** Prevent this experimental package from sending merchant data to another environment. */
internal object LoyaltyStagingPolicy {
    fun requireEnvironment(packageName: String, origin: String, staging: Boolean) {
        require(staging && origin == "https://move-staging.getedgeportal.app" &&
            packageName in setOf("uk.edge.move.handheld.loyaltytest.staging", "uk.edge.move.handheld.loyaltytest.staging.benchmark")) {
            "This loyalty test can connect only to Move staging."
        }
    }
}
