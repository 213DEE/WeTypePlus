package cn.dsr213.wetypeplus.bridge

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log as AndroidLog
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.io.FileInputStream
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Collections
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Log tag used by every line this module writes. */
const val LOG_TAG = "WeTypePlus"

/** Prefixes that mark a hook installation line as a result rather than a diagnostic. */
private const val AUDIT_SUCCESS = "Success: "
private const val AUDIT_FAILURE = "Failed: "

/**
 * How many recent log lines the module keeps in memory for the diagnostics screen to fetch.
 *
 * Bounded on purpose: this buffer is shipped across a binder boundary inside a status report, and
 * a report that outgrows the transaction limit is dropped silently by the system - which would
 * look exactly like the failure the feature exists to diagnose. 400 lines of at most a few hundred
 * characters is comfortably inside the limit.
 */
private const val LOG_BUFFER_CAPACITY = 400

/**
 * How long [Bridge]'s reporting thread waits for the host to bind its `Application`.
 *
 * `onPackageReady` runs before that bind and the bind follows immediately, so this normally costs
 * one iteration. A few seconds is the point at which "not yet" has become "not going to happen",
 * and the report falls back to the system context rather than waiting for ever.
 */
private const val CONTEXT_ATTEMPTS = 30
private const val CONTEXT_INTERVAL_MS = 100L

/**
 * The one and only place that talks to libxposed.
 *
 * Everything else in this project is written against the plain `hookBefore` / `hookAfter` shape
 * below, which keeps the hook classes free of framework types and therefore testable and
 * readable. This file is the entire framework coupling of the project.
 *
 * Three rules shape the code here:
 *
 * 1. **Never read a host static while hooks are being installed.** `Class.forName(name, false, …)`
 *    is used everywhere precisely so that resolving a class never runs its static initialiser.
 *    A host `<clinit>` can depend on an `Application` that has not been attached yet, and running
 *    it this early kills the input method process before it starts.
 * 2. **Every registration goes through the interceptor chain.** No hook is installed unless
 *    [attach] has supplied the module instance, so a missing framework fails loudly at install
 *    time instead of silently doing nothing.
 * 3. **The API level is declared, not assumed.** `module.prop` asks for libxposed API 102, so the
 *    API 102 calls below are legal - and the reason that declaration matters belongs right next to
 *    them. A framework *below* the declared level does not fail loudly: it declines to load the
 *    module at all and leaves the user looking at an enabled switch that does nothing. Raising the
 *    required level again therefore means updating `module.prop`, this file, and what the
 *    diagnostics screen tells the user to install.
 */
object Bridge {
    @Volatile
    private var module: XposedModule? = null

    @Volatile
    private var classLoader: ClassLoader? = null

    /** Monotonic suffix for `HookBuilder.setId`, which is how a hook is named in framework logs. */
    private val hookSequence = AtomicInteger(0)

    /** The framework's own identity, recorded at [attach] time and shipped to the settings app. */
    @Volatile
    private var frameworkName: String? = null

    @Volatile
    private var frameworkVersion: String? = null

    @Volatile
    private var frameworkVersionCode: Long = 0L

    @Volatile
    private var frameworkApiVersion: Int = 0

    /**
     * Hook-install results, kept as two ordered sets of labels.
     *
     * Filled from the log rather than from each hook's own return value: `Log.i("Success: …")` /
     * `Log.i("Failed: …")` is already this project's convention for reporting an installation
     * outcome, so reading it back here keeps one source of truth instead of two.
     */
    private val hooksInstalled = Collections.synchronizedSet(LinkedHashSet<String>())

    private val hooksFailed = Collections.synchronizedSet(LinkedHashSet<String>())

    /** Keys already reported through [reportOnce]; keeps a hot-path failure to a single line. */
    private val reportedOnce = Collections.synchronizedSet(HashSet<String>())

    /**
     * A one-line description of the switches in force, supplied by the hook side.
     *
     * Set by `HookSettings` when it starts listening. A lambda rather than a direct reference so
     * that this file keeps knowing nothing about the hook package: the report needs the answer, and
     * which channel produced it is exactly what a "my switch does nothing" report has to say.
     */
    @Volatile
    internal var settingsSummary: (() -> String)? = null

