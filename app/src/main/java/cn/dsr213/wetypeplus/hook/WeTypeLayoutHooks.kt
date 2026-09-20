package cn.dsr213.wetypeplus.hook

import android.app.Application
import cn.dsr213.wetypeplus.bridge.Log
import cn.dsr213.wetypeplus.bridge.hookAfter
import cn.dsr213.wetypeplus.bridge.hookBefore
import cn.dsr213.wetypeplus.bridge.loadClassOrNull
import cn.dsr213.wetypeplus.bridge.sameAs
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val UTILS = "com.tencent.wetype.plugin.hld.utils."

/** `m1` - WxImeUIUtil, the single source of every keyboard size constant. */
private const val M1 = "${UTILS}m1"

/**
 * `m1$b` - literally the `AppScreenWidthCacheItem`. It is the instance held by `m1.K`, i.e. the
 * one `m1.y(forceRefresh)` forwards to.
 *
 * Note it does **not** declare `d(boolean)`; that lives on the shared base. The class is therefore
 * used here only as an identity token, to tell the width item apart from its siblings.
 */
private const val M1_SCREEN_WIDTH = "${UTILS}m1\$b"

/**
 * `m1$h0` - the cache base shared by `m1$b` (width), `m1$a` (height), `m1$Y` and `m1$W`.
 *
 * `d(forceRefresh)` is the whole of its cache logic:
 * ```
 * if (defaultValue != cacheItem || forceRefresh) cacheItem = c()
 * return cacheItem
 * ```
 * The sibling `m1$a` resolves through `m1$a$a.a()`, whose own log string is
 * `"AppScreenHeightCacheItem:"` - so anything keyed off that helper is the *height*, not the width.
 */
private const val M1_WIDTH_CACHE_BASE = "${UTILS}m1\$h0"

/**
 * `m1.g1()` - the one scale factor every keyboard width figure is multiplied by.
 *
 * The chain behind it is
 * ```
 * m1.g1()  = m1.D.get()
 * m1$U.c() = (x() ?: y(true)) / <design base>     // design base: 1856.0 / 1080.0 / 2156.0
 * ```
 * In portrait the numerator is the screen *width*, so `<design width> * g1()` fits the panel. In
 * landscape the numerator is the screen's *short side* (`m1$x()`), so the same design width maps
 * onto the short side - the keyboard ends up a phone-width column inside a 2364px panel.
 *
 * `g1()` is hooked rather than `m1.D` / `m1$U` on purpose. Reading that field would force the
 * static initialiser of `m1`, and `m1.<clinit>` needs an already-attached `Application` (it goes
 * through `ImeSkinManager`), so touching it while hooks are being installed kills the input method
 * process before it can ever start. Hooking a *method* does not initialise the class, so it is safe.
 * The same rule applies to every other class named in this file: never read a host static at
 * install time.
 *
 * Every width figure is multiplied by this one factor, so correcting it here raises the *ceiling*
 * of the whole width chain at once, without pinning any single value.
 */

/** `float.h` - the floating-keyboard mode object; `h()` returns a `StateFlow<Boolean>`. */
private const val FLOAT_MODE = "com.tencent.wetype.plugin.hld.float.h"

/** `float.f` - the floating-keyboard singleton, used to leave the floating window alone. */
private const val FLOAT_SINGLETON = "com.tencent.wetype.plugin.hld.float.f"

/** `model.Q` - the per-scenario keyboard padding model ("adjust keyboard size"). */
private const val PADDING_MODEL = "com.tencent.wetype.plugin.hld.model.Q"

/** `Z0` - holds `P1()`, the first half of the padding branch test (`Z0.P1() && m1.X1()`). */
private const val PADDING_GATE = "${UTILS}Z0"

/** `adjust.b` - `ImeAdjustViewMgr`; the *single* entry point of the "adjust keyboard size" panel. */
private const val ADJUST_MANAGER = "com.tencent.wetype.plugin.hld.adjust.b"

/** `adjust.f` - `ImeAdjustViewSuper`, the base type returned by `Mgr.j(Context)`. */
private const val ADJUST_VIEW_SUPER = "com.tencent.wetype.plugin.hld.adjust.f"

/** `adjust.e` - `ImeAdjustViewSplit`; `Mgr.j()` builds this instead of `adjust.c` when split. */
private const val ADJUST_VIEW_SPLIT = "com.tencent.wetype.plugin.hld.adjust.e"

/**
 * The adjust panel's two entry points and the argument slots they carry.
 *
 * `Mgr.b()` (live preview) and `Mgr.c()` (confirm) take the very same `(d, left, right, e, gap)`
 * tuple, and both fan out through `model.Q.o(left)` / `p(right)` - the preview into the cached
 * `m1.w0`, the confirm into the persisted model. Rewriting the arguments here therefore covers what
 * you *see* and what gets *saved*.
 *
 * The slot indices are on-device verified: a logged `b(685,764,272,14,260)` was immediately
 * followed by `Q.o() = 764` and `Q.p() = 272`, and `i1.M3`'s own log line spells the same tuple out
 * as `saveKeyboardPadding, height:p1, left:p2, right:p3, bottom:p4, middleSpace:p5`.
 */
private const val ADJUST_PREVIEW = "b"
private const val ADJUST_COMMIT = "c"
private const val ADJUST_ARG_COUNT = 5
private const val ADJUST_LEFT_INDEX = 1
private const val ADJUST_RIGHT_INDEX = 2

/** `i1`'s single-hand setter and `Z0`'s split-keyboard setter, kept mutually exclusive. */
private const val SINGLE_HAND_SETTER = "a5"
private const val SPLIT_SETTER = "V3"

/** Sentinels for the margin baseline and the dragged-side latch. */
private const val MARGIN_UNSEEN = Int.MIN_VALUE
private const val MARGIN_SIDE_NONE = 0
private const val MARGIN_SIDE_LEFT = 1
private const val MARGIN_SIDE_RIGHT = 2

/** How many breadcrumb lines each of the two new hooks may emit. */
private const val MARGIN_REPORT_LIMIT = 16
private const val EXCLUSION_REPORT_LIMIT = 6

/**
 * `adjust.c` - `ImeAdjustViewSingle`, the *merged* ("合体") adjust panel.
 *
 * The host's naming is about how many key blocks the panel drives, not about one-handed mode:
 * `ImeAdjustViewSingle` is the one-block keyboard, `ImeAdjustViewSplit` (`adjust.e`) is the
 * two-block one. One-handed mode is a separate preference (`i1.k2()`) layered on top.
 */
private const val ADJUST_VIEW_SINGLE = "com.tencent.wetype.plugin.hld.adjust.c"

/**
 * `adjust.f.b(model.Q, keyboardWidth)` - the single call that hands the padding model to the panel.
 *
 * Both `adjust.c` and `adjust.e` override it. Everything the scrim and the keyboard body are drawn
 * from is derived inside that method, so it is the one place worth instrumenting.
 */
private const val VIEW_PADDING_SETTER = "b"

/**
 * Budgets for the geometry probes.
 *
 * These exist to be read once, so they are deliberately chatty but strictly capped - a drag emits
 * dozens of events a second and an uncapped dump would drown the logcat ring buffer.
 */
private const val VIEW_PROBE_LIMIT = 8

/** How many distinct preview-path states the panel probe will log. */
private const val PREVIEW_PROBE_LIMIT = 24

/** How many button-bar realignments get a log line. */
private const val BUTTON_BAR_REPORT_LIMIT = 6

/**
 * `ImeAdjustViewSingle` fields holding the last *committed* geometry.
 *
 * Verified against the live panel: `b(model.Q, int)` stores the requested keyboard width into `B`
 * and the left inset derived from the model into `E`. Re-centring the drag preview needs both, and
 * reading them back beats guessing at the host's pixel-to-panel mapping.
 */
private const val COMMIT_WIDTH_FIELD = "B"
private const val COMMIT_LEFT_FIELD = "E"
private const val WRITER_PROBE_LIMIT = 24
private const val RECORD_PROBE_LIMIT = 24
private const val VIEW_TREE_DUMP_LIMIT = 3
private const val VIEW_TREE_DEPTH = 3
private const val VIEW_TREE_CHILDREN = 12

/**
 * The plain (merged, non-split, non-single-hand) left / right margin fields of `a7.b` are `f` / `g`;
 * `model.Q` resolves the pair per scenario:
 * ```
 * Z0.P1() && m1.X1()  -> k / l   (split keyboard on an unfolded screen)
 * i1.k2()             -> h / i   (single-hand)
 * otherwise           -> f / g   (plain)
 * ```
 * The sync works on the *panel arguments* instead of on those fields, precisely so it never has to
 * track which branch the host is on. See `hookAdjustMarginSync`.
 */

/** `i1` - the host settings singleton that owns the single-hand-mode gate. */
private const val SETTINGS = "${UTILS}i1"

/** `model.N` - the keyboard model singleton; `t0()` is the active keyboard kind id. */
private const val KEYBOARD_MODEL = "com.tencent.wetype.plugin.hld.model.N"

/** `keyboard.t` - the scene enum whose instances carry the kind ids compared by `model.N.O1`. */
private const val KEYBOARD_SCENE_CLASS = "com.tencent.wetype.plugin.hld.keyboard.t"

