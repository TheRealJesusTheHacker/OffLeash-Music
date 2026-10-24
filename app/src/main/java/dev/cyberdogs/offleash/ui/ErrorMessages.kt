package dev.cyberdogs.offleash.ui

import dev.cyberdogs.offleash.core.error.OffleashException

/**
 * The ONE place core errors become user copy — the SPEC §6 shapes.
 * Every screen, the queue controller and every error panel go through here.
 */
object ErrorMessages {

    fun userMessage(error: Throwable): String = when (error) {
        is OffleashException.ContextRejected ->
            "YouTube refused the app's request — the API has likely changed. " +
                "(server said: ${error.serverMessage})"
        is OffleashException.PlayerRefused ->
            "YouTube won't play this track: ${error.reason}"
        is OffleashException.NoPlayableStream ->
            "No playable stream for this track — YouTube served only locked formats."
        is OffleashException.NetworkFailure ->
            "Couldn't reach YouTube Music — check your connection."
        is OffleashException.ParseFailure ->
            "YouTube Music answered in a shape this app doesn't understand yet — " +
                "the API has likely changed."
        else ->
            "Something went wrong. Try again."
    }
}