    /**
     * The module's own recent log lines, in the order they happened.
     *
     * This exists because of what a field report actually needs. These lines also go to the
     * framework's log file under `/data/adb/lspd/log`, but those files are `rwxrwx--- root:root`
     * under a `700` parent - measured on the test device - so only root can read them, and asking
     * a user for their log would mean asking them to grant this app root or to reach for a
     * desktop. Holding the last few hundred lines in memory instead lets the log travel inside the
     * status report: no root, no file access, and no parsing of another program's log format.
     *
     * Kept across hot reloads on purpose. It is a record of what this *process* has done, and the
     * previous generation's lines are exactly what someone reading a reload wants to compare
     * against.
     */
    private val logLines = ArrayDeque<String>()

    /** Per-thread formatter: `SimpleDateFormat` is not thread-safe, and hooks log from many threads. */
    private val logClock = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    /**
     * Appends one already-formatted line, dropping the oldest when the buffer is full.
     *
     * Internal rather than private because [Log] is the only caller and it is a sibling object in
     * this file, not a member of this one.
     */
    internal fun appendLogLine(text: String) {
        val stamp = runCatching { logClock.get()?.format(Date()) }.getOrNull().orEmpty()
        val line = if (stamp.isEmpty()) text else "$stamp $text"
        synchronized(logLines) {
            logLines.addLast(line)
            while (logLines.size > LOG_BUFFER_CAPACITY) logLines.removeFirst()
        }
    }

    /** A copy of the buffered lines, oldest first. */
    private fun recentLogLines(): List<String> = synchronized(logLines) { logLines.toList() }

    fun attach(module: XposedModule, classLoader: ClassLoader? = null) {
        this.module = module
        // A null is "no new information", not "forget the host's class loader" - see
        // [updateClassLoader] for what happens when it is taken as the latter.
        if (classLoader != null) this.classLoader = classLoader
        hookSequence.set(0)
    }

    /**
     * Records the host's class loader, ignoring a null.
     *
     * A null is "no new information" rather than "forget the loader": the framework only supplies
     * one on the original `onPackageReady`, and `onHotReloaded` arrives without it.
     *
     * **This alone does not survive a hot reload, and that is the point worth remembering.** A hot
     * reload loads the new module dex in a *new* class loader, so every static in this file starts
     * from its initial value - `classLoader` included, in a generation where `onPackageReady` never
     * runs again. Measured: `Host class loader after hot reload: none`, followed by 21 of 24 hooks
     * failing with `Failed to resolve com.tencent.wetype.plugin.hld.utils.m1`. The loader therefore
     * has to be *re-derived*, which is what [resolveClassLoader] does.
     */
    fun updateClassLoader(loader: ClassLoader?) {
        if (loader != null) classLoader = loader
    }

    /** Drops generation-local bookkeeping so a hot reload starts from a clean slate. */
    fun prepareForHotReload() {
        hookSequence.set(0)
        synchronized(hooksInstalled) { hooksInstalled.clear() }
        synchronized(hooksFailed) { hooksFailed.clear() }
        synchronized(reportedOnce) { reportedOnce.clear() }
    }

    /** Unhooks whatever the previous generation left behind. */
    fun finishHotReload(handles: List<XposedInterface.HookHandle>?) {
        handles?.forEach { handle -> runCatching { handle.unhook() } }
    }

    fun recordFramework(name: String?, version: String?, versionCode: Long, apiVersion: Int) {
        frameworkName = name
        frameworkVersion = version
        frameworkVersionCode = versionCode
        frameworkApiVersion = apiVersion
    }

    /**
     * Files one log line as a hook outcome, when it is one.
     *
     * Called for every line this module emits, so the match has to stay narrow: only the two
     * prefixes the hook installers already use count as results.
     */
    internal fun recordAuditLine(line: String) {
        when {
            line.startsWith(AUDIT_SUCCESS) -> hooksInstalled.add(line.removePrefix(AUDIT_SUCCESS))
            line.startsWith(AUDIT_FAILURE) -> hooksFailed.add(line.removePrefix(AUDIT_FAILURE))
        }
    }

    internal fun moduleOrNull(): XposedModule? = module