/** The padding fields of `a7.b`, which `model.Q` inherits from. */
private val PADDING_FIELDS = listOf("f", "g", "h", "i", "j", "k", "l")

/** Primitive type tokens, kept non-null so they can be fed to `sameAs(vararg Class<*>)`. */
private val PRIMITIVE_BOOLEAN: Class<*> = Boolean::class.javaPrimitiveType!!
private val PRIMITIVE_INT: Class<*> = Int::class.javaPrimitiveType!!
private val PRIMITIVE_DOUBLE: Class<*> = java.lang.Double::class.javaPrimitiveType!!
private val PRIMITIVE_VOID: Class<*> = java.lang.Void.TYPE

/**
 * The two width providers hung off `m1` as `m1.T` and `m1.X` - only ever *read* here.
 *
 * ```
 * m1$t0.a(kb) = min(2156 * g1(), m1.y(false))
 * m1$P.a(kb)  = min(<user ceiling> * g1(), m1.y(false))
 * ```
 */
private const val M1_WIDTH_PROVIDER_MAIN = "${UTILS}m1\$t0"
private const val M1_WIDTH_PROVIDER_MAX = "${UTILS}m1\$P"

/** The `keyboard.t` scene token both width providers are keyed by. */
private const val KEYBOARD_SCENE = "com.tencent.wetype.plugin.hld.keyboard.t"

/**
 * Removes the two "large screen" restrictions WeType applies on unfolded foldables.
 *
 * Both features are user-toggleable in the module settings and are read live, so flipping a
 * switch takes effect on the next keyboard layout without restarting anything.
 *
 * ## 1. Keyboard width on unfolded screens
 *
 * Every keyboard dimension is derived from one value: the "screen width" reported by
 * `m1.y(forceRefresh)`, implemented by `m1$b.d(forceRefresh)` (inherited from `m1$h0`). Its body
 * reads `Resources.getSystem().displayMetrics` - the *default display*. That `Resources` object is
 * built once and is not reconfigured when the display changes, so once the input method has been
 * started while the device was folded, the value stays phone sized even after unfolding.
 *
 * Two things go wrong, and they are separate:
 *
 * 1. the cached value goes stale, and
 * 2. the scale factor `g1()` measures the *short side* in landscape.
 *
 * (1) is fixed at the source: `m1$h0.d()` reports the *current* screen width, taken from the
 * running app's configuration. That alone is not enough - the keyboard window really is full
 * width already, yet the keyboard *content* stays a narrow column - because a design width (1080)
 * times a short-side-based scale (1.55) equals the short side (1672) of a 2364-wide panel.
 *
 * (2) is fixed at the source of the scale factor: `g1()` is scaled by `screenWidth / shortSide`,
 * which moves the whole width chain out of "short side" space and into "screen width" space in one
 * place. It is deliberately *not* a cap on any provider's return value - overriding the provider
 * instead would pin the keyboard to full width and take the size adjuster's travel away. Because
 * the factor is scaled rather than replaced, the built-in keyboard size adjuster keeps working:
 * shrinking the keyboard still shrinks it, and 100% now means the full screen width instead of a
 * phone-width column. Portrait is a no-op, and the floating keyboard is left alone because it owns
 * its own size.
 *
 * Only the receiver type `m1$b` is touched for the source. `m1$a` (the height item) shares the
 * same base method and must keep returning a height.
 *
 * ## 2. Single-hand mode on unfolded screens
 *
 * `i1.k2()` / `i1.Y2()` both contain `&& !m1.X1()`, where `X1()` answers "is this an unfolded /
 * large screen". On such screens the expression short-circuits before the user's own
 * `ime_enable_single_hand_mode` setting is read, so the toggle looks enabled but has no effect.
 *
 * The second half of the same problem lives in `model.Q`. The keyboard width consumed by the
 * layout is `providerWidth - Q.j() - Q.l()`, and `Q.j()/Q.l()` pick their value by scenario:
 * unfolded (`P1() && X1()`), single-hand (`k2()`) or default. Unfolded wins over single-hand, so
 * even with the gate open the unfolded side margins would still win and the one-hand offset would
 * never be applied.
 *
 * Both are solved the same way: the fold test is bypassed *only while those methods evaluate*
 * (thread-local, consumed on first use). Floating-keyboard detection, keyboard-type detection and
 * the user's own preferences keep their original semantics - the features simply stop being
 * disabled just because the screen is large.
 *
 * Nothing here rewrites the host APK or touches its signature. Every entry point fails closed: if
 * a future host build renames or restructures these members, the affected hook degrades to
 * "not applied" instead of crashing the input method.
 */
internal object WeTypeLayoutHooks {

    /**
     * While set on the calling thread, `m1.X1()` reports `false`.
     *
     * Thread-local rather than a plain boolean because the input method may evaluate keyboard
     * geometry off the main thread, and a global flag would also blanket every unrelated `X1()`
     * caller.
     */
    private val foldGateBypass = ThreadLocal.withInitial { false }

    @Volatile
    private var cachedApplication: Application? = null

    /** Keeps the verification breadcrumb short instead of logging every layout pass. */
    private val widthReportCount = AtomicInteger()

    /** The one-shot geometry dump is emitted at most once per process. */
    private val diagnosticLogged = AtomicBoolean()

    /** Bounded breadcrumb for the left / right padding actually returned by `model.Q`. */
    private val paddingReportCount = AtomicInteger()

    /** Bounded breadcrumb for the natural / uplifted values of the keyboard scale factor. */
    private val scaleReportCount = AtomicInteger()

    /** Bounded breadcrumb for what the two width providers naturally returned (read-only probe). */
    private val providerReportCount = AtomicInteger()

    /** Bounded breadcrumb for the linked left/right margin writes. */
    private val marginSyncReportCount = AtomicInteger()

    /** Bounded breadcrumb for the resolved padding branch / single-hand gate. */
    private val gateReportCount = AtomicInteger()

    /** Bounded breadcrumb for the adjust-panel drag entry points. */
    private val adjustProbeCount = AtomicInteger()

    /** Last `(d,left,right,e,gap)` tuple seen, so the drag probe only logs real changes. */
    @Volatile
    private var lastAdjustTuple: String? = null

    /** Last preview-path state logged, so a held finger does not flood the buffer. */
    @Volatile
    private var lastPreviewState: String? = null
    private val previewProbeCount = AtomicInteger()

    /** Bounded breadcrumb for the single-hand button-bar realignment. */
    private val buttonBarReportCount = AtomicInteger()

    /**
     * `true` while the host's own split-keyboard adjust panel is the active one.
     *
     * Learned from `Mgr.j(Context)`, the branch that chooses `ImeAdjustViewSplit` over
     * `ImeAdjustViewSingle`. That is the host's own definition of "split" and it is *not* the same
     * thing as `Z0.P1()` (`ime_enable_split_keyboard_mode`), which was observed `true` while the
     * keyboard was visibly a single block.
     */
    @Volatile
    private var splitAdjustActive = false

    /**
     * Left / right margins exactly as the panel handed them over, i.e. the *raw* pair.
     *
     * This - not the rewritten pair - is the baseline the next event is compared against, because the
     * panel replays its own stored value for the side that was not dragged (see `applyMarginSync`).
     */
    private val marginLastLeft = AtomicInteger(MARGIN_UNSEEN)
    private val marginLastRight = AtomicInteger(MARGIN_UNSEEN)

    /** Which side was dragged last; diagnostic only - see `reportMargin`. */
    private val marginLatchedSide = AtomicInteger(MARGIN_SIDE_NONE)

    /** Bounded breadcrumb for the single-hand / split-keyboard exclusivity. */
    private val exclusionReportCount = AtomicInteger()

    // Geometry probes. Cheap, read-once instrumentation for the adjust panel; see
    // `hookAdjustGeometryProbe` for what each one is meant to rule in or out.
    private val viewProbeCount = AtomicInteger()
    private val viewTreeCount = AtomicInteger()
    private val writerProbeCount = AtomicInteger()
    private val recordProbeCount = AtomicInteger()

    @Volatile
    private var floatMethod: java.lang.reflect.Method? = null

    @Volatile
    private var floatInstance: Any? = null

    @Volatile
    private var floatResolved = false

    fun install() {
        hookScreenWidthSource()
        hookScaleFactor()
        hookWidthProviderProbe()
        hookKeyboardPadding()
        hookAdjustSplitDetector()
        hookAdjustMarginSync()
        hookAdjustGeometryProbe()
        hookHandSplitExclusion()
        hookSingleHandModeGate()
    }

    /** Drops generation-local state so a hot reload starts from a clean slate. */
    fun prepareForHotReload() {
        foldGateBypass.remove()
        cachedApplication = null
        widthReportCount.set(0)
        paddingReportCount.set(0)
        scaleReportCount.set(0)
        providerReportCount.set(0)
        marginSyncReportCount.set(0)
        gateReportCount.set(0)
        adjustProbeCount.set(0)
        exclusionReportCount.set(0)
        viewProbeCount.set(0)
        viewTreeCount.set(0)
        writerProbeCount.set(0)
        recordProbeCount.set(0)
        previewProbeCount.set(0)
        buttonBarReportCount.set(0)
        lastPreviewState = null
        lastAdjustTuple = null
        splitAdjustActive = false
        marginLastLeft.set(MARGIN_UNSEEN)
        marginLastRight.set(MARGIN_UNSEEN)
        marginLatchedSide.set(MARGIN_SIDE_NONE)
        diagnosticLogged.set(false)
        floatMethod = null
        floatInstance = null
        floatResolved = false
        HookSettings.prepareForHotReload()
    }

