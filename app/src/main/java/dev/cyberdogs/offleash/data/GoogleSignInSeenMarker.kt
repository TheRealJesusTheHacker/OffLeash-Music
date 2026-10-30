package dev.cyberdogs.offleash.data

import android.content.Context
import java.io.File

/**
 * The file the "a Google sign-in has happened on this device"
 * marker lives in: the app's no-backup directory, beside the two
 * session stores. The marker is NOT a credential — its content is
 * just the epoch millis of the first mark, kept for debugging.
 */
fun googleSignInSeenFileIn(context: Context): File =
    File(context.noBackupFilesDir, GOOGLE_SIGN_IN_SEEN_FILE_NAME)

const val GOOGLE_SIGN_IN_SEEN_FILE_NAME: String = "google_signin_seen.txt"

/**
 * Whether the presented account state may fall back to the legacy
 * cookie session (bug sweep round 1, closing the H4 residue the
 * sign-in lane found in the container): the fallback exists ONLY
 * for a device that has never had a Google session. Once a Google
 * sign-in has happened, the cookie session is a zombie — if the
 * Google store is later wiped on its own (revoked grant, rejected
 * identity, an unrefreshable token), the account UI must land on
 * the honest Google signed-out state, never resurrect the dormant
 * cookie session as "signed in".
 */
fun legacyFallbackApplies(googleSignedIn: Boolean, googleSignInSeen: Boolean): Boolean =
    !googleSignedIn && !googleSignInSeen

/**
 * Durable one-bit memory: has a Google sign-in EVER been adopted
 * on this device? Written when an adoption lands (and at container
 * start when a stored Google session already exists, which covers
 * upgrades). Never cleared — not even by sign-out: the total
 * sign-out wipes both session stores anyway, so there is nothing
 * left to resurrect, and keeping the bit means a later Google-only
 * wipe can never reopen the legacy fallback either.
 *
 * Total by the stores' contract: every operation swallows IO
 * failure. A marker that cannot be read reads as "not seen" (the
 * pre-sweep behaviour — a missed bit degrades to the old fallback,
 * never to a crash); a mark that cannot be written is retried on
 * the next adoption/start.
 */
class GoogleSignInSeenMarker(private val file: File) {

    fun seen(): Boolean = try {
        file.isFile && file.length() > 0L
    } catch (e: Exception) {
        false
    }

    fun mark() {
        try {
            if (!seen()) {
                file.writeText(System.currentTimeMillis().toString())
            }
        } catch (e: Exception) {
            // Best-effort by design (see class doc): the next
            // adoption or container start marks again.
        }
    }
}