    /**
     * The class loader the hooks resolve host classes against.
     *
     * Three sources, in order, and the third is the one that makes a hot reload work:
     *
     * 1. whatever the caller passed explicitly,
     * 2. the loader the framework handed over on `onPackageReady`,
     * 3. **the host `Application`'s own loader**, asked for at the moment it is needed.
     *
     * The third exists because a hot reload - which is what LSPosed does to a module whose APK was
     * updated while the host was running, i.e. what every user does when they install an update -
     * loads the new dex into a new class loader and never calls `onPackageReady` again. Without it
     * the hooks in the new generation resolve against a null loader, the host's classes disappear,
     * and the module installs itself into nothing. Verified both ways on a real device: cold start
     * reports 24 installed / 0 failed, and the same build hot-reloaded reported 0 installed / 21
     * failed until this fallback existed.
     *
     * The host's classes are in WeType's own `base.apk` (`com/tencent/wetype/plugin/hld/…`, checked
     * directly in the installed APK), so the application's `PathClassLoader` does reach them - this
     * is not a plugin loader that has to be hunted down.
     */
    internal fun resolveClassLoader(explicit: ClassLoader?): ClassLoader? =
        explicit ?: classLoader ?: applicationClassLoader()

    private fun applicationClassLoader(): ClassLoader? = runCatching {
        currentApplication()?.classLoader
    }.getOrNull()

    /**
     * The host class loader, as a name, for the log.
     *
     * Worth a line of its own because "which loader did the hooks resolve against" is invisible
     * from every other angle, and a `none` here is exactly what a broken hot reload looks like.
     */
    internal fun classLoaderDescription(): String =
        (classLoader ?: applicationClassLoader())?.javaClass?.name ?: "none"

    internal fun register(
        method: Method,
        kind: String,
        hooker: XposedInterface.Hooker
    ) {
        val target = module ?: error(
            "libxposed module is not attached yet; refusing to hook ${method.name}::$kind"
        )
        val id = buildString {
            append("wetypeplus:")
            append(kind)
            append(':')
            append(method.declaringClass.name)
            append('#')
            append(method.name)
            append(':')
            append(hookSequence.getAndIncrement())
        }
        target.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId(id)
            .intercept(hooker)
    }

    /**
     * The framework's view of a preferences file, or `null` when the framework cannot provide one.
     *
     * Measured, not assumed: on the test device this returns a **read-only** instance whose
     * `edit()` throws `UnsupportedOperationException: Read only implementation`, and it stays
     * empty for the group name this app actually uses. It is therefore a candidate channel, not
     * the channel - [SettingsBridge] records what is known about all of them, and the hook side
     * tries each in turn and logs which one answered.
     *
     * Returns `null` rather than throwing, so a framework without the capability simply moves on to
     * the next channel instead of breaking the hooks.
     */
    internal fun remotePreferences(group: String): SharedPreferences? = runCatching {
        module?.getRemotePreferences(group)
    }.getOrElse { error ->
        // Logged once per group, not once per call: the settings read sits on hot paths, but a
        // framework that cannot provide preferences at all is worth exactly one line, and that
        // line is the only way to tell "unavailable" apart from "the file is empty".
        reportOnce(
            "prefs:$group",
            "Remote preferences unavailable for '$group': " +
                "${error.javaClass.simpleName}: ${error.message}"
        )
        null
    }

    /**
     * The framework's listing of the files it can hand this module, or an empty list.
     *
     * Measured: **empty** on LSPosed 2.2.0, so [remoteFileText] has nothing to open. Kept as a
     * probe rather than deleted because the log line it produces ("Settings channels:
     * remoteFiles=…") is what tells a field report which framework behaviours are in play, and it
     * costs one call per process.
     */
    internal fun remoteFiles(): List<String> = runCatching {
        module?.listRemoteFiles()?.toList().orEmpty()
    }.getOrElse { error ->
        reportOnce(
            "remote-files",
            "Remote file listing unavailable: ${error.javaClass.simpleName}: ${error.message}"
        )
        emptyList()
    }