    // ------------------------------------------------------------- keyboard width source

    /**
     * `m1$h0.d(forceRefresh)` - the shared cache entry point, filtered down to the width item.
     *
     * Overriding the *return* also sidesteps the memoisation, which would otherwise pin the result
     * to whatever the first layout after boot computed (a phone-width value if the device started
     * folded).
     */
    private fun hookScreenWidthSource() {
        runCatching {
            val widthItem = loadClassOrNull(M1_SCREEN_WIDTH)
                ?: error("Failed to resolve $M1_SCREEN_WIDTH")
            val base = loadClassOrNull(M1_WIDTH_CACHE_BASE)
                ?: error("Failed to resolve $M1_WIDTH_CACHE_BASE")
            val method = base.declaredMethods.firstOrNull { candidate ->
                candidate.parameterTypes.sameAs(PRIMITIVE_BOOLEAN) &&
                    candidate.returnType == PRIMITIVE_INT
            }?.apply { isAccessible = true }
                ?: throw NoSuchMethodException("$M1_WIDTH_CACHE_BASE#d(boolean): Int")

            method.hookAfter { param ->
                // `m1$a` (height), `m1$Y` and `m1$W` share this method - leave them alone.
                if (!widthItem.isInstance(param.thisObject)) return@hookAfter
                applyScreenWidthOverride(param)
            }
            Log.i("Success: Report real screen width via $M1_WIDTH_CACHE_BASE.${method.name}()")
        }.onFailure { error ->
            Log.i("Failed: Report real screen width via $M1_WIDTH_CACHE_BASE")
            Log.i(error)
        }
    }

    private fun applyScreenWidthOverride(param: cn.dsr213.wetypeplus.bridge.MethodHookParam) {
        if (!HookSettings.unlockKeyboardWidth) return
        val screenWidth = currentScreenWidthPx()
        if (screenWidth <= 0) return
        param.result = screenWidth
        if (widthReportCount.incrementAndGet() <= 3) {
            Log.i("Keyboard width source: real screen width = ${screenWidth}px")
        }
        logGeometryOnce()
    }

    /**
     * One bounded dump of the geometry inputs, so a field report can be diagnosed without a
     * debugger. `Resources.getSystem()` is included precisely because it is the value that goes
     * stale on a foldable - comparing it with the process resources is the whole point.
     */
    private fun logGeometryOnce() {
        if (!diagnosticLogged.compareAndSet(false, true)) return
        runCatching {
            val width = currentScreenWidthPx()
            val height = runCatching {
                currentApplication()?.resources?.displayMetrics?.heightPixels ?: 0
            }.getOrDefault(0)
            val system = runCatching {
                android.content.res.Resources.getSystem().displayMetrics
            }.getOrNull()
            Log.i(
                "Geometry: app=${width}x${height}" +
                    " system=${system?.widthPixels}x${system?.heightPixels}" +
                    " widthItem=${callM1("y", false)} shortSide=${callM1("x")}" +
                    " g1()=${callM1("g1")} X1()=${callM1("X1")} W1()=${callM1("W1")}" +
                    " Q1()=${callM1("Q1")}"
            )
        }.onFailure { Log.i(it) }
    }

    /** Reflectively calls a method on the `m1` singleton (field `a`). */
    private fun callM1(name: String, vararg args: Any?): Any? =
        callOnSingleton(M1, "a", name, *args)

    /** Reflectively calls a method on a host singleton stored in a static field. */
    private fun callOnSingleton(
        className: String,
        instanceField: String,
        methodName: String,
        vararg args: Any?
    ): Any? = runCatching {
        val cls = loadClassOrNull(className) ?: return@runCatching null
        val instance = cls.getDeclaredField(instanceField).apply { isAccessible = true }.get(null)
            ?: return@runCatching null
        val method = cls.declaredMethods.firstOrNull {
            it.name == methodName && it.parameterTypes.size == args.size
        }?.apply { isAccessible = true } ?: return@runCatching null
        method.invoke(instance, *args)
    }.getOrNull()

    // --------------------------------------------------------------- keyboard scale factor

    /**
     * Raises the *ceiling* of every keyboard-width calculation instead of overwriting a result.
     *
     * `m1.g1()` is the single factor multiplied into every width figure. In landscape its numerator
     * is the short side, so a design width lands on a phone-width column inside a wide panel.
     * Scaling the factor by `screenWidth / shortSide` moves the whole width chain into screen-width
     * space at once, and it stays proportional:
     * - portrait is a no-op (`screenWidth == shortSide`),
     * - the size adjuster keeps its full travel, because it multiplies *whatever* the user asked for,
     * - 100% now means the full screen width instead of the short side.
     *
     * Overriding a provider's return value instead would pin the keyboard to full width and take the
     * adjuster's travel away, so this deliberately does not do that.
     */
    private fun hookScaleFactor() {
        runCatching {
            val m1Class = loadClassOrNull(M1) ?: error("Failed to resolve $M1")
            val method = m1Class.declaredMethods.firstOrNull { candidate ->
                candidate.name == "g1" &&
                    candidate.parameterTypes.isEmpty() &&
                    candidate.returnType == PRIMITIVE_DOUBLE
            }?.apply { isAccessible = true }
                ?: throw NoSuchMethodException("$M1#g1(): Double")

            method.hookAfter { param ->
                val natural = param.result as? Double ?: return@hookAfter
                val uplifted = upliftScaleFactor(natural)
                if (scaleReportCount.incrementAndGet() <= 6) {
                    Log.i("Scale factor: natural=$natural uplifted=$uplifted")
                }
                if (uplifted != natural) param.result = uplifted
            }
            Log.i("Success: Raise keyboard width ceiling via $M1.g1()")
        }.onFailure { error ->
            Log.i("Failed: Raise keyboard width ceiling via $M1.g1()")
            Log.i(error)
        }
    }

    /** `g1()` moved from short-side space into screen-width space; portrait is a no-op. */
    private fun upliftScaleFactor(natural: Double): Double {
        if (natural <= 0.0) return natural
        if (!HookSettings.unlockKeyboardWidth) return natural
        val metrics = currentMetrics() ?: return natural
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val shortSide = if (width < height) width else height
        // Portrait: the short side *is* the width, so there is nothing to move.
        if (shortSide <= 0 || width <= shortSide) return natural
        if (floatingSurfaceActive() || floatingModeFlag()) return natural
        return natural * width / shortSide
    }

    /** True while the floating keyboard is the active surface - it must keep its own size. */
    private fun floatingSurfaceActive(): Boolean {
        if (!floatResolved) {
            floatResolved = true
            runCatching {
                val cls = loadClassOrNull(FLOAT_SINGLETON) ?: return@runCatching
                floatInstance = cls.declaredFields.firstOrNull { field ->
                    java.lang.reflect.Modifier.isStatic(field.modifiers) &&
                        field.type.isAssignableFrom(cls)
                }?.apply { isAccessible = true }?.get(null)
                floatMethod = cls.declaredMethods.firstOrNull {
                    it.name == "V" && it.parameterTypes.isEmpty()
                }?.apply { isAccessible = true }
            }
        }
        val method = floatMethod ?: return false
        val instance = floatInstance ?: return false
        return runCatching { method.invoke(instance) == true }.getOrDefault(false)
    }

    /**
     * `float.h.h()` - the floating-keyboard *mode* flag, which the size code consults before it ever
     * asks whether the window is showing. Read from its `StateFlow` value.
     */
    private fun floatingModeFlag(): Boolean = runCatching {
        val cls = loadClassOrNull(FLOAT_MODE) ?: return@runCatching false
        val instance = cls.declaredFields.firstOrNull { field ->
            java.lang.reflect.Modifier.isStatic(field.modifiers) &&
                field.type.isAssignableFrom(cls)
        }?.apply { isAccessible = true }?.get(null) ?: return@runCatching false
        val flow = cls.declaredMethods.firstOrNull {
            it.name == "h" && it.parameterTypes.isEmpty()
        }?.apply { isAccessible = true }?.invoke(instance) ?: return@runCatching false
        val getter = flow.javaClass.methods.firstOrNull {
            it.name == "getValue" && it.parameterTypes.isEmpty()
        } ?: return@runCatching false
        getter.invoke(flow) == true
    }.getOrDefault(false)

    // ------------------------------------------------------------- width providers (read-only)

