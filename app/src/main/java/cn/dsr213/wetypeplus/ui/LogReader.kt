package cn.dsr213.wetypeplus.ui

import java.util.concurrent.TimeUnit

/**
 * Reads this module's own lines back out of the system log.
 *
 * The module logs through libxposed, so its lines land in the system log under its own tag - and
 * that log is exactly where the answer to "why did nothing happen" lives: one line per hook,
 * `Success: …` or `Failed: …`, naming the host method it was looking for. Until now the only way
 * to see them was a desktop `adb logcat`, which is no help to anyone reporting a problem.
 *
 * **Root is required.** Since Android 4.1 the log is per-application: a process may only read its
 * own records, and these belong to WeType's process and to the framework. `su` is therefore the
 * only route, which is not a hardship for this app's audience - the module itself cannot be
 * installed without root. A device without it gets [Result.NoRoot] rather than an empty list, so
 * the screen can say which of the two it is.
 *
 * Filtering happens on the device (`grep -i wetypeplus`) rather than here: a full `logcat -d` is
 * megabytes of unrelated chatter, and shipping that across the process boundary just to throw it
 * away would be the slowest part of opening the screen.
 */
internal object LogReader {

    /**
     * How many recent log records to scan before filtering.
     *
     * The module emits a bounded number of lines per device boot - the report budget on each probe
     * is deliberately small - so a few thousand records is generous while keeping the read fast on
     * a busy log.
     */
    private const val SCAN_LINES = 3000

    /** How long `su` gets before the read is abandoned and reported as unavailable. */
    private const val ROOT_TIMEOUT_SECONDS = 15L

    /** Upper bound on what one read may hand to the UI, independent of what the device returns. */
    private const val MAX_LINES = 4000

    sealed interface Result {
        /** Matching lines, oldest first. */
        data class Lines(val value: List<String>) : Result

        /** `su` did not answer - no root, or the prompt was dismissed. */
        data object NoRoot : Result

        data class Failure(val message: String) : Result
    }

    fun read(): Result = runCatching {
        val output = su("logcat -d -t $SCAN_LINES | grep -i wetypeplus")
            ?: return Result.NoRoot
        // Materialised before trimming: `takeLast` is defined on `List`, not on `Sequence`, and the
        // list is bounded anyway - the command above asks `logcat` for at most [SCAN_LINES]
        // records, so nothing unbounded ever reaches memory.
        val lines = output.lineSequence()
            .filter { it.isNotBlank() }
            .toList()
            .takeLast(MAX_LINES)
        Result.Lines(lines)
    }.getOrElse { Result.Failure(it.message ?: it.javaClass.name) }

    /**
     * Empties the log, so the next read shows only what happened after this point.
     *
     * Worth having as an action of its own: the module reports most of what it does in the first
     * moments after WeType starts, and a log that already contains ten boots buries that.
     */
    fun clear(): Boolean = runCatching { su("logcat -c") != null }.getOrDefault(false)

    /**
     * Runs [command] in a root shell and returns its merged output, or `null` if root is missing
     * or unresponsive.
     *
     * The output is drained on its own thread before waiting. A `logcat -d | grep` pipeline easily
     * exceeds the pipe buffer, and a process blocked writing into a full pipe never exits - so
     * waiting first and reading afterwards would deadlock rather than time out.
     */
    private fun su(command: String): String? {
        val process = runCatching {
            ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: return null

        val buffer = StringBuilder()
        val drain = Thread {
            runCatching {
                process.inputStream.bufferedReader().use { input ->
                    val chunk = CharArray(8192)
                    while (true) {
                        val count = input.read(chunk)
                        if (count < 0) break
                        synchronized(buffer) { buffer.appendRange(chunk, 0, count) }
                    }
                }
            }
        }.apply {
            name = "wetypeplus-logreader"
            isDaemon = true
            start()
        }

        val finished = runCatching {
            process.waitFor(ROOT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }.getOrDefault(false)
        if (!finished) {
            runCatching { process.destroy() }
            return null
        }
        runCatching { drain.join(2000) }
        return synchronized(buffer) { buffer.toString() }
    }
}