    /**
     * Reads a file the framework serves for this module, or `null` when it cannot.
     *
     * The path is relative to the module's data directory, which is where this app's own
     * `SharedPreferences` file lives - so this is a synchronous read of the switches from inside
     * WeType's process, needing no IPC and no visibility rule, *if* the framework serves it. The
     * hook side treats a `null` here as "channel unavailable" and falls through.
     */
    internal fun remoteFileText(path: String): String? {
        val text = runCatching {
            module?.openRemoteFile(path)?.use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { it.readBytes().decodeToString() }
            }
        }.getOrElse { error ->
            reportOnce(
                "remote-file:$path",
                "Remote file '$path' unavailable: ${error.javaClass.simpleName}: ${error.message}"
            )
            return null
        }
        // A `null` here is the framework's way of saying "no such file", and it arrives without an
        // exception - so without this line the channel would fail completely silently, which is the
        // one failure mode this project has already been bitten by twice.
        if (text == null) {
            reportOnce("remote-file:$path", "Remote file '$path' unavailable: nothing was served")
        }
        return text
    }

    /** Emits [message] the first time it is seen for [key], so hot paths cannot flood the log. */
    private fun reportOnce(key: String, message: String) {
        if (reportedOnce.add(key)) Log.i(message)
    }

    /**
     * Ships the installation result to the settings app, off the calling thread.
     *
     * The caller sits inside the host's `onPackageReady`, i.e. on the input method's main thread,
     * and the receiving end lives in a *different* process the system may have to start first.
     * Doing that inline would stall the keyboard's start-up, so the whole thing runs on its own
     * thread and a failure is only ever a log line.
     *
     * [trigger] is carried into the log so the two callers - the once-per-process startup report
     * and a report the user asked for from the diagnostics screen - can be told apart in a field
     * log that contains both.
     */
    fun publishStatusAsync(trigger: String = "startup") {
        Thread {
            runCatching { publishStatus(trigger) }
                .onFailure { Log.i("Status report threw: ${it.message ?: it.javaClass.name}") }
        }.apply {
            name = "wetypeplus-status"
            isDaemon = true
        }.start()
    }

    /**
     * Sends one report over a broadcast aimed at this app's receiver.
     *
     * **Why a broadcast and not the two channels that look more natural.** A `ContentProvider`
     * call from the host fails with `Unknown authority`, because Android filters provider
     * authority resolution by package visibility and WeType neither lists this app in its
     * `<queries>` nor can be made to; a framework preferences write fails with `Read only
     * implementation`. Both were measured on the test device. An intent with an explicit component
     * needs no resolution, so it is the one that arrives. See [SettingsBridge].
     *
     * **Every outcome is logged, including the ones that look impossible.** The previous
     * implementation returned silently when there was no `Application` - and `onPackageReady`
     * always runs before `Application` exists, so that guard fired every single time and the
     * diagnostics screen could never show anything. A silent exit is not something this function
     * is allowed to do.
     */
    private fun publishStatus(trigger: String) {
        val context = awaitContext() ?: run {
            Log.i("Status report dropped in ${currentProcessName()} ($trigger): no Context")
            return
        }
        // `Collections.synchronizedSet` guards single calls only - iterating it needs the lock
        // held, and `toList()` iterates.
        val installedNow = synchronized(hooksInstalled) { hooksInstalled.toList() }
        val failedNow = synchronized(hooksFailed) { hooksFailed.toList() }
        val log = recentLogLines()
        val report = Bundle().apply {
            putInt(SettingsBridge.KEY_WIRE_VERSION, SettingsBridge.WIRE_VERSION)
            putLong(SettingsBridge.KEY_TIMESTAMP, System.currentTimeMillis())
            putString(SettingsBridge.KEY_FRAMEWORK_NAME, frameworkName)
            putString(SettingsBridge.KEY_FRAMEWORK_VERSION, frameworkVersion)
            putLong(SettingsBridge.KEY_FRAMEWORK_VERSION_CODE, frameworkVersionCode)
            putInt(SettingsBridge.KEY_API_VERSION, frameworkApiVersion)
            putInt(SettingsBridge.KEY_MIN_API_VERSION, SettingsBridge.REQUIRED_API_VERSION)
            putString(SettingsBridge.KEY_PROCESS_NAME, currentProcessName())
            putString(SettingsBridge.KEY_HOST_VERSION, hostVersionOrEmpty())
            putStringArrayList(SettingsBridge.KEY_INSTALLED, ArrayList(installedNow))
            putStringArrayList(SettingsBridge.KEY_FAILED, ArrayList(failedNow))
            putStringArrayList(SettingsBridge.KEY_LOG, ArrayList(log))
            putString(SettingsBridge.KEY_SETTINGS, settingsSummary?.invoke().orEmpty())
        }
        // `sendBroadcast` returns nothing and throws nothing when a broadcast reaches no one, so
        // this line is the *only* observable outcome. Finding it in the framework log is how a
        // report that nobody received is told apart from one that was never sent at all.
        runCatching { context.sendBroadcast(SettingsBridge.reportIntent().putExtras(report)) }
            .onSuccess {
                Log.i(
                    "Status report sent from ${currentProcessName()} ($trigger): " +
                        "${installedNow.size} hooks installed, ${failedNow.size} failed, " +
                        "${log.size} log lines"
                )
            }
            .onFailure {
                Log.i(
                    "Status report threw in ${currentProcessName()} ($trigger): " +
                        "${it.javaClass.simpleName}: ${it.message}"
                )
            }
    }

    /**
     * A `Context` for this process, waiting briefly if the `Application` is not bound yet.
     *
     * `onPackageReady` runs before `ActivityThread` has bound the `Application` - measured, and the
     * reason the old code reported nothing from any process - and a broadcast needs a `Context`.
     * Waiting is cheap here because the caller is already a thread of its own, and it is bounded:
     * the `Application` is created immediately after `onPackageReady` returns.
     *
     * **There is no fallback to the system context, and that is a measurement rather than a
     * preference.** `ActivityThread.getSystemContext()` does hand back a `Context`, but its package
     * is `android` while the process is WeType's, and the framework refuses calls that mix the two:
     * `registerReceiver` on it fails with `SecurityException: Given caller package android is not
     * running in process ProcessRecord{… :com.tencent.wetype/…}`. A broadcast from it would be
     * rejected the same way. Waiting for the real `Application` is the only thing that works, so a
     * report that cannot get one is dropped and says so.
     */
    private fun awaitContext(): Context? {
        repeat(CONTEXT_ATTEMPTS) { attempt ->
            currentApplication()?.let { return it }
            if (attempt < CONTEXT_ATTEMPTS - 1) runCatching { Thread.sleep(CONTEXT_INTERVAL_MS) }
        }
        return null
    }

    /**
     * Deleted, not deprecated: `publishViaRemotePreferences` and `publishViaProvider`.
     *
     * Both were written from a plausible reading of the APIs and neither one ever delivered a
     * single byte. The framework preferences instance refuses `edit()` with `Read only
     * implementation`, and the provider call dies in resolution with `Unknown authority`. Keeping
     * them as extra "fallbacks" would only have made the next reader repeat the investigation, and
     * the broadcast above is measured to work. See [SettingsBridge] for the table.
     */
    private fun currentProcessName(): String = runCatching {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentProcessName")
            .apply { isAccessible = true }
            .invoke(null) as? String
    }.getOrNull() ?: ""

    /**
     * WeType's own version, read from inside WeType's process - no package-visibility filter applies.
     *
     * Returns empty rather than falling back to the system context: see [awaitContext] for why a
     * context whose package is not the running process cannot be used. An empty value degrades
     * cleanly - the diagnostics screen reads the same version through its own package probe, which
     * needs no framework and no host process at all.
     */
    private fun hostVersionOrEmpty(): String = runCatching {
        @Suppress("DEPRECATION")
        currentApplication()?.packageManager
            ?.getPackageInfo(SettingsBridge.HOST_PACKAGE, 0)
            ?.versionName
    }.getOrNull().orEmpty()
}