    /**
     * Logs what the two width providers compute, without touching the result.
     *
     * This exists to answer one question with data rather than guesswork: does the host's own
     * "adjust keyboard size" panel move any of these numbers, and by how much. Both providers are
     * `min(<design width> * g1(), m1.y(false))`, so a saturated second term would explain a keyboard
     * that looks fixed no matter where the user drags the slider.
     */
    private fun hookWidthProviderProbe() {
        listOf(
            M1_WIDTH_PROVIDER_MAIN to "T",
            M1_WIDTH_PROVIDER_MAX to "X"
        ).forEach { (className, label) ->
            runCatching {
                val owner = loadClassOrNull(className)
                    ?: error("Failed to resolve $className")
                val method = owner.declaredMethods.firstOrNull { candidate ->
                    candidate.name == "a" &&
                        candidate.parameterTypes.size == 1 &&
                        candidate.returnType == PRIMITIVE_INT &&
                        runCatching { candidate.parameterTypes[0].name == KEYBOARD_SCENE }
                            .getOrDefault(false)
                }?.apply { isAccessible = true }
                    ?: throw NoSuchMethodException("$className#a($KEYBOARD_SCENE): Int")

                method.hookAfter { param ->
                    if (providerReportCount.incrementAndGet() <= 10) {
                        Log.i("Probe m1.$label: value=${param.result} cap=${callM1("y", false)}")
                    }
                }
                Log.i("Success: Probe keyboard width provider via $className.a()")
            }.onFailure { error ->
                Log.i("Failed: Probe keyboard width provider via $className")
                Log.i(error)
            }
        }
    }

    /**
     * The inputs of the keyboard-width chain, logged at layout time. Read-only, and only after a
     * padding getter has already run - i.e. once the host is fully up, so `m1` is safe to touch.
     */
    private fun layoutProbe(): String = runCatching {
        StringBuilder()
            .append("screenW=").append(currentScreenWidthPx())
            .append(" shortSide=").append(callM1("x"))
            .append(" cap=").append(callM1("y", false))
            .append(" W1=").append(callM1("W1"))
            .append(" Q1=").append(callM1("Q1"))
            .append(" P1=").append(callOnSingleton(PADDING_GATE, "a", "P1"))
            .append(" g1=").append(callM1("g1"))
            .toString()
    }.getOrElse { "layoutProbe failed: $it" }

    // ------------------------------------------------------------- linked side margins

    /**
     * Keeps the keyboard centred while it is being resized, by mirroring the *delta* of whichever
     * margin the user just dragged onto the other one.
     *
     * The user's own definition of "sync" (2026-09-21): 「左右留白联动，左边往右缩多少，右边就往左
     * 缩多少，保持键盘始终居中」- merged and split keyboards both treat their two margins as one
     * centred whole; only one-handed mode is deliberately left-aligned or right-aligned. Shifting
     * both sides by the same delta preserves whatever symmetry the pair already has, so a centred
     * keyboard stays centred for the whole drag.
     *
     * On-device logs show the panel is not symmetric at rest: `ImeAdjustViewSingle` carries one
     * handle per side and hands both over at once as `Mgr.b(d, left, right, e, gap)`, with the
     * untouched side replayed verbatim out of the panel's own store. Comparing each argument against
     * the previous call's is what makes "which side moved" detectable without duplicating the host's
     * branch logic.
     *
     * Two corrections are folded in here, both driven by on-device evidence:
     *
     * - *2026-09-21 a*: an earlier revision mirrored the fields *inside* `model.Q.o()` / `p()`. That
     *   could never work - both setters run back to back on every event, so the mirror written by
     *   `o()` was overwritten by `p()` a moment later. Rewriting the arguments at the single entry
     *   point keeps the preview and the committed value consistent by construction.
     * - *2026-09-21 b*: a second revision wrote the dragged side's **absolute value** into both
     *   slots. That is not centring, it is re-basing, and it is expensive: the host computes its own
     *   `keyboardWidth = totalWidth - Q.j() - Q.l()`, so replacing an observed `(386, 1101)` pair
     *   with `(1101, 1101)` silently removes 715px of keyboard width. That is the "over-shrunk,
     *   squashed together" report, and it also displaces the keyboard body relative to the scrim.
     */
    private fun hookAdjustMarginSync() {
        runCatching {
            val manager = loadClassOrNull(ADJUST_MANAGER)
                ?: error("Failed to resolve $ADJUST_MANAGER")
            var installed = 0
            listOf(ADJUST_PREVIEW, ADJUST_COMMIT).forEach { name ->
                val method = manager.declaredMethods.firstOrNull { candidate ->
                    candidate.name == name &&
                        candidate.parameterTypes.size == ADJUST_ARG_COUNT &&
                        candidate.parameterTypes.all { it == PRIMITIVE_INT } &&
                        candidate.returnType == PRIMITIVE_VOID
                }?.apply { isAccessible = true } ?: return@forEach

                method.hookBefore { param -> applyMarginSync(name, param.args) }
                installed++
            }
            if (installed == 0) error("No margin entry points matched in $ADJUST_MANAGER")
            Log.i("Success: Sync keyboard side margins via $ADJUST_MANAGER.b()/c()")
        }.onFailure { error ->
            Log.i("Failed: Sync keyboard side margins via $ADJUST_MANAGER.b()/c()")
            Log.i(error)
        }
    }

    /**
     * Rewrites `(d, left, right, e, gap)` so the keyboard sits centred, without changing its width.
     *
     * The rule is one line: hand back `(sum / 2, sum - sum / 2)` where `sum = left + right`. See the
     * comments inside for why the sum must survive untouched and the difference must not, and for the
     * two earlier revisions the on-device logs disproved.
     *
     * **The baseline is the panel's raw input, not the value we wrote back.** That distinction still
     * matters for detecting a fresh event versus a re-emission. The panel keeps its *own* stored pair
     * and re-emits it on every frame of a drag; the stored side is sticky and does not follow what
     * reached the host (measured: it stayed pinned at `997` for four consecutive events while the host
     * was applying `490`). Comparing the next raw pair against what we wrote therefore makes that
     * stale side look like the dragged one on the very next event:
     * ```
     * Adjust b: left,right=490,997 -> 490,490 | side=1
     * Adjust b: left,right=490,997 -> 997,997 | side=2   <- same input, opposite output
     * ```
     * So: compare raw against raw, and when the raw pair is unchanged (the panel re-emitting while the
     * finger is held) simply hold the previous output instead of re-deciding.
     *
     * Both slots are always written back, so the host never sees a half-rewritten argument list.
     *
     * Centring is not a preference. [2026-09-21] It used to sit behind a `syncSideMargins` switch;
     * that switch is gone, because a stored `false` from an earlier install - or any settings read
     * that came back empty - would have handed the keyboard back the broken, permanently off-centre
     * layout this function exists to fix. Single-hand mode is the only remaining short circuit.
     */
    private fun applyMarginSync(label: String, args: Array<Any?>?) {
        if (args == null || args.size != ADJUST_ARG_COUNT) return
        val left = args[ADJUST_LEFT_INDEX] as? Int ?: return
        val right = args[ADJUST_RIGHT_INDEX] as? Int ?: return

        val split = splitAdjustActive
        val hand = singleHandActive()

        // Only one-handed mode is left alone: there the keyboard is meant to hug one edge, so
        // centring it would destroy the mode outright.
        //
        // The split keyboard needs no special case. Its panel hands over an already-symmetric pair -
        // on-device: `Adjust b: left,right=99,99`, `=258,258`, `=80,80`, `=71,71`, i.e. the two
        // blocks are scaled as one centred whole and the difference is already zero - so `sum / 2`
        // returns exactly what came in. [CORRECTION 2026-09-21] An intermediate revision excluded
        // the split panel only in its comment but not in its code, and its delta-mirroring then
        // turned `(99, 99)` into `(-1047, 99)`: a negative inset, which visibly broke a keyboard
        // that had been working. Centring is an identity transform on a symmetric pair, so the
        // split board is now safe by construction rather than by a branch.
        if (hand) {
            args[ADJUST_LEFT_INDEX] = left
            args[ADJUST_RIGHT_INDEX] = right
            rememberMargins(left, right)
            reportMargin(label, left, right, left, right, false, MARGIN_SIDE_NONE, false, false,
                split, hand)
            return
        }

        val previousLeft = marginLastLeft.get()
        val previousRight = marginLastRight.get()
        val first = previousLeft == MARGIN_UNSEEN || previousRight == MARGIN_UNSEEN
        val repeated = !first && left == previousLeft && right == previousRight

        var side = MARGIN_SIDE_NONE
        if (!first && !repeated) {
            val leftMoved = left != previousLeft
            val rightMoved = right != previousRight
            side = when {
                leftMoved && !rightMoved -> MARGIN_SIDE_LEFT
                rightMoved && !leftMoved -> MARGIN_SIDE_RIGHT
                // Both differ: the panel replayed a stale value for the untouched side, so the
                // "only one side moved" test cannot separate them - the last dragged side can.
                leftMoved || rightMoved -> marginLatchedSide.get()
                else -> MARGIN_SIDE_NONE
            }
        }
        if (side != MARGIN_SIDE_NONE) marginLatchedSide.set(side)

        // Centre, unconditionally - including on the first event, so opening the adjust panel while
        // the keyboard sits off-centre shows it centred straight away rather than only once a finger
        // moves. `side` above is now purely diagnostic: the action no longer depends on which handle
        // moved, only on the pair the panel handed over.
        //
        // The host's own arithmetic (`Mgr.w()` ends with
        // `keyboardWidth = m1.z1(keyboardType, 1, null) - Q.j() - Q.l()`) makes the pair carry two
        // independent jobs:
        //
        //   * `left + right`  -> how WIDE the keyboard is (the sum is subtracted from the total)
        //   * `left - right`  -> WHERE it sits (the difference is the off-centre offset)
        //
        // So the only way to centre without touching the width the user just dragged to is to keep
        // the sum and zero the difference - i.e. hand back `sum / 2` on both sides. Splitting the sum
        // is also an identity transform on an already-symmetric pair, which is exactly what the split
        // panel emits (`99,99` / `258,258` / `80,80` / `71,71` on device), so that board is correct
        // by construction and needs no branch of its own.
        //
        // Two earlier revisions got this wrong in opposite directions, and the on-device logs name
        // both:
        //
        //   [1.0.1-alpha] `outLeft = outRight = dragged`. Zeroed the difference (centred, good) but
        //   re-based the sum onto the dragged side, inflating it by `|left - right|` per event.
        //   Observed `(386, 1101) -> (1101, 1101)`: 715px of keyboard width gone in one frame - the
        //   "over-shrunk, squashed together" report.
        //
        //   [1.0.3-alpha] shifted both sides by the *delta* instead, preserving `left - right`. An
        //   honest sum, but it can never centre anything: the panel's pair is not symmetric to begin
        //   with (`Adjust b: left,right=79,530` opens the drag) and preserving a non-zero difference
        //   keeps the keyboard off-centre for the whole gesture. The "合体键盘没有始终保持居中"
        //   report. Worse, it also applied that delta to the split board, turning its symmetric
        //   `(99, 99)` into `(-1047, 99)` - a negative inset, which broke a keyboard that had been
        //   working.
        //
        // Preserving the sum also keeps this safe at the extremes: `left + right` is by construction
        // a pair the panel itself produced, so the keyboard width can never collapse the way a
        // re-based sum could.
        //
        // Both sides take `(left + right) / 2`, so the pair is *exactly* symmetric - the difference
        // is a hard zero, not "one pixel off" - and the sum can only ever shrink by the parity bit,
        // never grow. A smaller sum means a *wider* keyboard, so this rounding direction can never
        // squeeze the board: at worst it hands back one pixel the user had already given up. (The
        // mirror-image choice, `outRight = sum - outLeft`, would preserve the sum exactly but leave
        // an odd pair 1px off centre, which contradicts "始终保持居中".)
        val half = (left + right) / 2
        val outLeft = half
        val outRight = half

        args[ADJUST_LEFT_INDEX] = outLeft
        args[ADJUST_RIGHT_INDEX] = outRight
        rememberMargins(left, right)
        reportMargin(label, left, right, outLeft, outRight,
            outLeft != left || outRight != right, side, first, repeated, split, hand)
    }

