package dev.cyberdogs.offleash.ui

import dev.cyberdogs.offleash.core.error.ContextRejected
import dev.cyberdogs.offleash.core.error.NetworkFailure
import dev.cyberdogs.offleash.core.error.NoPlayableStream
import dev.cyberdogs.offleash.core.error.ParseFailure
import dev.cyberdogs.offleash.core.error.PlayerRefused

/**
 * The ONE place core errors become user copy — the SPEC §6 shapes.
 * Every screen, the queue controller and every error panel go through here.
 */
object ErrorMessages {

    fun userMessage(error: Throwable): String = when (error) {
        is ContextRejected ->
            "YouTube refused the app's request — the API has likely changed. " +
                "(server said: ${error.serverMessage})"
        is PlayerRefused ->
            "YouTube won't play this track: ${error.reason}"
        is NoPlayableStream ->
            "No playable stream for this track — YouTube served only locked formats."
        is NetworkFailure ->
            "Couldn't reach YouTube Music — check your connection."
        is ParseFailure ->
            "YouTube Music answered in a shape this app doesn't understand yet — " +
                "the API has likely changed."
        else ->
            "Something went wrong. Try again."
    }
}