/**
 * Resolves a class without forcing its static initialiser (`initialize = false`).
 *
 * Returns `null` instead of throwing so that every hook can degrade to "not applied" when a
 * future host build renames something.
 */
fun loadClassOrNull(className: String, classLoader: ClassLoader? = null): Class<*>? =
    runCatching {
        Class.forName(className, false, Bridge.resolveClassLoader(classLoader))
    }.getOrNull()

/**
 * The `Application` of the process these hooks are running in, or `null` if the process has not
 * attached one yet. Resolved lazily - never at install time.
 */
fun currentApplication(): Application? = runCatching {
    Class.forName("android.app.ActivityThread")
        .getDeclaredMethod("currentApplication")
        .apply { isAccessible = true }
        .invoke(null) as? Application
}.getOrNull()

/**
 * Routed through the framework logger when one is attached, so lines land in the LSPosed module
 * log; falls back to logcat in a plain JVM context.
 */
object Log {
    fun i(message: Any?) = emit(AndroidLog.INFO, message)

    fun e(message: Any?) = emit(AndroidLog.ERROR, message)

    fun e(throwable: Throwable) = emit(AndroidLog.ERROR, throwable)

    private fun emit(priority: Int, message: Any?) {
        val level = if (priority == AndroidLog.ERROR) "E" else "I"
        // Fed in raw, before the `[I] ` decoration: the audit matches on this module's own
        // `Success: ` / `Failed: ` prefixes, which the decoration would hide.
        if (message is String) Bridge.recordAuditLine(message)
        // Buffered first, so what the diagnostics screen shows is the same text the framework log
        // holds - not a second, subtly different rendering of it.
        if (message is Throwable) {
            val text = "[$level] ${message.message ?: message.javaClass.name}"
            Bridge.appendLogLine(text)
            val target = Bridge.moduleOrNull()
            if (target != null) {
                target.log(priority, LOG_TAG, text, message)
            } else {
                AndroidLog.println(priority, LOG_TAG, text)
            }
            return
        }
        val text = "[$level] $message"
        Bridge.appendLogLine(text)
        val target = Bridge.moduleOrNull()
        if (target != null) {
            target.log(priority, LOG_TAG, text)
        } else {
            AndroidLog.println(priority, LOG_TAG, text)
        }
    }
}