    /** Records the panel's raw pair, which is the baseline the *next* event is compared against. */
    private fun rememberMargins(rawLeft: Int, rawRight: Int) {
        marginLastLeft.set(rawLeft)
        marginLastRight.set(rawRight)
    }

    private fun reportMargin(
        label: String,
        rawLeft: Int,
        rawRight: Int,
        outLeft: Int,
        outRight: Int,
        applied: Boolean,
        side: Int,
        first: Boolean,
        repeated: Boolean,
        split: Boolean,
        hand: Boolean,
    ) {
        val tuple = "$label:$rawLeft,$rawRight->$outLeft,$outRight|$side|$applied|$first|$repeated"
        if (tuple == lastAdjustTuple) return
        lastAdjustTuple = tuple
        if (marginSyncReportCount.incrementAndGet() > MARGIN_REPORT_LIMIT) return
        Log.i(
            "Adjust $label: left,right=$rawLeft,$rawRight -> $outLeft,$outRight" +
                " | applied=$applied side=$side first=$first hold=$repeated" +
                " split=$split hand=$hand"
        )
    }

    /** `i1.k2()` - the host's single-hand gate; in that mode the two margins are meant to differ. */
    private fun singleHandActive(): Boolean =
        runCatching { callOnSingleton(SETTINGS, "a", "k2") == true }.getOrDefault(false)

    /**
     * `adjust.b.j(Context)` - the factory that picks `ImeAdjustViewSplit` over `ImeAdjustViewSingle`.
     *
     * This is the host's *own* notion of "split keyboard", and it is the only reliable one: the
     * panel that drives the left / right margins is chosen right here, whereas `Z0.P1()`
     * (`ime_enable_split_keyboard_mode`) was observed `true` while the keyboard was visibly a
     * single block. Reading the returned view's class touches no host static, so the
     * never-read-a-host-static-at-install rule still holds.
     */
    private fun hookAdjustSplitDetector() {
        runCatching {
            val manager = loadClassOrNull(ADJUST_MANAGER)
                ?: error("Failed to resolve $ADJUST_MANAGER")
            val factory = manager.declaredMethods.firstOrNull { candidate ->
                candidate.name == "j" &&
                    candidate.parameterTypes.size == 1 &&
                    candidate.returnType.name == ADJUST_VIEW_SUPER
            }?.apply { isAccessible = true }
                ?: throw NoSuchMethodException("$ADJUST_MANAGER#j(Context)")

            factory.hookAfter { param ->
                val view = param.result
                splitAdjustActive = view != null && view.javaClass.name == ADJUST_VIEW_SPLIT
                Log.i("Adjust view: ${view?.javaClass?.name} -> split=$splitAdjustActive")
            }
            Log.i("Success: Track adjust panel variant via $ADJUST_MANAGER.j()")
        }.onFailure { error ->
            Log.i("Failed: Track adjust panel variant via $ADJUST_MANAGER.j()")
            Log.i(error)
        }
    }

    /**
     * Makes the single-hand mode and the split keyboard mutually exclusive.
     *
     * The host keeps them as two independent preferences, so both can read `true` at once even
     * though they describe the same layout decision - and the keyboard that comes out is neither.
     * Switching either one on now switches the other off.
     *
     * Only the "on" direction acts, and that is also what keeps this re-entrancy-safe: the call
     * made into the *other* setter carries `false`, so that hook takes no action of its own.
     */
    private fun hookHandSplitExclusion() {
        hookBooleanSetter(SETTINGS, SINGLE_HAND_SETTER, "single-hand") {
            callOnSingleton(PADDING_GATE, "a", SPLIT_SETTER, false)
        }
        hookBooleanSetter(PADDING_GATE, SPLIT_SETTER, "split-keyboard") {
            callOnSingleton(SETTINGS, "a", SINGLE_HAND_SETTER, false)
        }
    }

    /**
     * Runs [onEnabled] after `className#methodName(Z)` is called with `true`.
     *
     * The setter `Method` is resolved here rather than through `callOnSingleton`, because the hook
     * has to be installed on the very method the host will invoke.
     */
    private fun hookBooleanSetter(
        className: String,
        methodName: String,
        label: String,
        onEnabled: () -> Unit
    ) {
        val owner = loadClassOrNull(className)
        if (owner == null) {
            Log.i("Failed: Enforce $label exclusivity - cannot resolve $className")
            return
        }
        val setter = owner.declaredMethods.firstOrNull { candidate ->
            candidate.name == methodName &&
                candidate.parameterTypes.sameAs(PRIMITIVE_BOOLEAN) &&
                candidate.returnType == PRIMITIVE_VOID
        }
        if (setter == null) {
            Log.i("Failed: Enforce $label exclusivity - no $className#$methodName(Z)")
            return
        }
        runCatching {
            setter.isAccessible = true
            setter.hookAfter { param ->
                if (param.args?.firstOrNull() != true) return@hookAfter
                onEnabled()
                if (exclusionReportCount.incrementAndGet() <= EXCLUSION_REPORT_LIMIT) {
                    Log.i("Exclusion: $label enabled -> the other mode was cleared")
                }
            }
            Log.i("Success: Enforce $label exclusivity via $className.$methodName()")
        }.onFailure { error ->
            Log.i("Failed: Enforce $label exclusivity via $className.$methodName()")
            Log.i(error)
        }
    }

    /** Reads one `int` field, walking up to whichever superclass declares it. */
    private fun readIntField(instance: Any?, name: String): Int {
        if (instance == null) return Int.MIN_VALUE
        runCatching {
            var target: Class<*>? = instance.javaClass
            while (target != null) {
                val field = target.declaredFields.firstOrNull {
                    it.name == name && it.type == PRIMITIVE_INT
                }
                if (field != null) {
                    field.isAccessible = true
                    return field.getInt(instance)
                }
                target = target.superclass
            }
        }
        return Int.MIN_VALUE
    }

    /** Writes an `a7.b` int field, walking up to whichever superclass declares it. */
    private fun setIntField(instance: Any?, name: String, value: Int) {
        if (instance == null) return
        runCatching {
            var target: Class<*>? = instance.javaClass
            while (target != null) {
                val field = target.declaredFields.firstOrNull {
                    it.name == name && it.type == PRIMITIVE_INT
                }
                if (field != null) {
                    field.isAccessible = true
                    field.setInt(instance, value)
                    return
                }
                target = target.superclass
            }
        }
    }

    // ------------------------------------------------------------- adjust-keyboard padding

