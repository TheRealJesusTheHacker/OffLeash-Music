package dev.cyberdogs.offleash.core.auth

/**
 * The fake client identities core tests run the device flow
 * under (0.8.6): obvious fakes, never a real pair — the shared
 * pair this replaces left the source tree with the 0.8.6 build,
 * and no test may carry a live credential shape. [SHARED] stands
 * in for any configured build-supplied pair; [OWNER] for the
 * owner's own registration.
 */
object TestOAuthIdentity {

    val SHARED = OAuthClientIdentity(
        clientId = "fake-shared-client-id",
        clientSecret = "fake-shared-client-secret",
        kind = OAuthClientKind.SHARED,
    )

    val OWNER = OAuthClientIdentity(
        clientId = "fake-owner-client-id",
        clientSecret = "fake-owner-client-secret",
        kind = OAuthClientKind.OWNER,
    )
}