private object StaticReceiver

/**
 * The `thisObject` / `args` / `result` triple a callback sees, mirroring the classic Xposed shape
 * so the hook classes read the way they always have.
 */
class MethodHookParam internal constructor(
    val method: Method,
    thisObject: Any?,
    var args: Array<Any?>,
    result: Any? = null
) {
    /**
     * Hooks on instance methods treat this as non-null. Static hooks are handed an internal
     * sentinel instead of `null` so call sites need no null handling.
     */
    val thisObject: Any = thisObject ?: StaticReceiver

    var result: Any? = result
        set(value) {
            field = value
            resultWasSet = true
        }

    internal var resultWasSet: Boolean = false
}

/**
 * Runs [callback] before the original body.
 *
 * Assigning `param.result` short-circuits the call; leaving it untouched proceeds with whatever
 * arguments the callback left in `param.args`. A throwing callback is logged and the original
 * call proceeds unchanged - a broken hook never breaks the keyboard.
 */
fun Method.hookBefore(callback: (MethodHookParam) -> Unit) {
    val method = this
    Bridge.register(method, "before") { chain ->
        val param = MethodHookParam(method, chain.thisObject, chain.args.toTypedArray())
        val failure = runCatching { callback(param) }.exceptionOrNull()
        when {
            failure != null -> {
                Log.e(failure)
                chain.proceed(param.args)
            }

            param.resultWasSet -> param.result

            else -> chain.proceed(param.args)
        }
    }
}

/**
 * Runs [callback] after the original body, with `param.result` holding the real return value.
 * Reassigning `param.result` replaces it.
 */
fun Method.hookAfter(callback: (MethodHookParam) -> Unit) {
    val method = this
    Bridge.register(method, "after") { chain ->
        val originalResult = chain.proceed()
        val param = MethodHookParam(
            method = method,
            thisObject = chain.thisObject,
            args = chain.args.toTypedArray(),
            result = originalResult
        )
        try {
            callback(param)
            param.result
        } catch (throwable: Throwable) {
            Log.e(throwable)
            originalResult
        }
    }
}

private val primitiveToBoxed: Map<Class<*>, Class<*>> = mapOf(
    Boolean::class.javaPrimitiveType!! to Boolean::class.javaObjectType,
    Byte::class.javaPrimitiveType!! to Byte::class.javaObjectType,
    Char::class.javaPrimitiveType!! to Char::class.javaObjectType,
    Double::class.javaPrimitiveType!! to Double::class.javaObjectType,
    Float::class.javaPrimitiveType!! to Float::class.javaObjectType,
    Int::class.javaPrimitiveType!! to Int::class.javaObjectType,
    Long::class.javaPrimitiveType!! to Long::class.javaObjectType,
    Short::class.javaPrimitiveType!! to Short::class.javaObjectType,
    Void.TYPE to Void::class.java
)

internal fun boxed(type: Class<*>): Class<*> = primitiveToBoxed[type] ?: type

/**
 * Signature comparison that treats `int` and `Integer` as the same parameter type, which is what
 * makes it safe to write hooks against literal type tokens.
 */
fun Array<Class<*>>.sameAs(vararg types: Class<*>): Boolean {
    if (size != types.size) return false
    return indices.all { index -> boxed(this[index]) == boxed(types[index]) }
}