    /**
     * `model.Q.j()` / `model.Q.l()` - the left / right margin subtracted from the keyboard width.
     *
     * These are only *observed* here, never overridden. `model.Q` picks its backing field with
     * ```
     * Z0.P1() && m1.X1()  -> k / l   (split keyboard)
     * i1.k2()             -> h / i   (single-hand)
     * otherwise           -> f / g   (default / joined)
     * ```
     * `Q.o(I)` / `Q.p(I)` are the only writers, and they resolve the field with the very same test.
     *
     * [CORRECTION 2026-09-20] An earlier revision of this comment claimed that faking `X1()` made the
     * getter and the setter disagree (`o()` writing `k` while `j()` read `f`), which would explain a
     * slider that moves without changing the keyboard. On-device probing disproved it: `Z0.P1()`
     * reports false, so the first branch is never taken and that whole theory was a no-op. The
     * bypass it justified has been removed. Kept here so the dead end is not re-explored.
     */
    private fun hookKeyboardPadding() {
        runCatching {
            val owner = loadClassOrNull(PADDING_MODEL)
                ?: error("Failed to resolve $PADDING_MODEL")
            var installed = 0
            listOf("j", "l").forEach { name ->
                val method = owner.declaredMethods.firstOrNull { candidate ->
                    candidate.name == name &&
                        candidate.parameterTypes.isEmpty() &&
                        candidate.returnType == PRIMITIVE_INT
                }?.apply { isAccessible = true } ?: return@forEach
                method.hookAfter { param ->
                    foldGateBypass.set(false)
                    if (paddingReportCount.incrementAndGet() <= 24) {
                        Log.i(
                            "Keyboard padding: Q.$name() = ${param.result}" +
                                " fields[${paddingFields(param.thisObject)}] | ${layoutProbe()}"
                        )
                    }
                }
                installed++
            }
            if (installed == 0) error("No padding getters matched in $PADDING_MODEL")
            Log.i("Success: Observe keyboard padding via $PADDING_MODEL.j()/l()")
        }.onFailure { error ->
            Log.i("Failed: Observe keyboard padding via $PADDING_MODEL")
            Log.i(error)
        }
    }

    // ------------------------------------------------------------------ geometry probes

    /**
     * Read-once instrumentation for the merged adjust panel. It exists to settle the two questions
     * that static reading could not.
     *
     * **1. Which View is the dark scrim, and what decides its edges?** `adjust.c.b(Q, width)` is the
     * call in which the host hands the padding model down to the panel, so every number that shapes
     * the scrim and the keyboard body is in scope there. The tree dump is taken from a *posted*
     * runnable, because at setter time the children still carry the previous frame's bounds.
     *
     * **2. Does the margin rewrite reach the host at all?** `Mgr.b()` fans out to `m1.B2()` (the
     * preview record). If `Mgr.b` logs a synced pair while `B2` logs the raw one, then the
     * `param.args` write-back is not taking effect and the fix has to move elsewhere.
     *
     * One host invariant is worth writing down, because everything else obeys it. `Mgr.w()` ends
     * with:
     * ```
     * keyboardWidth = m1.z1(keyboardType, 1, null) - Q.j() - Q.l()
     * Mgr.C(Q, keyboardWidth)
     * ```
     * so `left + keyboardWidth + right == totalWidth` holds by construction. Rewriting one side
     * without compensating the other therefore *both* moves and resizes the keyboard - which is the
     * "over-shrunk, squashed together" the user reported.
     */
    private fun hookAdjustGeometryProbe() {
        hookViewPaddingSetter()
        hookPaddingWriters()
        hookPaddingRecords()
        hookAdjustPanelPreview()
    }

    /** Instruments the one method that turns `model.Q` into panel geometry on both panel variants. */
    private fun hookViewPaddingSetter() {
        listOf(ADJUST_VIEW_SINGLE, ADJUST_VIEW_SPLIT).forEach { className ->
            runCatching {
                val owner = loadClassOrNull(className) ?: error("Failed to resolve $className")
                val setter = owner.declaredMethods.firstOrNull { candidate ->
                    candidate.name == VIEW_PADDING_SETTER &&
                        candidate.parameterTypes.size == 2 &&
                        candidate.parameterTypes[0].name == PADDING_MODEL &&
                        candidate.parameterTypes[1] == PRIMITIVE_INT &&
                        candidate.returnType == PRIMITIVE_VOID
                }?.apply { isAccessible = true }
                    ?: error("No ${VIEW_PADDING_SETTER}($PADDING_MODEL, int) on $className")

                setter.hookAfter { param ->
                    // Runs on every commit, including while a handle is being dragged, because the
                    // host re-decides the bar's side only in single-hand mode - and only this module
                    // knows where the keyboard ended up.
                    if (className == ADJUST_VIEW_SINGLE && alignButtonBar(param.thisObject)) {
                        if (buttonBarReportCount.incrementAndGet() <= BUTTON_BAR_REPORT_LIMIT) {
                            Log.i("Success: Single-hand button bar realigned via $className")
                        }
                    }
                    val model = param.args?.getOrNull(0)
                    val width = param.args?.getOrNull(1) as? Int ?: 0
                    val left = intGetter(model, "j")
                    val right = intGetter(model, "l")
                    if (viewProbeCount.incrementAndGet() <= VIEW_PROBE_LIMIT) {
                        Log.i(
                            "Panel geometry $className: width=$width left=$left right=$right" +
                                " sum=${width + left + right}" +
                                " | q[${paddingFields(model)}]" +
                                " | view[${intFields(param.thisObject)}]"
                        )
                    }
                    val view = param.thisObject as? android.view.View ?: return@hookAfter
                    if (viewTreeCount.incrementAndGet() > VIEW_TREE_DUMP_LIMIT) return@hookAfter
                    view.post { Log.i("Panel tree $className:${viewTree(view)}") }
                }
                Log.i("Success: Probe adjust panel geometry via $className.$VIEW_PADDING_SETTER()")
            }.onFailure { error ->
                Log.i("Failed: Probe adjust panel geometry via $className.$VIEW_PADDING_SETTER()")
                Log.i(error)
            }
        }
    }

    /**
     * Records which `a7.b` field each padding setter actually lands in.
     *
     * `o()` / `p()` pick between `f`/`g`, `h`/`i` and `k`/`l` at call time, so a value can reach the
     * model and still be invisible to a reader that lands on a different branch. Logging the written
     * value next to what the matching getter reads back removes that whole class of doubt.
     */
    private fun hookPaddingWriters() {
        runCatching {
            val owner = loadClassOrNull(PADDING_MODEL) ?: error("Failed to resolve $PADDING_MODEL")
            var installed = 0
            listOf("o" to "j", "p" to "l").forEach { (setterName, getterName) ->
                val setter = owner.declaredMethods.firstOrNull { candidate ->
                    candidate.name == setterName &&
                        candidate.parameterTypes.size == 1 &&
                        candidate.parameterTypes[0] == PRIMITIVE_INT &&
                        candidate.returnType == PRIMITIVE_VOID
                }?.apply { isAccessible = true } ?: return@forEach

                setter.hookAfter { param ->
                    if (writerProbeCount.incrementAndGet() <= WRITER_PROBE_LIMIT) {
                        Log.i(
                            "Padding write: Q.$setterName(${param.args?.getOrNull(0)})" +
                                " -> $getterName()=${intGetter(param.thisObject, getterName)}" +
                                " | ${intFields(param.thisObject)}"
                        )
                    }
                }
                installed++
            }
            if (installed == 0) error("No padding setters matched")
            Log.i("Success: Probe padding writers via $PADDING_MODEL.o()/p()")
        }.onFailure { error ->
            Log.i("Failed: Probe padding writers via $PADDING_MODEL.o()/p()")
            Log.i(error)
        }
    }

    /**
     * The point of the whole probe set: `Mgr.b()` records through `m1.B2()`, `Mgr.c()` through
     * `i1.M3()`. Logging both entries shows whether the rewritten arguments actually arrive.
     */
    private fun hookPaddingRecords() {
        listOf(M1 to "B2", SETTINGS to "M3").forEach { (className, methodName) ->
            runCatching {
                val owner = loadClassOrNull(className) ?: error("Failed to resolve $className")
                val record = owner.declaredMethods.firstOrNull { candidate ->
                    candidate.name == methodName &&
                        candidate.parameterTypes.size == ADJUST_ARG_COUNT &&
                        candidate.parameterTypes.all { it == PRIMITIVE_INT } &&
                        candidate.returnType == PRIMITIVE_VOID
                }?.apply { isAccessible = true }
                    ?: error("No $methodName(IIIII) on $className")

                record.hookBefore { param ->
                    if (recordProbeCount.incrementAndGet() <= RECORD_PROBE_LIMIT) {
                        Log.i(
                            "Padding record: ${className.substringAfterLast('.')}.$methodName(" +
                                param.args.joinToString(",") + ") <- ${callerHint()}"
                        )
                    }
                }
                Log.i("Success: Probe padding record via $className.$methodName()")
            }.onFailure { error ->
                Log.i("Failed: Probe padding record via $className.$methodName()")
                Log.i(error)
            }
        }
    }

