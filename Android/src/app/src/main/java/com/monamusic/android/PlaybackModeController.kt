package com.monamusic.android

import kotlin.random.Random

class PlaybackModeController(
    initialMode: Int,
    private val onModeChanged: (Int) -> Unit
) {
    private var playMode: Int = initialMode.coerceIn(0, 2)
    private val shuffleRoundPlayed = mutableSetOf<Int>()
    private val shuffleRoundQueue = mutableListOf<Int>()

    fun mode(): Int = playMode

    fun setMode(newMode: Int, queueSize: Int, currentIndex: Int) {
        val next = newMode.coerceIn(0, 2)
        playMode = next
        onModeChanged(next)
        if (next == 2) {
            resetShuffleRound(queueSize, currentIndex, markCurrent = true)
        } else {
            clearShuffleState()
        }
    }

    fun resetShuffleRound(queueSize: Int, currentIndex: Int, markCurrent: Boolean) {
        clearShuffleState()
        if (queueSize <= 0) return
        if (markCurrent && currentIndex in 0 until queueSize) {
            shuffleRoundPlayed.add(currentIndex)
        }
        (0 until queueSize)
            .filterNot { shuffleRoundPlayed.contains(it) }
            .shuffled()
            .forEach(shuffleRoundQueue::add)
    }

    fun onQueueChanged(queueSize: Int, currentIndex: Int) {
        if (queueSize <= 0) {
            clearShuffleState()
            return
        }
        shuffleRoundPlayed.removeAll { it !in 0 until queueSize }
        shuffleRoundQueue.removeAll { it !in 0 until queueSize }
        if (playMode == 2 && shuffleRoundQueue.isEmpty()) {
            resetShuffleRound(queueSize, currentIndex, markCurrent = true)
        }
    }

    fun resolveNextIndex(currentIndex: Int, queueSize: Int): Int {
        if (queueSize <= 1) return 0
        return if (playMode == 2) nextShuffleIndex(currentIndex, queueSize)
        else (currentIndex + 1).floorMod(queueSize)
    }

    fun resolvePrevIndex(currentIndex: Int, queueSize: Int): Int {
        if (queueSize <= 1) return 0
        return if (playMode == 2) randomIndexExceptCurrent(currentIndex, queueSize)
        else (currentIndex - 1).floorMod(queueSize)
    }

    fun resolveEndedIndex(currentIndex: Int, queueSize: Int): Int {
        if (queueSize <= 1) return 0
        return when (playMode) {
            1 -> currentIndex.floorMod(queueSize)
            2 -> nextShuffleIndex(currentIndex, queueSize)
            else -> (currentIndex + 1).floorMod(queueSize)
        }
    }

    private fun nextShuffleIndex(currentIndex: Int, queueSize: Int): Int {
        val current = currentIndex.floorMod(queueSize)
        shuffleRoundPlayed.add(current)
        shuffleRoundQueue.removeAll { it == current }
        if (shuffleRoundQueue.isEmpty()) {
            resetShuffleRound(queueSize, current, markCurrent = true)
        }
        return shuffleRoundQueue.firstOrNull()?.also { shuffleRoundQueue.removeAt(0) }
            ?: ((current + 1).floorMod(queueSize))
    }

    private fun randomIndexExceptCurrent(currentIndex: Int, queueSize: Int): Int {
        val current = currentIndex.floorMod(queueSize)
        var next = current
        while (next == current) {
            next = Random.nextInt(queueSize)
        }
        return next
    }

    private fun clearShuffleState() {
        shuffleRoundPlayed.clear()
        shuffleRoundQueue.clear()
    }

    private fun Int.floorMod(mod: Int): Int {
        val value = this % mod
        return if (value < 0) value + mod else value
    }
}
