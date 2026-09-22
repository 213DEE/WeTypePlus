package cn.dsr213.wetypeplus.bridge

import android.app.Application
import android.os.Bundle
import android.util.Log as AndroidLog
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.Collections
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicInteger

/** Log tag used by every line this module writes. */
const val LOG_TAG = "WeTypePlus"

/** Prefixes that mark a hook installation line as a result rather than a diagnostic. */
private const val AUDIT_SUCCESS = "Success: "
private const val AUDIT_FAILURE = "Failed: "

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

    fun attach(module: XposedModule, classLoader: ClassLoader? = null) {
        this.module = module
        this.classLoader = classLoader
        hookSequence.set(0)
    }

    fun updateClassLoader(loader: ClassLoader?) {
        classLoader = loader
    }

    /** Drops generation-local bookkeeping so a hot reload starts from a clean slate. */
    fun prepareForHotReload() {
        hookSequence.set(0)
        synchronized(hooksInstalled) { hooksInstalled.clear() }
        synchronized(hooksFailed) { hooksFailed.clear() }
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

    internal fun resolveClassLoader(explicit: ClassLoader?): ClassLoader? = explicit ?: classLoader

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
     * Ships the installation result to the settings app, off the calling thread.
     *
     * The caller sits inside the host's `onPackageReady`, i.e. on the input method's main thread,
     * and the receiving end is a provider in a *different* process - one the system may have to
     * start first. Doing that inline would stall the keyboard's start-up for however long a cold
     * process launch takes, so the whole thing runs on its own thread and a failure is only ever
     * a log line.
     */
    fun publishStatusAsync() {
        Thread {
            runCatching { publishStatus() }
                .onFailure { Log.i("Status report skipped: ${it.message ?: it.javaClass.name}") }
        }.apply {
            name = "wetypeplus-status"
            isDaemon = true
        }.start()
    }

    private fun publishStatus() {
        val application = currentApplication() ?: return
        // `Collections.synchronizedSet` guards single calls only - iterating it needs the lock
        // held, and `toList()` iterates.
        val installedNow = synchronized(hooksInstalled) { hooksInstalled.toList() }
        val failedNow = synchronized(hooksFailed) { hooksFailed.toList() }
        val extras = Bundle().apply {
            putInt(SettingsBridge.KEY_WIRE_VERSION, SettingsBridge.WIRE_VERSION)
            putLong(SettingsBridge.KEY_TIMESTAMP, System.currentTimeMillis())
            putString(SettingsBridge.KEY_FRAMEWORK_NAME, frameworkName)
            putString(SettingsBridge.KEY_FRAMEWORK_VERSION, frameworkVersion)
            putLong(SettingsBridge.KEY_FRAMEWORK_VERSION_CODE, frameworkVersionCode)
            putInt(SettingsBridge.KEY_API_VERSION, frameworkApiVersion)
            putInt(SettingsBridge.KEY_MIN_API_VERSION, SettingsBridge.REQUIRED_API_VERSION)
            putString(SettingsBridge.KEY_PROCESS_NAME, currentProcessName())
            putString(SettingsBridge.KEY_HOST_VERSION, hostVersion(application))
            putStringArrayList(SettingsBridge.KEY_INSTALLED, ArrayList(installedNow))
            putStringArrayList(SettingsBridge.KEY_FAILED, ArrayList(failedNow))
        }
        application.contentResolver.call(
            SettingsBridge.CONTENT_URI,
            SettingsBridge.METHOD_REPORT,
            null,
            extras
        )
    }

    private fun currentProcessName(): String = runCatching {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentProcessName")
            .apply { isAccessible = true }
            .invoke(null) as? String
    }.getOrNull() ?: ""

    /** WeType's own version, read from inside WeType's process - no package-visibility filter applies. */
    private fun hostVersion(application: Application): String = runCatching {
        @Suppress("DEPRECATION")
        application.packageManager
            .getPackageInfo(SettingsBridge.HOST_PACKAGE, 0)
            .versionName
    }.getOrNull() ?: ""
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
        val target = Bridge.moduleOrNull()
        if (message is Throwable) {
            val text = "[$level] ${message.message ?: message.javaClass.name}"
            if (target != null) {
                target.log(priority, LOG_TAG, text, message)
            } else {
                AndroidLog.println(priority, LOG_TAG, text)
            }
            return
        }
        val text = "[$level] $message"
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