    /** Calls a no-arg `int` getter by name, tolerating whatever the host obfuscator renamed. */
    private fun intGetter(target: Any?, name: String): Int = runCatching {
        val method = target?.javaClass?.declaredMethods?.firstOrNull {
            it.name == name && it.parameterTypes.isEmpty() && it.returnType == PRIMITIVE_INT
        }?.apply { isAccessible = true } ?: return@runCatching MARGIN_UNSEEN
        method.invoke(target) as? Int ?: MARGIN_UNSEEN
    }.getOrDefault(MARGIN_UNSEEN)

    /** Every `int` field an object carries, so a stale one is visible at a glance. */
    private fun intFields(instance: Any?): String = runCatching {
        if (instance == null) return@runCatching "n/a"
        instance.javaClass.declaredFields
            .filter { it.type == PRIMITIVE_INT }
            .joinToString(" ") { field ->
                field.isAccessible = true
                "${field.name}=${field.get(instance)}"
            }
    }.getOrElse { "n/a" }

    /** Reads one primitive `int` field by name, or null when the class does not declare it. */
    private fun intField(instance: Any?, name: String): Int? = runCatching {
        if (instance == null) return@runCatching null
        instance.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }
            .get(instance) as? Int
    }.getOrNull()

    /**
     * The panel's content container - the view the dark scrim is drawn into.
     *
     * `ImeAdjustViewSingle` keeps it in the `d` field, and both of its layout entry points rewrite
     * that one view's margin and width.
     */
    private fun panelContentView(panel: Any?): android.view.View? = runCatching {
        if (panel == null) return@runCatching null
        panel.javaClass.getDeclaredField("d")
            .apply { isAccessible = true }
            .get(panel) as? android.view.View
    }.getOrNull()

    /**
     * Re-centres the merged panel's *preview* frame, mirroring what the split panel already does.
     *
     * The two panel variants lay their content out through different entry points, and only the split
     * one is symmetric:
     * ```
     * ImeAdjustViewSplit.q()   left = (keyboardWidthTotal - keyboardWidth) / 2 - p1 + p3
     * ImeAdjustViewSingle.m()  left = E + p1                                     <- p3 missing
     * ```
     * A merged drag that moves only the right handle arrives with `p1 = 0`, so the host's own formula
     * leaves the left edge where it was and pulls only the right edge in. The keyboard underneath is
     * driven from `model.Q` (which this module keeps centred) while the scrim is driven from this
     * preview, so the two visibly drift apart - the user's "遮罩没有同步".
     *
     * `m()` does not hand over the transformed `p1` / `p3`, but they can be recovered from what the
     * host wrote, since its two assignments are `width = B - p1 + p3` and `left = E + p1`:
     * ```
     * p1 = left - E
     * p3 = width - B + p1
     * ```
     * Substituting those into the split panel's symmetric form (`left = E + p1 - p3`,
     * `width = B - 2*p1 + 2*p3`) collapses to plain arithmetic on the committed pair, so the host's
     * own pixel-to-panel mapping never has to be reproduced:
     * ```
     * width = 2*previewWidth - B
     * left  = E + B - previewWidth
     * ```
     * Worked example from the on-device capture (`E=327, B=1710, previewWidth=1696`): left becomes
     * `327 + 1710 - 1696 = 341`, width becomes `2*1696 - 1710 = 1682`, and `(2364 - 1682) / 2 = 341`
     * - exactly centred, which the host's own output (`left=327, width=1696`) was not.
     *
     * @return true when the layout params were rewritten.
     */
    private fun centrePreviewFrame(panel: Any?): Boolean {
        if (singleHandActive()) return false
        val content = panelContentView(panel) ?: return false
        val params = content.layoutParams as? android.view.ViewGroup.MarginLayoutParams ?: return false
        val committedWidth = intField(panel, COMMIT_WIDTH_FIELD) ?: return false
        val committedLeft = intField(panel, COMMIT_LEFT_FIELD) ?: return false
        val previewWidth = params.width
        if (committedWidth <= 0 || previewWidth <= 0) return false

        val mirroredWidth = 2 * previewWidth - committedWidth
        val mirroredLeft = committedLeft + committedWidth - previewWidth
        // A non-positive width would collapse the board; a negative inset would push it off-screen.
        if (mirroredWidth <= 0 || mirroredLeft < 0) return false
        if (params.width == mirroredWidth && params.marginStart == mirroredLeft) return false

        params.width = mirroredWidth
        params.setMarginStart(mirroredLeft)
        content.layoutParams = params
        return true
    }

    /**
     * One line per node of a view subtree: class, laid-out bounds, margins, translation, alpha.
     * Depth- and breadth-limited because the panel has more than two dozen children.
     */
    private fun viewTree(root: android.view.View): String {
        val builder = StringBuilder()
        fun walk(view: android.view.View, depth: Int, index: Int) {
            builder.append('\n').append("  ".repeat(depth))
            if (depth > 0) builder.append(index).append(": ")
            builder.append(view.javaClass.simpleName)
                .append(" (").append(view.left).append(',').append(view.top)
                .append(" -> ").append(view.right).append(',').append(view.bottom).append(')')
                .append(" wh=").append(view.width).append('x').append(view.height)
                .append(" vis=").append(view.visibility)
                .append(" alpha=").append(view.alpha)
                .append(" tx=").append(view.translationX.toInt())
                .append(" ty=").append(view.translationY.toInt())
            (view.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { params ->
                builder.append(" m=").append(params.leftMargin).append(',').append(params.topMargin)
                    .append(',').append(params.rightMargin).append(',').append(params.bottomMargin)
            }
            if (depth >= VIEW_TREE_DEPTH) return
            val group = view as? android.view.ViewGroup ?: return
            val shown = minOf(group.childCount, VIEW_TREE_CHILDREN)
            for (child in 0 until shown) walk(group.getChildAt(child), depth + 1, child)
            if (group.childCount > shown) {
                builder.append('\n').append("  ".repeat(depth + 1))
                    .append("... ").append(group.childCount - shown).append(" more")
            }
        }
        walk(root, 0, 0)
        return builder.toString()
    }

    /** The nearest *host* frame above the probe, so a second call path is not mistaken for the first. */
    private fun callerHint(): String = runCatching {
        Throwable().stackTrace
            .firstOrNull { frame ->
                frame.className.contains("wetype") && !frame.className.contains("wetypeplus")
            }
            ?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            ?: "?"
    }.getOrDefault("?")

    /**
     * Instruments `ImeAdjustViewSingle.m(int, int, int, int)` - the *preview* path.
     *
     * The panel lays itself out through two different entry points. The commit path
     * (`b(Q, width)`) takes its margin straight from `model.Q`; the preview path takes four pixel
     * offsets and applies them on top of whatever margin the commit path last stored in `E`. Only the
     * commit path sees this module's rewritten padding pair, so if a drag is rendered purely through
     * the preview path the container keeps a stale margin - which is the suspected reason the dark
     * scrim and the keyboard content no longer coincide. Logging the offsets alongside the panel's
     * own `E` and the container's laid-out bounds decides that per event.
     */
    private fun hookAdjustPanelPreview() {
        runCatching {
            val owner = loadClassOrNull(ADJUST_VIEW_SINGLE)
                ?: error("Failed to resolve $ADJUST_VIEW_SINGLE")
            val preview = owner.declaredMethods.firstOrNull { candidate ->
                candidate.name == "m" &&
                    candidate.parameterTypes.size == 4 &&
                    candidate.parameterTypes.all { it == PRIMITIVE_INT } &&
                    candidate.returnType == PRIMITIVE_VOID
            }?.apply { isAccessible = true }
                ?: error("No m(int,int,int,int) on $ADJUST_VIEW_SINGLE")

            preview.hookAfter { param ->
                val hostOutput = panelContentBounds(param.thisObject)
                val rewritten = centrePreviewFrame(param.thisObject)
                val state = intFields(param.thisObject)
                if (state == lastPreviewState) return@hookAfter
                lastPreviewState = state
                if (previewProbeCount.incrementAndGet() > PREVIEW_PROBE_LIMIT) return@hookAfter
                val offsets = param.args?.joinToString(",") ?: "?"
                Log.i("Panel preview m($offsets): $state")
                Log.i("Panel preview host: $hostOutput")
                Log.i("Panel preview ours (rewritten=$rewritten): ${panelContentBounds(param.thisObject)}")
            }
            Log.i("Success: Sync adjust panel preview via $ADJUST_VIEW_SINGLE.m()")
        }.onFailure { error ->
            Log.i("Failed: Probe adjust panel preview via $ADJUST_VIEW_SINGLE.m()")
            Log.i(error)
        }
    }

    /**
     * The scrim container's live geometry - the `d` field view, its layout params and its parent.
     *
     * The container's `marginStart` is what the user sees as the scrim's left edge, so it has to be
     * read from the laid-out view rather than from the panel's bookkeeping fields.
     */
    private fun panelContentBounds(panel: Any?): String = runCatching {
        val content = panelContentView(panel) ?: return@runCatching "d=null"
        val params = content.layoutParams as? android.view.ViewGroup.MarginLayoutParams
        val parent = content.parent as? android.view.View
        val parentText = if (parent == null) "?"
        else "${parent.javaClass.simpleName} wh=${parent.width}x${parent.height}"
        "d[bounds=${content.left},${content.top}->${content.right},${content.bottom}" +
            " w=${content.width} vis=${content.visibility}" +
            " lp.w=${params?.width} top=${params?.topMargin} start=${params?.marginStart}]" +
            " parent=$parentText"
    }.getOrElse { "n/a" }

    /**
     * Pins the reset / cancel / confirm bar to the side *opposite* the docked keyboard.
     *
     * `ImeAdjustViewSingle` inflates that bar from XML as a centred `RelativeLayout` and never
     * repositions it, so in single-hand mode the bar ends up under the keyboard - the user's
     * "单手键盘的三个按钮位置不对，要贴近键盘相反的方向".
     *
     * Direction is read from the content container instead of from a settings flag, because the
     * host exposes single-hand mode only as a boolean: `model.Q`'s insets are what dock the
     * container to an edge, so where the container sits *is* where the keyboard sits. A container
     * that is roughly centred keeps the bar centred, which is what the host already does.
     *
     * The container holds exactly one `RelativeLayout` child (the bar - the others are four
     * `FrameLayout` handles, a background `View` and the keyboard surface), so no view id is needed.
     *
     * @return true when the bar's layout params were rewritten.
     */
    private fun alignButtonBar(panel: Any?): Boolean {
        val content = panelContentView(panel) as? android.view.ViewGroup ?: return false
        val parent = content.parent as? android.view.View ?: return false
        val total = parent.width
        if (total <= 0) return false

        val contentParams = content.layoutParams as? android.view.ViewGroup.MarginLayoutParams
            ?: return false
        val inset = contentParams.marginStart
        val width = if (content.width > 0) content.width else contentParams.width
        if (width <= 0 || inset < 0) return false

        // Compare centres rather than edges: a docked keyboard can be wider or narrower than half the
        // screen, but its centre is still clearly off the middle. The eighth-of-a-screen tolerance
        // keeps a centred keyboard (and the small wobble of a live drag) from flipping the bar.
        val offset = (inset + width / 2) - total / 2
        if (kotlin.math.abs(offset) < total / 8) return false
        val shouldHugRight = offset < 0

        var bar: android.view.View? = null
        for (index in 0 until content.childCount) {
            val child = content.getChildAt(index)
            if (child is android.widget.RelativeLayout) {
                bar = child
                break
            }
        }
        val barView = bar ?: return false
        val barParams = barView.layoutParams as? android.widget.RelativeLayout.LayoutParams
            ?: return false

        val wanted = if (shouldHugRight) android.widget.RelativeLayout.ALIGN_PARENT_RIGHT
        else android.widget.RelativeLayout.ALIGN_PARENT_LEFT
        val other = if (shouldHugRight) android.widget.RelativeLayout.ALIGN_PARENT_LEFT
        else android.widget.RelativeLayout.ALIGN_PARENT_RIGHT
        if (barParams.getRule(wanted) == android.widget.RelativeLayout.TRUE &&
            barParams.getRule(other) != android.widget.RelativeLayout.TRUE
        ) {
            return false
        }

        barParams.removeRule(android.widget.RelativeLayout.CENTER_HORIZONTAL)
        barParams.removeRule(other)
        barParams.addRule(wanted, android.widget.RelativeLayout.TRUE)
        barParams.addRule(android.widget.RelativeLayout.CENTER_VERTICAL, android.widget.RelativeLayout.TRUE)
        barParams.leftMargin = 0
        barParams.rightMargin = 0
        barView.layoutParams = barParams
        return true
    }

    // --------------------------------------------------------------- single-hand mode

    private fun hookSingleHandModeGate() {
        hookFoldGate()

        val settingsClass = loadClassOrNull(SETTINGS)
        if (settingsClass == null) {
            Log.e("Failed: Resolve WeType settings class for single-hand unlock")
            return
        }

        listOf("k2", "Y2").forEach { methodName ->
            runCatching {
                val gateMethod = settingsClass.declaredMethods.firstOrNull { method ->
                    method.name == methodName &&
                        method.parameterTypes.isEmpty() &&
                        method.returnType == PRIMITIVE_BOOLEAN
                }?.apply { isAccessible = true }
                    ?: throw NoSuchMethodException("$SETTINGS#$methodName()")

                // Open the window right before the host evaluates the gate, and close it after,
                // so only this evaluation sees the bypassed fold gate.
                gateMethod.hookBefore {
                    if (HookSettings.unlockSingleHandMode) foldGateBypass.set(true)
                }
                gateMethod.hookAfter { param ->
                    foldGateBypass.set(false)
                    // Deliberately *not* forcing the result. `k2()` has four conditions and the
                    // last one is the user's own `ime_enable_single_hand_mode`; overriding it would
                    // make the in-keyboard toggle one-way (it could turn the mode on but never
                    // off). Bypassing the fold gate is enough - the user's setting decides the rest.
                    if (gateReportCount.incrementAndGet() <= 6) {
                        Log.i(
                            "Single-hand gate: $SETTINGS.$methodName() = ${param.result}"
                                + " | " + singleHandTrace()
                        )
                    }
                }
                Log.i("Success: Unlock WeType single-hand mode via $SETTINGS.$methodName()")
            }.onFailure { error ->
                Log.i("Failed: Unlock WeType single-hand mode via $SETTINGS.$methodName()")
                Log.i(error)
            }
        }
    }

    /**
     * The three inputs of `i1.k2()` other than the fold gate, so a field report is conclusive:
     * whether the keyboard is floating, which kind it is, whether that kind qualifies, and what
     * the two qualifying kind ids are.
     */
    private fun singleHandTrace(): String = runCatching {
        val kind = callOnSingleton(KEYBOARD_MODEL, "a", "t0")
        val typeOk = if (kind == null) null else callOnSingleton(KEYBOARD_MODEL, "a", "O1", kind)
        "floating=${callOnSingleton(FLOAT_SINGLETON, "a", "V")} kind=$kind typeOk=$typeOk" +
            " sceneE=${keyboardSceneId("e")} sceneJ=${keyboardSceneId("j")}"
    }.getOrElse { "trace failed: $it" }

    private fun keyboardSceneId(field: String): Any? = runCatching {
        val cls = loadClassOrNull(KEYBOARD_SCENE_CLASS) ?: return@runCatching null
        val instance = cls.getDeclaredField(field).apply { isAccessible = true }.get(null)
            ?: return@runCatching null
        cls.getDeclaredMethod("c").apply { isAccessible = true }.invoke(instance)
    }.getOrNull()

    /** Reads the `model.Q` padding fields (`a7.b`) so the active branch is unambiguous. */
    private fun paddingFields(instance: Any?): String = runCatching {
        if (instance == null) return@runCatching "n/a"
        var target: Class<*>? = instance.javaClass
        while (target != null) {
            val fields = target.declaredFields.filter { it.name in PADDING_FIELDS }
            if (fields.isNotEmpty()) {
                return@runCatching fields.joinToString(",") { field ->
                    field.isAccessible = true
                    "${field.name}=${field.get(instance)}"
                }
            }
            target = target.superclass
        }
        "no padding fields"
    }.getOrElse { "n/a" }

    /**
     * `m1.X1()` - the unfolded / large-screen test that disables single-hand mode.
     *
     * The bypass is consumed on first use. If the host short-circuits before reaching `X1()`, or a
     * hook on the caller never runs its after-callback, the flag cannot leak into an unrelated
     * evaluation.
     */
    private fun hookFoldGate() {
        runCatching {
            val m1Class = loadClassOrNull(M1)
                ?: error("Failed to resolve $M1")
            val foldGateMethod = m1Class.declaredMethods.firstOrNull { method ->
                method.name == "X1" &&
                    method.parameterTypes.isEmpty() &&
                    method.returnType == PRIMITIVE_BOOLEAN
            }?.apply { isAccessible = true }
                ?: throw NoSuchMethodException("$M1#X1()")

            foldGateMethod.hookBefore { param ->
                if (foldGateBypass.get() != true) return@hookBefore
                foldGateBypass.set(false)
                param.result = false
            }
            Log.i("Success: Bypass WeType unfolded-screen gate via $M1.X1()")
        }.onFailure { error ->
            Log.i("Failed: Bypass WeType unfolded-screen gate via $M1.X1()")
            Log.i(error)
        }
    }

    // ------------------------------------------------------------------------- shared

    private fun currentMetrics(): android.util.DisplayMetrics? {
        val context = currentApplication() ?: return null
        return runCatching { context.resources.displayMetrics }.getOrNull()
    }

    /**
     * Current screen width in pixels for the active orientation.
     *
     * The keyboard window spans the full display width, so the process resources report exactly
     * the value the host needs - unlike `Resources.getSystem()`, which follows the default display
     * and is never reconfigured. `Resources` is reconfigured on rotation and on fold / unfold, so
     * a cached numeric width would go stale - it is never cached.
     */
    private fun currentScreenWidthPx(): Int = currentMetrics()?.widthPixels ?: 0

    private fun currentApplication(): Application? {
        cachedApplication?.let { return it }
        val application = runCatching {
            Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication")
                .apply { isAccessible = true }
                .invoke(null) as? Application
        }.getOrNull()
        application?.let { cachedApplication = it }
        return application
    }
}
