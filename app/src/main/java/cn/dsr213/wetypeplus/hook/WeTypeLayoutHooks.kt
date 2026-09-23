package cn.dsr213.wetypeplus.hook

import android.app.Application
import cn.dsr213.wetypeplus.bridge.Log
import cn.dsr213.wetypeplus.bridge.hookAfter
import cn.dsr213.wetypeplus.bridge.hookBefore
import cn.dsr213.wetypeplus.bridge.loadClassOrNull
import cn.dsr213.wetypeplus.bridge.sameAs
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * ⚠️ **The `m1`-flavoured names in the KDoc below are historical, not literals.**
 *
 * Every class this module needs out of the host's `utils` package is looked up through [HostNames],
 * because the host renames them: WeType 3.5.4 moved `m1` -> `n1`, `i1` -> `j1` and `Z0` -> `a1`,
 * while keeping the method names. The comments keep the names the reverse engineering was done
 * against, because that is what the analysis in the knowledge base refers to.
 */

/** `m1` up to 3.5.3, `n1` from 3.5.4 - WxImeUIUtil, the single source of every keyboard size constant. */
private val M1 get() = HostNames.widthUtil

/**
 * `m1$b` - literally the `AppScreenWidthCacheItem`. It is the instance held by `m1.K`, i.e. the
 * one `m1.y(forceRefresh)` forwards to.
 *
 * Note it does **not** declare `d(boolean)`; that lives on the shared base. The class is therefore
 * used here only as an identity token, to tell the width item apart from its siblings.
 */
private val M1_SCREEN_WIDTH get() = HostNames.widthItem

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
private val M1_WIDTH_CACHE_BASE get() = HostNames.widthCacheBase

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

/** `Z0` up to 3.5.3, `a1` from 3.5.4 - holds `P1()` (split keyboard) and `V3(Z)`, its setter. */
private val PADDING_GATE get() = HostNames.paddingGate

/** `adjust.b` - `ImeAdjustViewMgr`; the *single* entry point of the "adjust keyboard size" panel. */
private const val ADJUST_MANAGER = "com.tencent.wetype.plugin.hld.adjust.b"

/**
 * `adjust.f` - `ImeAdjustViewSuper`, the base type returned by `Mgr.j(Context)`.
 *
 * ⚠️ **Do not hook its methods to intercept panel behaviour.** `Mgr.A(t)` calls its `a(...)` through
 * the declared type `adjust.f`, but the receiver is always `adjust.c` or `adjust.e`, and each of
 * those declares its own `a(...)`. A libxposed hook binds one `ArtMethod`, so a hook on
 * `adjust.f.a` is never entered - the 1.0.15/1.0.16 builds capped a value that no live panel ever
 * read. `adjust.e` is also where `Mgr.j()`'s split branch lands, for the same reason.
 */
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

/**
 * `Mgr.x(...)` - the private commit path, and the only place one-handed mode is ever switched off by
 * the resize gesture.
 *
 * Read-only here: the floor it is guarded by is applied upstream in [applyMarginSync], on arguments
 * that reach `Mgr.x` unchanged. What this proves is that the guard actually lands - the alternative,
 * trusting the argument rewrite without watching its consumer, is exactly the mistake the earlier
 * `model.Q.o()` clamp made.
 */
private const val ADJUST_COMMIT_GATE = "x"

/** How many `Mgr.x(...)` decisions get a log line. */
private const val ADJUST_GATE_LIMIT = 12

/**
 * `adjust.c.e(I)` / `adjust.c.f(I)` - the two per-edge clamps `adjust.c.m(IIII)` routes the
 * horizontal drag deltas through.
 *
 * `m(p1, p2, p3, p4)` is the gesture's whole geometry, and the four arguments are one delta per
 * edge: `left_rv` -> `p1`, `top_rv` -> `p2`, `right_rv` -> `p3`, `bottom_rv` -> `p4`, with the
 * unused slots zeroed by the synthetic default-argument bridge `adjust.c.n(...)`. It hands
 * `p1` to `e()`, `p3` to `f()`, `p2` to `g()` and `p4` to `d()`, then writes the four results
 * straight onto the scrim's `LayoutParams` - `width` and `height` on the params, `marginStart` and
 * `topMargin` through the margin setter. The keyboard body is laid out *inside* that scrim, which is
 * why a limit here stops the strip and the keyboard in the same frame: there is only one view being
 * measured.
 *
 * Because a single touch event carries exactly one non-zero delta, the two gaps are each a pure
 * function of one clamp result, and the panel's own report agrees: at touch-up it re-baselines
 * `B = params.width`, `E = params.marginStart` and then tells `Mgr.b(...)`
 * `left = E`, `right = w - E - B`. So, with the panel's fields,
 *
 * ```
 * leftGap  = E + e(p1)
 * rightGap = w - E - B - f(p3)
 * ```
 *
 * Both are exact - that is what makes a floor here land on the pixel rather than near it.
 */
private const val ADJUST_CLAMP_LEADING = "e"
private const val ADJUST_CLAMP_TRAILING = "f"

/**
 * The five `adjust.c` fields the floor is computed from, all on-device verified.
 *
 * `B` is re-baselined from the actual `LayoutParams.width` at every touch-up, and `E` from
 * `getMarginStart()`, so the pair means "where the keyboard sat when this gesture started" - which
 * is exactly what the deltas are relative to. `w` is the width budget (the screen width on a live
 * panel: `w - E - B = 237` matched `J = 237` on device, with `E = 31`, `B = 1404`, `w = 1672`).
 * `I` / `J` hold the previous frame's two gaps.
 */
private const val ADJUST_FIELD_WIDTH = "B"
private const val ADJUST_FIELD_INSET = "E"
private const val ADJUST_FIELD_SPAN = "w"
private const val ADJUST_FIELD_LEFT = "I"
private const val ADJUST_FIELD_RIGHT = "J"

/** How many edge-floor clamps get a log line. */
private const val EDGE_FLOOR_LIMIT = 12

/**
 * The split panel's layout applier - `adjust.e.e(ZIIII)V`, the only writer of the two halves'
 * `RelativeLayout.LayoutParams`.
 *
 * Measured on device, the five arguments mean:
 * ```
 * p1 isRight   -> picks the binding: false = the left half, true = the right half
 * p2 width     -> written straight into LayoutParams.width
 * p3 height    -> LayoutParams.height
 * p4 topMargin -> LayoutParams.topMargin
 * p5 margin    -> setMarginStart() when isRight, setMarginEnd() otherwise
 * ```
 * So one call lays out one half: the half is `width` wide and its outer edge sits `margin` from the
 * screen edge. The board's own width is `screenWidth - 2 * margin - centreGap`, which makes `margin`
 * the exact inverse of "how wide the keyboard is" - the one number a width ceiling has to hold.
 */
private const val ADJUST_SPLIT_APPLY = "e"
private const val ADJUST_SPLIT_WIDTH_INDEX = 1
private const val ADJUST_SPLIT_MARGIN_INDEX = 4

/** How many split-panel width clamps get a log line. */
private const val SPLIT_FLOOR_LIMIT = 12

private const val ADJUST_ARG_COUNT = 5
private const val ADJUST_LEFT_INDEX = 1
private const val ADJUST_RIGHT_INDEX = 2

/** How many distinct gate states get a "Single-hand gate:" line. */
private const val GATE_TRACE_LIMIT = 24

/**
 * The host's own preferences that name the single-hand and split-keyboard members.
 *
 * These are **anchors**, not names. R8 renames symbols and never string literals, so a preference
 * key is the one fact about this code that a host update cannot move - which is why every
 * role-sensitive method here is resolved against a key rather than against a spelling. See
 * [HostMethods] for the four roles and for the 3.5.4 update that made this necessary.
 */
private val SINGLE_HAND_SETTING get() = HostMethods.SINGLE_HAND_KEY
private val SPLIT_SETTING get() = HostMethods.SPLIT_KEY

/**
 * The host's own "this keyboard has outgrown one-handed mode" threshold, in **dp**.
 *
 * `Mgr.x(...)` - the method the adjust panel commits through - ends with
 * ```
 * if (Math.max(left, right) < m1.m0(130) && i1.k2()) i1.a5(false)
 * ```
 * i.e. the moment the wider of the two insets drops under `130dp`, the host silently switches
 * one-handed mode **off** and the keyboard snaps back to full width. That is the "拖到一定值单手模式
 * 就自动关闭" behaviour.
 *
 * Rather than invent a pixel figure, the floor is read back through the very same `m1.m0(130)` at
 * runtime. Keeping the two in lockstep is the whole point: the drag now stops exactly one pixel
 * before the host would have given up on the mode, so the branch above can never fire.
 */
private const val SINGLE_HAND_GAP_FLOOR_DP = 130

/** `m1.m0(int)` - the host's dp-to-px helper, and the one `Mgr.x(...)` measures its limit with. */
private const val DP_TO_PX = "m0"

/** Bounded breadcrumb for the single-hand gap floor. */
private const val PAD_FLOOR_LIMIT = 8

/**
 * `adjust.ImeKeyboardResetView` - the move / reset / size pill.
 *
 * This is **not** the adjust panel. `adjust.c` / `adjust.e` are the drag surfaces; this
 * `RelativeLayout` is the three-button affordance that single-hand mode pins to the side opposite
 * the keyboard. Its class name is uncompromised in the shipped 3.5.3 build.
 */
private const val RESET_VIEW = "com.tencent.wetype.plugin.hld.adjust.ImeKeyboardResetView"

/** `ImeKeyboardResetView.e(show, isAdjust)` - the display entry point. */
private const val RESET_VIEW_SHOW = "e"

/** The companion that owns the show / hide entry point the host actually calls. */
private const val RESET_VIEW_COMPANION = "$RESET_VIEW\$i"
private const val RESET_VIEW_TOGGLE = "b"

/** The synthetic back-reference from that companion to the pill instance itself. */
private const val RESET_VIEW_HOST_FIELD = "this\$0"

/** `f(ZZ)` / `g(ZZ)` - the private pair that reveals the pill's left / right button copy. */
private const val RESET_VIEW_LEFT_COPY = "f"
private const val RESET_VIEW_RIGHT_COPY = "g"

/**
 * `ImeKeyboardResetView.z` - the static behind the companion property `i.a()` / `i.b(Z)`.
 *
 * It is the first half of the fold decision and its `<clinit>` starts it `true`, i.e. the strip was
 * written to arrive expanded and is folded away later by whichever caller wants it out of the way:
 * ```
 * p1 = ImeKeyboardResetView.z          // read by e(ZZ) and handed to f / g as the first argument
 * if (p1 == false || A == true) show <side>_adjust_simple_ll   // the folded three-dot icon
 * else                          show <side>_adjust_rl          // move / reset / size
 * ```
 */
private const val RESET_VIEW_SHOW_SIMPLE_FLAG = "z"

/**
 * `ImeKeyboardResetView.A` - the static "a handle was touched just now" latch, and the second half
 * of that same condition.
 *
 * Set to `true` by `onTouch`'s `ACTION_DOWN` for any of the six handles, and cleared to `false` at
 * the end of **every** `f(ZZ)` / `g(ZZ)` call - so it only speaks for the frame that follows a
 * press. A `true` here therefore folds the strip away even while `z` still asks for the full one.
 */
private const val RESET_VIEW_TOUCHED_FLAG = "A"

/** Bounded breadcrumb for the strip's forced expansion. */
private const val STRIP_FORCE_REPORT_LIMIT = 8

/**
 * `m1.q3(View, Integer height, Integer width)` - the host's own "size this view" call.
 *
 * `ImeKeyboardResetView.e(ZZ)` reaches it (through the `r3` default-args bridge) as
 * `q3(this, null, z1(null, 1, null))`, and only on unfolded screens. It assigns straight into
 * `layoutParams.width`, so it is the single place the pill's width comes from.
 */
private const val PILL_WIDTH_SETTER = "q3"
private const val PILL_WIDTH_LIMIT = 6

/** How far below the pill a view may sit and still count as part of it: row, then cell. */
private const val PILL_DESCENT_DEPTH = 2

/**
 * Gap between the pinned affordance and the panel edge, in dp.
 *
 * Only meant to keep the strip off the very edge, not to move it inward: the keyboard's own panel
 * starts about 40px in on this device, so 16dp (44px) puts the strip on the same margin as the
 * keyboard body it belongs to. Adjusting this one number is the whole tuning surface.
 */
private const val RESET_PILL_EDGE_INSET_DP = 16

private const val RESET_VIEW_REPORT_LIMIT = 8

/**
 * Budgets for the pill probes, deliberately **not** shared.
 *
 * The display hook and the laid-out dump used to draw on one counter. The dump spends it within a
 * couple of frames, after which the display hook goes quiet precisely while the pill is on screen -
 * which is how a working probe came to look like a dead entry point. One laid-out frame carries
 * every number that matters, so the budgets stay tiny.
 */
private const val RESET_PILL_CALL_LIMIT = 6
private const val RESET_PILL_TREE_LIMIT = 3
private const val RESET_PILL_COPY_LIMIT = 4

/** How many ancestors the pill breadcrumb climbs, so the containing box width is in the line. */
private const val VIEW_CHAIN_DEPTH = 8

/** Sentinels for the margin baseline and the dragged-side latch. */
private const val MARGIN_UNSEEN = Int.MIN_VALUE
private const val MARGIN_SIDE_NONE = 0
private const val MARGIN_SIDE_LEFT = 1
private const val MARGIN_SIDE_RIGHT = 2

/** How many breadcrumb lines each of the two new hooks may emit. */
private const val MARGIN_REPORT_LIMIT = 16
private const val EXCLUSION_REPORT_LIMIT = 6

/** Bounded breadcrumb for the settings setters, both directions. */
private const val SETTER_REPORT_LIMIT = 12

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

/** `i1` up to 3.5.3, `j1` from 3.5.4 - the host settings singleton that owns the single-hand gate. */
private val SETTINGS get() = HostNames.settings

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
private val M1_WIDTH_PROVIDER_MAIN get() = HostNames.widthProviderMain
private val M1_WIDTH_PROVIDER_MAX get() = HostNames.widthProviderMax

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
     *
     * The window is opened by [hookSingleHandModeGate], which installs itself on *every* candidate
     * spelling of a gate rather than on the one this module was last built against, so the depth
     * below can legitimately nest.
     */
    private val foldGateBypass: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

    /**
     * How deep this thread is inside a gate candidate, so the bypass window survives nesting.
     *
     * Nesting is real: a gate's first two conditions call into the host's own model code, and the
     * inner call can reach a second candidate. Clearing the window from the inner call's
     * after-hook would close it while the outer gate is still being evaluated - which is exactly
     * the case the bypass exists for - so the flag is only cleared when the depth returns to zero.
     */
    private val gateWindowDepth: ThreadLocal<Int> = ThreadLocal.withInitial { 0 }

    /**
     * Depth, read the defensive way.
     *
     * `ThreadLocal<T>.get()` comes back as `T?` under this build's Kotlin/JDK interop even though
     * `withInitial` guarantees a value, and an unset depth means exactly what a zero depth means -
     * no gate is running on this thread - so the fallback states the invariant instead of hiding it.
     */
    private fun gateDepth(): Int = gateWindowDepth.get() ?: 0

    /**
     * The **bare method name** (`l2`) of the gate candidate currently running on this thread.
     *
     * This is the **resolution anchor** for [HostMethods.Role.SINGLE_HAND_GATE]. A gate is
     * identified by reading its own preference while its window is open, and this is what says
     * *who* was running when that read happened - no stack walk, no name assumption.
     *
     * ⚠️ **Bare, and it has to stay bare.** This value is handed straight to
     * [HostMethods.resolve], and everything downstream of a role - `singleHandActive()` above all -
     * uses it as an argument to `callOnSingleton`, which matches `Method.name` exactly. 1.0.27
     * stored a qualified `"$SETTINGS#$methodName"` here instead, so the role resolved to `j1#l2`,
     * `singleHandActive()` asked for a method literally named `j1#l2`, got `null` from
     * `callOnSingleton` every time, and the whole one-handed path went dead while all 24 hooks
     * still reported `Success`. A qualified name is display material, not an argument:
     * [SETTINGS] is passed to `resolve` separately, for the log line.
     *
     * Nesting can overwrite it, which is harmless: the method that reads the key is the innermost
     * candidate, so the value here is the one that matters.
     */
    private val gateWindowOwner = ThreadLocal<String?>()

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

    /**
     * Cleared as soon as the single-hand gate has been anchored, so the reader probe stops doing
     * anything on the input method's hot preference path.
     */
    private val anchorProbeDisarmed = AtomicBoolean()

    /** Last gate state reported, so the trace budget survives start-up. */
    @Volatile
    private var lastGateTrace: String? = null

    /** Bounded breadcrumb for the single-hand action pill's display calls. */
    private val resetViewReportCount = AtomicInteger()

    /** Separate budget for the laid-out dump - see [RESET_PILL_TREE_LIMIT]. */
    private val resetViewTreeCount = AtomicInteger()

    /** Separate budget for the `f` / `g` copy-selection breadcrumb. */
    private val resetViewCopyCount = AtomicInteger()

    /** Bounded breadcrumb for the strip's forced expansion. */
    private val stripForceCount = AtomicInteger()

    /** One line per distinct strip state - the fold is driven by repeated layout passes. */
    @Volatile
    private var lastStripLine: String? = null

    /**
     * The pill's two fold flags, resolved once from inside a live `f` / `g` call.
     *
     * `getDeclaredField` does not run the host's `<clinit>`, and the `resolved` latch keeps a miss
     * from being retried - and re-thrown - on every layout pass.
     */
    @Volatile
    private var resetViewShowSimpleField: java.lang.reflect.Field? = null

    @Volatile
    private var resetViewTouchedField: java.lang.reflect.Field? = null

    @Volatile
    private var resetViewFlagsResolved = false

    /** Bounded breadcrumb for the host's own `m1.q3` sizing of the pill. */
    private val pillWidthCount = AtomicInteger()

    /** Bounded breadcrumb for the single-hand gap floor. */
    private val padFloorCount = AtomicInteger()

    /**
     * Latched by the `q3` clamp and read by the layout backstop - carries the whole gate with it
     * (feature on **and** unfolded screen), so the backstop never has to re-derive it.
     *
     * Deliberately a plain flag rather than a settings read at every layout: the backstop runs on
     * the UI thread for every pill layout, and `HookSettings` only caches for a second, so reading
     * it there would put a `ContentResolver` query inside a layout pass roughly once a second.
     */
    @Volatile
    private var pillPinActive = false

    /** Guards the layout registration - the pill's toggle fires on every show and hide. */
    private val resetViewBound = AtomicBoolean(false)

    /** Bounded breadcrumb for the adjust-panel drag entry points. */
    private val adjustProbeCount = AtomicInteger()

    /** Bounded breadcrumb for the one-hand cut-off decision inside `Mgr.x(...)`. */
    private val adjustGateCount = AtomicInteger()

    /** Bounded breadcrumb for the size-handle travel cap inside `adjust.c.e()` / `adjust.c.f()`. */
    private val edgeFloorCount = AtomicInteger()

    /** Bounded breadcrumb for the split panel's width ceiling inside `adjust.e.e()`.
     *
     * The split panel writes its two halves' `LayoutParams` itself, so the `adjust.c` travel cap
     * never sees it - see [hookAdjustSplitEdgeFloor] for the measured geometry that says so.
     */
    private val splitFloorCount = AtomicInteger()

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

    /**
     * Breadcrumb for every settings-setter transition, in both directions.
     *
     * The *off* direction is the one that matters: an over-wide keyboard is what turns single-hand
     * mode off, and until these lines existed no probe in the module could see it happen - the
     * setter hooks only ever reported the `true` direction.
     */
    private val setterReportCount = AtomicInteger()

    @Volatile
    private var lastSetterTrace: String? = null

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
        hookKeyboardResetView()
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
        anchorProbeDisarmed.set(false)
        gateWindowDepth.remove()
        gateWindowOwner.remove()
        // Anchor resolutions are per generation: the method objects behind them were resolved
        // against the class loader this instance was created with, and a hot reload brings a new
        // one. Keeping the old answers would name methods that no longer exist in the new loader.
        HostMethods.reset()
        resetViewReportCount.set(0)
        resetViewTreeCount.set(0)
        resetViewCopyCount.set(0)
        stripForceCount.set(0)
        lastStripLine = null
        resetViewShowSimpleField = null
        resetViewTouchedField = null
        resetViewFlagsResolved = false
        pillWidthCount.set(0)
        padFloorCount.set(0)
        pillPinActive = false
        resetViewBound.set(false)
        adjustProbeCount.set(0)
        adjustGateCount.set(0)
        edgeFloorCount.set(0)
        splitFloorCount.set(0)
        exclusionReportCount.set(0)
        setterReportCount.set(0)
        lastSetterTrace = null
        viewProbeCount.set(0)
        viewTreeCount.set(0)
        writerProbeCount.set(0)
        recordProbeCount.set(0)
        previewProbeCount.set(0)
        buttonBarReportCount.set(0)
        lastPreviewState = null
        lastAdjustTuple = null
        lastGateTrace = null
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
            .append(" split=").append(splitGateReading())
            .append(" g1=").append(callM1("g1"))
            .toString()
    }.getOrElse { "layoutProbe failed: $it" }

    /**
     * The split-keyboard preference, read through whichever spelling has been resolved.
     *
     * Labelled `split=` rather than `P1=` on purpose: this same breadcrumb already prints
     * `n1.Q1()` as `Q1=`, and two different classes both contributing a `Q1`/`P1` to one line is how
     * a reader ends up comparing the wrong pair. The candidate fallback covers the first pass, before
     * the split anchor has been observed writing its key - reading an unproven spelling is read-only,
     * so it cannot do damage even when it is the wrong preference.
     */
    private fun splitGateReading(): Any? {
        val name = HostMethods.name(HostMethods.Role.SPLIT_GATE) ?: HostMethods.SPLIT_GATES.first()
        return callOnSingleton(PADDING_GATE, "a", name)
    }

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
     * Watches the decision that used to end one-handed mode mid-drag.
     *
     * `Mgr.x(d, left, right, e, gap)` is reached from the commit path only, and it ends with
     * ```
     * if (Math.max(left, right) < m1.m0(130) && i1.k2()) i1.a5(false)
     * ```
     * Logging the pair it is handed, next to the floor and `k2()` it is judged against, is what makes
     * "the guard held" an observation rather than an assumption - and when the guard *fails* (a path
     * that never went through [applyMarginSync], or a `m1.m0` that cannot be resolved) the same line
     * says so before the mode disappears.
     */
    private fun hookAdjustCommitGate() {
        runCatching {
            val manager = loadClassOrNull(ADJUST_MANAGER)
                ?: error("Failed to resolve $ADJUST_MANAGER")
            val gate = manager.declaredMethods.firstOrNull { candidate ->
                candidate.name == ADJUST_COMMIT_GATE &&
                    candidate.parameterTypes.size == ADJUST_ARG_COUNT &&
                    candidate.parameterTypes.all { it == PRIMITIVE_INT } &&
                    candidate.returnType == PRIMITIVE_VOID
            }?.apply { isAccessible = true }
                ?: throw NoSuchMethodException("$ADJUST_MANAGER#$ADJUST_COMMIT_GATE(IIIII)")

            gate.hookBefore { param ->
                val left = param.args?.getOrNull(ADJUST_LEFT_INDEX) as? Int ?: return@hookBefore
                val right = param.args?.getOrNull(ADJUST_RIGHT_INDEX) as? Int ?: return@hookBefore
                if (adjustGateCount.incrementAndGet() > ADJUST_GATE_LIMIT) return@hookBefore
                val floor = singleHandGapFloor()
                val hand = singleHandActive()
                val wider = maxOf(left, right)
                Log.i(
                    "Adjust gate $ADJUST_COMMIT_GATE: left,right=$left,$right wider=$wider" +
                        " floor=$floor hand=$hand" +
                        " wouldDisableMode=${floor > 0 && wider < floor && hand}"
                )
            }
            Log.i("Success: Watch one-hand cut-off via $ADJUST_MANAGER.$ADJUST_COMMIT_GATE()")
        }.onFailure { error ->
            Log.i("Failed: Watch one-hand cut-off via $ADJUST_MANAGER.$ADJUST_COMMIT_GATE()")
            Log.i(error)
        }
    }

    /**
     * Turns the host's own one-handed cut-off into a hard stop for the size handle.
     *
     * The gesture never consults `model.Q`: `adjust.c.m(IIII)` hands each edge delta to its own
     * clamp and writes the result straight onto the scrim's `LayoutParams`, which the keyboard body
     * is laid out inside. So the clamps - not the padding model, and not `Mgr.b` / `Mgr.c` - are
     * where a drag can actually be stopped, and capping them stops the strip and the keyboard in the
     * same frame because there is only one view being measured.
     *
     * The floor is the *gap*, not the width: the rule is "the side the keyboard is docked away from
     * never gets narrower than [singleHandGapFloor]". Which side that is comes from the panel's own
     * `I` / `J`, so the same code covers the left- and the right-handed dock without knowing which
     * one is in play. If the far side already carries the floor the near side is left alone - that
     * is what keeps the keyboard flush against its own edge instead of floating in the middle.
     *
     * Both clamps return a *delta*, so the cap is applied to the return value:
     *
     * - `f(p3)` moves the trailing edge. `rightGap = w - E - B - f(p3)`, so the result is capped at
     *   `w - E - B - floor`.
     * - `e(p1)` moves the leading edge. `leftGap = E + e(p1)`, so the result is raised to
     *   `floor - E`.
     *
     * Because the trailing gap does not depend on `e()`'s result at all, and a single touch event
     * only ever carries one non-zero delta, the two clamps cannot fight each other.
     *
     * Gated on [singleHandActive]: taking a full-width span away from the merged panel would break
     * the ordinary resize gesture, and the same `adjust.c` is also the plain (non-single-hand) panel.
     */
    private fun hookAdjustEdgeFloor() {
        runCatching {
            val panel = loadClassOrNull(ADJUST_VIEW_SINGLE)
                ?: error("Failed to resolve $ADJUST_VIEW_SINGLE")
            val widthField = intField(panel, ADJUST_FIELD_WIDTH)
            val insetField = intField(panel, ADJUST_FIELD_INSET)
            val spanField = intField(panel, ADJUST_FIELD_SPAN)
            val leftField = intField(panel, ADJUST_FIELD_LEFT)
            val rightField = intField(panel, ADJUST_FIELD_RIGHT)

            var installed = 0
            listOf(ADJUST_CLAMP_LEADING, ADJUST_CLAMP_TRAILING).forEach { name ->
                val trailing = name == ADJUST_CLAMP_TRAILING
                val clamp = panel.declaredMethods.firstOrNull { candidate ->
                    candidate.name == name &&
                        candidate.parameterTypes.size == 1 &&
                        candidate.parameterTypes[0] == PRIMITIVE_INT &&
                        candidate.returnType == PRIMITIVE_INT
                }?.apply { isAccessible = true } ?: return@forEach

                clamp.hookAfter { param ->
                    val raw = param.result as? Int ?: return@hookAfter
                    val floor = singleHandGapFloor()
                    if (floor <= 0) return@hookAfter
                    val owner = param.thisObject
                    val span = spanField.getInt(owner)
                    val inset = insetField.getInt(owner)
                    // The gap on the *other* side, as of the previous frame. When it already clears
                    // the floor the wide side is settled and this edge is free to travel.
                    val other = if (trailing) leftField.getInt(owner) else rightField.getInt(owner)
                    if (other >= floor) return@hookAfter
                    val limit = if (trailing) {
                        span - inset - widthField.getInt(owner) - floor
                    } else {
                        floor - inset
                    }
                    if (limit <= 0) return@hookAfter
                    val capped = if (trailing) minOf(raw, limit) else maxOf(raw, limit)
                    if (capped == raw) return@hookAfter
                    if (!singleHandActive()) return@hookAfter
                    param.result = capped
                    if (edgeFloorCount.incrementAndGet() <= EDGE_FLOOR_LIMIT) {
                        Log.i(
                            "Adjust edge floor $name: $raw -> $capped" +
                                " span=$span base=${widthField.getInt(owner)} inset=$inset" +
                                " floor=$floor other=$other hand=true" +
                                " | q[${paddingFields(currentPaddingModel())}]"
                        )
                    }
                }
                installed++
            }
            if (installed < 2) {
                error("Only $installed of 2 edge clamps matched in $ADJUST_VIEW_SINGLE")
            }
            Log.i(
                "Success: Cap size-handle travel via $ADJUST_VIEW_SINGLE." +
                    "$ADJUST_CLAMP_LEADING()/$ADJUST_CLAMP_TRAILING()"
            )
        }.onFailure { error ->
            Log.i(
                "Failed: Cap size-handle travel via $ADJUST_VIEW_SINGLE." +
                    "$ADJUST_CLAMP_LEADING()/$ADJUST_CLAMP_TRAILING()"
            )
            Log.i(error)
        }
    }

    /**
     * Caps how wide the keyboard may get while one-handed mode is on, on the *split* panel.
     *
     * [hookAdjustEdgeFloor] caps `adjust.c`, which is the panel the host builds when the keyboard is
     * a single block. This device renders the keyboard as two halves in landscape, and the host then
     * builds `adjust.e` instead - a different class with a different set of clamps - so the travel
     * cap simply never ran in that orientation. On-device proof, from a drag of the right half's
     * outer grip out to the screen edge (`_wetype_grip2.sh L1 2126 2340 1315`):
     * ```
     * Adjust b: left,right=0,0 -> 143,143 | applied=true ... split=true hand=true
     * Adjust gate x: left,right=143,143 wider=143 floor=143 hand=true wouldDisableMode=false
     * ```
     * The guard held - the mode was *not* switched off - and yet the screenshot taken right after
     * 确定 shows the board flush against both edges with the action strip collapsed to a tab. So the
     * two halves are laid out by the panel itself and never consult `model.Q`, which is exactly why
     * the committed padding pair makes no difference to what the user sees.
     *
     * `adjust.e.e(ZIIII)V` is that layout, and it is the only writer of the halves'
     * `LayoutParams` (see [ADJUST_SPLIT_APPLY] for the argument map). Its `margin` argument is the
     * inverse of the keyboard's width, because the two halves are symmetric and their outer edges
     * are pinned to `margin` from the screen edges:
     * ```
     * boardWidth = screenWidth - 2 * margin - centreGap
     * ```
     * So a floor on `margin` *is* a ceiling on the width. Raising the margin alone would push each
     * half's outer edge inward and leave its inner edge where it was - the half would spill past the
     * margin it was just given. Shrinking `width` by the same delta instead keeps the outer edge
     * exactly where the finger put it and retracts the *inner* edge, so the board stops growing while
     * the drag continues: the handle travels, the keyboard does not.
     *
     * Gated on [singleHandActive], because the same class lays out the ordinary two-handed board,
     * where a 130dp outer margin would be a visible defect rather than a limit.
     */
    private fun hookAdjustSplitEdgeFloor() {
        runCatching {
            val panel = loadClassOrNull(ADJUST_VIEW_SPLIT)
                ?: error("Failed to resolve $ADJUST_VIEW_SPLIT")
            val apply = panel.declaredMethods.firstOrNull { candidate ->
                candidate.name == ADJUST_SPLIT_APPLY &&
                    candidate.parameterTypes.size == ADJUST_ARG_COUNT &&
                    candidate.parameterTypes[0] == PRIMITIVE_BOOLEAN &&
                    candidate.parameterTypes.drop(1).all { it == PRIMITIVE_INT } &&
                    candidate.returnType == PRIMITIVE_VOID
            }?.apply { isAccessible = true }
                ?: throw NoSuchMethodException("$ADJUST_VIEW_SPLIT#$ADJUST_SPLIT_APPLY(ZIIII)")

            apply.hookBefore { param ->
                val args = param.args ?: return@hookBefore
                val margin = args.getOrNull(ADJUST_SPLIT_MARGIN_INDEX) as? Int ?: return@hookBefore
                val width = args.getOrNull(ADJUST_SPLIT_WIDTH_INDEX) as? Int ?: return@hookBefore
                val floor = singleHandGapFloor()
                if (floor <= 0 || margin >= floor) return@hookBefore
                if (!singleHandActive()) return@hookBefore
                val shrink = floor - margin
                // Refuse a clamp that would leave a negative width, which would be a broken layout
                // rather than a limit. On a screen this narrow for the margin to reach the floor the
                // halves are already small, so this only guards a pathological frame.
                if (width - shrink <= 0) return@hookBefore
                args[ADJUST_SPLIT_MARGIN_INDEX] = floor
                args[ADJUST_SPLIT_WIDTH_INDEX] = width - shrink
                if (splitFloorCount.incrementAndGet() <= SPLIT_FLOOR_LIMIT) {
                    Log.i(
                        "Split width floor $ADJUST_SPLIT_APPLY: margin=$margin->$floor" +
                            " width=$width->${width - shrink} isRight=${args[0]}" +
                            " | q[${paddingFields(currentPaddingModel())}]"
                    )
                }
            }
            Log.i(
                "Success: Cap split-board width via $ADJUST_VIEW_SPLIT." +
                    "$ADJUST_SPLIT_APPLY(ZIIII)"
            )
        }.onFailure { error ->
            Log.i(
                "Failed: Cap split-board width via $ADJUST_VIEW_SPLIT." +
                    "$ADJUST_SPLIT_APPLY(ZIIII)"
            )
            Log.i(error)
        }
    }

    /**
     * Reads one of the panel's `int` fields.
     *
     * The panels are Kotlin classes whose fields survive obfuscation as single letters, so the names
     * in [ADJUST_FIELD_WIDTH] and friends are version-locked rather than stable API. Resolved once
     * at install time and reused, because these are read on every touch-move of a drag.
     */
    private fun intField(owner: Class<*>, name: String): java.lang.reflect.Field =
        owner.getDeclaredField(name).apply { isAccessible = true }

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
            // One-handed mode is the one case that must *not* be centred, but it is also the one
            // case that needs a limit: the host switches the mode off the instant the wider inset
            // falls under its own `130dp` threshold, which is what made "drag the size handle past a
            // certain point" read as *the feature turning itself off*. Holding that same threshold
            // as a floor stops the drag one pixel early, so the branch that would disable the mode
            // can never be reached - and because the preview (`Mgr.b`) and the commit (`Mgr.c`) both
            // arrive here, the keyboard body and the scrim stop together rather than in two steps.
            //
            // Only the *wider* side is raised. That is the side acting as the blank strip: the
            // keyboard is docked to the opposite edge, so widening it is exactly what shrinks this
            // value. Raising the narrower side instead would push the keyboard off its edge.
            val floor = singleHandGapFloor()
            var outLeft = left
            var outRight = right
            if (floor > 0 && maxOf(left, right) < floor) {
                if (left > right) outLeft = floor
                else if (right > left) outRight = floor
                else {
                    // A dead-centre pair under the floor has no "wider" side to pick, and the host
                    // reaches that state by dragging the keyboard out to nearly full width. Move both
                    // so the pair stays symmetric, which is what that gesture expects to see.
                    outLeft = floor
                    outRight = floor
                }
                if (padFloorCount.incrementAndGet() <= PAD_FLOOR_LIMIT) {
                    Log.i(
                        "Single-hand gap floor $label: left,right=$left,$right -> $outLeft,$outRight" +
                            " floor=$floor | q[${paddingFields(currentPaddingModel())}]"
                    )
                }
            }
            args[ADJUST_LEFT_INDEX] = outLeft
            args[ADJUST_RIGHT_INDEX] = outRight
            rememberMargins(left, right)
            reportMargin(label, left, right, outLeft, outRight,
                outLeft != left || outRight != right, MARGIN_SIDE_NONE, false, false,
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

    /**
     * The host's single-hand gate - `i1.k2()` up to 3.5.3, `j1.l2()` from 3.5.4 - but only once it
     * has proved which method it is.
     *
     * Answering from a name is worse than answering `false` here. 3.5.4 left `k2()` in place on the
     * SMS-code autofill preference, and this getter decides whether the one-handed padding maths
     * runs at all, so the stale spelling made the module lay out an ordinary two-handed keyboard as
     * if it were one-handed. Unresolved reads as "not in one-handed mode" instead: the keyboard
     * stays correct while the anchor is still being established, which takes one layout pass.
     *
     * ⚠️ **"Wrong method" and "wrong shape of name" fail identically here, and 1.0.27 hit the
     * second one.** `HostMethods` resolved this role to `j1.l2()` correctly, but stored it
     * qualified as `j1#l2`; `callOnSingleton` matches `Method.name`, so the lookup returned `null`,
     * `null == true` was `false`, and all six callers of this function took the "one-handed mode is
     * off" branch - on a keyboard where the user had it on. Spelled out because the module's own
     * diagnostics cannot tell the two apart: every failure of this function is the same boolean,
     * and only the gate's own trace (`resolved=` / `current=`) distinguishes them.
     */
    private fun singleHandActive(): Boolean = runCatching {
        val gate = HostMethods.name(HostMethods.Role.SINGLE_HAND_GATE) ?: return@runCatching false
        callOnSingleton(SETTINGS, "a", gate) == true
    }.getOrDefault(false)

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
     *
     * Both setters are found by **writing the preference they own**, never by name. 3.5.4 left
     * `a5(Z)` and `V3(Z)` in place on methods that now write `ime_show_voice_speed`, so a call
     * placed by name would have turned the user's voice-speed preference off and left the mode on -
     * a silent misconfiguration in place of the intended one, with nothing in the UI to connect
     * the two.
     */
    private fun hookHandSplitExclusion() {
        // Touch the reader first so its own resolution line lands with the other install lines
        // rather than in the middle of the first user toggle.
        settingReader

        hookAnchoredSetter(
            owner = SETTINGS,
            candidates = HostMethods.SINGLE_HAND_SETTERS,
            role = HostMethods.Role.SINGLE_HAND_SETTER,
            label = "single-hand",
            readings = { listOf(SINGLE_HAND_SETTING to readHostPreference(SINGLE_HAND_SETTING)) }
        ) {
            clearOtherMode(PADDING_GATE, HostMethods.Role.SPLIT_SETTER, "split-keyboard")
        }

        hookAnchoredSetter(
            owner = PADDING_GATE,
            candidates = HostMethods.SPLIT_SETTERS,
            role = HostMethods.Role.SPLIT_SETTER,
            label = "split-keyboard",
            // `paddingGate` has the same per-key shape as `settings`, but its own generic reader is
            // not a name this module knows, so the split key is watched through the split *gate*
            // candidates instead. Reading an unproven candidate is harmless - it is read-only - and
            // the pair that moves together is the pair that gets adopted.
            readings = { HostMethods.SPLIT_GATES.map { it to callOnSingleton(PADDING_GATE, "a", it) } },
            onEvidence = { moved -> HostMethods.resolve(HostMethods.Role.SPLIT_GATE, moved, PADDING_GATE, "followed ${HostMethods.SPLIT_KEY}") }
        ) {
            clearOtherMode(SETTINGS, HostMethods.Role.SINGLE_HAND_SETTER, "single-hand")
        }
    }

    /**
     * `settings`' generic `(String, boolean) -> boolean` preference reader - `i1.B` on 3.5.3,
     * `j1.B` on 3.5.4, the same name on both.
     *
     * Every per-preference getter on `settings` is a one-line wrapper around this method, which is
     * what makes a preference *key* observable from a hook at all, and therefore what every
     * anchor in [HostMethods] rests on. Resolved once, by name, from the candidate list.
     *
     * [CORRECTION 2026-09-21, re-confirmed 2026-09-23] An earlier revision called `"C"`. There is no
     * `C(String, boolean)`: `C` exists only as the Kotlin default-argument bridge
     * `C(settings, String, boolean, int, Object)`, which forwards here. Invoking the bridge with two
     * arguments threw, `userOn` came back `null` in every report, and the one condition that
     * decides whether the mode is on was the one condition never visible. The bridge is not a
     * fallback - it is the thing to avoid.
     */
    private val settingReader: String? by lazy {
        val owner = loadClassOrNull(SETTINGS)
        val name = owner?.let { cls ->
            HostMethods.SETTING_READERS.firstOrNull { candidate ->
                cls.declaredMethods.any { method ->
                    method.name == candidate &&
                        method.parameterTypes.size == 2 &&
                        method.returnType == PRIMITIVE_BOOLEAN
                }
            }
        }
        if (name == null) {
            Log.i("Failed: Resolve WeType settings reader on $SETTINGS ${HostMethods.SETTING_READERS}")
        } else {
            Log.i("Success: Resolve WeType settings reader as $SETTINGS.$name(String, boolean)")
        }
        name
    }

    /** Reads one host boolean preference through [settingReader], or `null` while it is unresolved. */
    private fun readHostPreference(key: String): Any? =
        settingReader?.let { callOnSingleton(SETTINGS, "a", it, key, false) }

    /**
     * Hooks every candidate spelling of a boolean preference's `(Z)V` setter and lets the
     * preference decide which spelling is the real one.
     *
     * A `(Z)V` body gives no clue about the key it writes, so identification is by observation: the
     * key is read before and after each candidate runs, and a candidate is adopted only when the
     * value actually followed the argument. [onEnabled] therefore never runs for a candidate that
     * has not proved itself, and a stale spelling cannot make this module write anything.
     */
    private fun hookAnchoredSetter(
        owner: String,
        candidates: List<String>,
        role: HostMethods.Role,
        label: String,
        readings: () -> List<Pair<String, Any?>>,
        onEvidence: (String) -> Unit = {},
        onEnabled: () -> Unit
    ) {
        val ownerClass = loadClassOrNull(owner)
        if (ownerClass == null) {
            Log.i("Failed: Enforce $label exclusivity - cannot resolve $owner")
            return
        }
        candidates.forEach { candidateName ->
            runCatching {
                val setter = ownerClass.declaredMethods.firstOrNull { candidate ->
                    candidate.name == candidateName &&
                        candidate.parameterTypes.sameAs(PRIMITIVE_BOOLEAN) &&
                        candidate.returnType == PRIMITIVE_VOID
                }?.apply { isAccessible = true }
                    ?: throw NoSuchMethodException("$owner#$candidateName(Z)")

                val before = ThreadLocal<List<Pair<String, Any?>>>()
                setter.hookBefore { before.set(runCatching(readings).getOrDefault(emptyList())) }
                setter.hookAfter { param ->
                    val was = before.get().orEmpty()
                    before.remove()
                    val now = runCatching(readings).getOrDefault(emptyList())
                    val moved = now.firstOrNull { (key, value) ->
                        was.any { it.first == key && it.second != value }
                    }
                    if (moved != null) {
                        // The preference followed the argument, so this spelling really does own the
                        // key - and for the split pair, the getter that moved is the gate.
                        HostMethods.resolve(role, candidateName, owner, "'${moved.first}' moved")
                        onEvidence(moved.first)
                    }

                    val enabled = param.args?.firstOrNull() == true
                    val trace = "$label=$enabled caller=${callerHint()}"
                    if (trace != lastSetterTrace) {
                        lastSetterTrace = trace
                        if (setterReportCount.incrementAndGet() <= SETTER_REPORT_LIMIT) {
                            Log.i("Setter: $trace")
                        }
                    }

                    if (!enabled) return@hookAfter
                    if (!HostMethods.isResolved(role, candidateName)) return@hookAfter
                    onEnabled()
                    if (exclusionReportCount.incrementAndGet() <= EXCLUSION_REPORT_LIMIT) {
                        Log.i("Exclusion: $label enabled -> the other mode was cleared")
                    }
                }
                Log.i("Success: Watch $label setter candidate $owner.$candidateName()")
            }.onFailure { error ->
                Log.i("Failed: Watch $label setter candidate $owner.$candidateName()")
                Log.i(error)
            }
        }
    }

    /**
     * Clears the *other* mode after the user switched one on, but only once the other mode's setter
     * has proved itself.
     *
     * Skipping is the right failure. The two preferences are independent, so leaving both on gives a
     * keyboard that is neither - whereas calling a stale spelling would set whatever preference that
     * name now owns, and nothing in the UI would connect the two. The line below is what keeps the
     * skipped case visible in a report instead of looking like a hook that never fired.
     */
    private fun clearOtherMode(owner: String, role: HostMethods.Role, label: String) {
        val setter = HostMethods.name(role)
        if (setter == null) {
            if (exclusionReportCount.incrementAndGet() <= EXCLUSION_REPORT_LIMIT) {
                Log.i("Exclusion: $label setter still unidentified - left untouched")
            }
            return
        }
        callOnSingleton(owner, "a", setter, false)
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
                    // No `foldGateBypass` reset here. These getters are read from inside `k2()`,
                    // so clearing the flag from a bystander is exactly what made the bypass
                    // unreliable; the gate hook owns the whole window.
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
        hookAdjustCommitGate()
        hookAdjustEdgeFloor()
        hookAdjustSplitEdgeFloor()
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
     * Records which `a7.b` field each padding setter actually lands in, and enforces the floor that
     * keeps the single-hand controls their own patch of panel.
     *
     * `o()` / `p()` pick between `f`/`g`, `h`/`i` and `k`/`l` at call time, so a value can reach the
     * model and still be invisible to a reader that lands on a different branch. Logging the written
     * value next to what the matching getter reads back removes that whole class of doubt.
     *
     * `o()` is the leading side (`k` split / **`h` single-hand** / `f` merged) and `p()` the trailing
     * one (`l` / **`i`** / `g`).
     *
     * **[CORRECTION 2026-09-21]** An earlier revision also clamped the *argument* here to a hard
     * `205px`. On device that never produced a clean stop: these setters run several times per frame,
     * the host re-issued the value it wanted each time (four consecutive `Q.o(31) -> 205` pairs), and
     * the mode still ended up being switched off - because the host decides that from the arguments
     * reaching `Mgr.x(...)`, not from what is later stored in the model. The floor now lives at that
     * single entry point instead; see [applyMarginSync]. This hook is read-only again.
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
        // Matched by name first, then by "the only five-int writer this class has".
        //
        // 3.5.4 renamed this one: `i1.M3(IIIII)` is now `j1.P3(IIIII)` - the sole `(IIIII)V` method
        // on that class - and `M3` itself was repurposed as a logging helper that takes a tag
        // string. Binding by name alone would drop the probe without saying so, which is the one
        // outcome a diagnostic must never produce, so the bound name is logged either way.
        listOf(M1 to listOf("B2"), SETTINGS to listOf("M3", "P3"))
            .forEach { (className, methodNames) ->
            runCatching {
                val owner = loadClassOrNull(className) ?: error("Failed to resolve $className")
                val writers = owner.declaredMethods.filter { candidate ->
                    candidate.parameterTypes.size == ADJUST_ARG_COUNT &&
                        candidate.parameterTypes.all { it == PRIMITIVE_INT } &&
                        candidate.returnType == PRIMITIVE_VOID
                }
                val record = (writers.firstOrNull { it.name in methodNames }
                    ?: writers.singleOrNull())
                    ?.apply { isAccessible = true }
                    ?: error("No ${methodNames.first()}(IIIII) on $className")
                val bound = record.name

                record.hookBefore { param ->
                    if (recordProbeCount.incrementAndGet() <= RECORD_PROBE_LIMIT) {
                        Log.i(
                            "Padding record: ${className.substringAfterLast('.')}.$bound(" +
                                param.args.joinToString(",") + ") <- ${callerHint()}"
                        )
                    }
                }
                Log.i("Success: Probe padding record via $className.$bound()")
            }.onFailure { error ->
                Log.i("Failed: Probe padding record via $className.${methodNames.first()}()")
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

    /**
     * Instruments `adjust.ImeKeyboardResetView`, the pill that carries the move / reset / size
     * buttons.
     *
     * `e(show, isAdjust)` is the only display entry point, and the host picks the side inside it:
     * ```
     * left = Q.j(); right = Q.l();
     * if (left > right) f(...) else g(...)                 // f -> left copy, g -> right copy
     * if (m1.X1()) q3(this, null, m1.z1(t0(), 1, null))    // unfolded: pin the pill's width
     * ```
     * Logging the arguments next to the laid-out geometry separates a wrong *side* from a wrong
     * *width* - two failures that look identical on screen.
     */
    private fun hookKeyboardResetView() {
        runCatching {
            val owner = loadClassOrNull(RESET_VIEW) ?: error("Failed to resolve $RESET_VIEW")
            hookResetViewCompanion()
            hookResetViewCopy(owner)
            hookPillWidthSetter()
            val show = owner.declaredMethods.firstOrNull { candidate ->
                candidate.name == RESET_VIEW_SHOW &&
                    candidate.parameterTypes.size == 2 &&
                    candidate.parameterTypes[0] == PRIMITIVE_BOOLEAN &&
                    candidate.parameterTypes[1] == PRIMITIVE_BOOLEAN &&
                    candidate.returnType == PRIMITIVE_VOID
            }?.apply { isAccessible = true }
                ?: error("No $RESET_VIEW.$RESET_VIEW_SHOW(ZZ)")

            show.hookAfter { param ->
                val view = param.thisObject as? android.view.View ?: return@hookAfter
                if (resetViewReportCount.incrementAndGet() <= RESET_PILL_CALL_LIMIT) {
                    Log.i(pillLine("show", view, param.args))
                }
                watchResetViewLayout(view)
            }
            Log.i("Success: Probe single-hand action pill via $RESET_VIEW.$RESET_VIEW_SHOW()")
        }.onFailure { error ->
            Log.i("Failed: Probe single-hand action pill via $RESET_VIEW")
            Log.i(error)
        }
    }

    /**
     * Pins the pill's contents to the outer edge, at `m1.q3` - the one call that sizes them.
     *
     * **The complaint this answers.** In single-hand mode the keyboard docks to one side and the
     * pill covers the whole keyboard row (2364px here). The three-affordance column is *supposed*
     * to sit on the opposite outer edge - `ime_keyboard_reset_view_layout.xml` declares
     * `alignParentEnd` on both right-hand copies and `wrap_content` widths, and that is what the
     * host's own anchor logic does with them.
     *
     * What actually happens is that `f(ZZ)` / `g(ZZ)` hand every part of the tree the width of the
     * *freed* strip instead of letting it wrap:
     * ```
     * q3(row,  height = Q.d - scaled + Q.e, width = Q.j())     // the row
     * q3(cell, null,                        width = max(j,l))  // each button cell
     * ```
     * The cells centre their icon and label horizontally, so the whole column ends up centred in
     * the strip rather than pinned to its edge. On a phone the strip is ~40% of the width and the
     * result reads as "on the empty side"; on an unfolded inner screen the strip is 1351px wide and
     * the column floats in the middle of the screen instead.
     *
     * **The fix.** Rewrite the width argument to `null`. `q3` guards every assignment with
     * `if (width != null)`, so a null width means "leave it alone" and the view keeps the
     * `wrap_content` its layout declares - after which the host's own `alignParentEnd` does the
     * right thing. Nothing else is touched: the heights the host computes are load-bearing (they
     * are the row's laid-out height and the cells' weighted shares), and the pill itself is
     * excluded, because its full width *is* correct.
     *
     * **Scoped to the unfolded screen.** Gated on the host's own `m1.X1()`, the same predicate that
     * decides whether the host widens the pill at all. On the outer screen the host does not, the
     * strip already sits where the phone build wants it, and correcting it there would have moved
     * chrome nobody asked about. Reported after the first attempt did exactly that.
     *
     * Also logs the clamp, so a regression shows up as a missing line rather than as a mysterious
     * layout change.
     */
    private fun hookPillWidthSetter() {
        runCatching {
            val owner = loadClassOrNull(M1) ?: error("Failed to resolve $M1")
            val setter = owner.declaredMethods.firstOrNull { candidate ->
                candidate.name == PILL_WIDTH_SETTER &&
                    candidate.parameterTypes.size == 3 &&
                    candidate.parameterTypes[0] == android.view.View::class.java
            }?.apply { isAccessible = true }
                ?: error("No $M1.$PILL_WIDTH_SETTER(View, Integer, Integer)")

            setter.hookBefore { param ->
                val view = param.args?.getOrNull(0) as? android.view.View ?: return@hookBefore
                if (!isInsideResetPill(view)) return@hookBefore
                val width = param.args.getOrNull(2)
                val unfolded = unfoldedScreen()
                // Unfolded only. On the outer screen the host skips its own width pinning and the
                // strip already sits where the phone build wants it - clamping there would move
                // chrome the user never complained about, so the whole fix is scoped to the wide
                // screen it was reported on.
                val pin = HookSettings.unlockSingleHandMode && unfolded
                pillPinActive = pin
                if (pillWidthCount.incrementAndGet() <= PILL_WIDTH_LIMIT) {
                    Log.i(
                        "Pill width clamped: h=${param.args.getOrNull(1)} w=$width" +
                            " -> ${if (pin) "wrap_content" else "unchanged"}" +
                            " unfolded=$unfolded class=${view.javaClass.simpleName}"
                            + " parent=${(view.parent as? android.view.View)?.javaClass?.simpleName}"
                    )
                }
                if (!pin) return@hookBefore
                // `q3` skips a null width, so the row / cell keeps the `wrap_content` that
                // `ime_keyboard_reset_view_layout.xml` declares for it - and the host's own
                // `alignParentEnd` then pins it to the outer edge instead of the middle of the gap.
                param.args[2] = null
            }
            Log.i("Success: Probe pill width setter via $M1.$PILL_WIDTH_SETTER()")
        }.onFailure { error ->
            Log.i("Failed: Probe pill width setter via $M1.$PILL_WIDTH_SETTER()")
            Log.i(error)
        }
    }

    /**
     * Keeps the move / reset / size strip showing its three buttons instead of folding down to a
     * three-dot icon.
     *
     * **The complaint this answers.** The strip folds into a single `left_more_iv` / `right_more_iv`
     * dot icon inside `<side>_adjust_simple_ll` - and once folded it never comes back: the `onTouch`
     * branch for that icon sets `z = false` and then calls `f(0,0)` / `g(0,0)`, which is the same
     * fold again. The strip is a dead end after the first fold, which is exactly what the report
     * described ("the three dots on the right do nothing").
     *
     * The fold is decided in `f(ZZ)` / `g(ZZ)` and nowhere else - see
     * [RESET_VIEW_SHOW_SIMPLE_FLAG] for the condition. Both inputs have several writers (`z`:
     * `onTouch`'s move-handle drag, `onTouch`'s dot tap, `float.f.S()`, `ImeRootView`,
     * `WxHldService`, the pill's own `i()`; `A`: any handle press), so no single upstream value is
     * worth pinning.
     *
     * **Why the correction sits in `hookAfter` and not in the arguments.** 1.0.18-alpha registered
     * the forced expansion as a second `hookBefore` on these same two methods, and the run produced
     * not one line from it while this `hookAfter` reported normally - so the argument route is not
     * the dependable one here. Rewriting the *result* is: the host's own branch runs first, then
     * the three-button container of the side that branch chose is shown and its dot icon hidden.
     * The two flags are reset as well, so the host's next pass picks the expanded branch by itself
     * instead of relying on the correction.
     *
     * **Scoped to single-hand mode.** The first revision applied this unconditionally, on the
     * reasoning that the fold is a dead end in the plain keyboard-adjust panel too and that `e(ZZ)`
     * already gates the whole pill on `i1.k2()`. On device that turned out to be wrong: the strip is
     * also built for the two-handed boards, and the forced expansion reached them, which is the
     * 「三个按钮常驻是指在单手模式常驻，不要放进分体键盘、合体键盘」 correction. Both `f` and `g` now
     * return early unless the host itself reports one-handed mode, so every other board keeps the
     * host's own fold behaviour untouched.
     */
    private fun hookResetViewCopy(owner: Class<*>) {
        listOf(RESET_VIEW_LEFT_COPY, RESET_VIEW_RIGHT_COPY).forEach { name ->
            val left = name == RESET_VIEW_LEFT_COPY
            val method = owner.declaredMethods.firstOrNull { candidate ->
                candidate.name == name &&
                    candidate.parameterTypes.size == 2 &&
                    candidate.parameterTypes[0] == PRIMITIVE_BOOLEAN &&
                    candidate.parameterTypes[1] == PRIMITIVE_BOOLEAN
            }?.apply { isAccessible = true } ?: return@forEach

            method.hookAfter { param ->
                val view = param.thisObject as? android.view.View ?: return@hookAfter

                // Only one-handed mode is asked to keep the three buttons out. `f` / `g` are the
                // strip's *own* fold decision and they run for every board the host builds, so
                // forcing them unguarded is how the three buttons turned up on the two-handed board
                // as well. Measured on device, the strip is drawn whenever the host thinks
                // one-handed mode is on (`i1.k2()`), so gating on exactly that leaves the two-handed
                // boards with the host's own behaviour - dots when it wants dots - and nothing else
                // changes. The user's wording (2026-09-21): 「三个按钮常驻是指在单手模式常驻，不要放进
                // 分体键盘、合体键盘」.
                if (!singleHandActive()) {
                    if (resetViewCopyCount.incrementAndGet() <= RESET_PILL_COPY_LIMIT) {
                        Log.i(pillLine("copy:$name", view, param.args))
                    }
                    watchResetViewLayout(view)
                    return@hookAfter
                }

                val forced = forceStripButtons(view, left)
                pinStripFlags(owner)
                val line = "Strip $name: $forced pins=${stripFlagState()}" +
                    " kids[${pillChildren(view)}]"
                if (line != lastStripLine) {
                    lastStripLine = line
                    if (stripForceCount.incrementAndGet() <= STRIP_FORCE_REPORT_LIMIT) {
                        Log.i(line)
                    }
                }
                if (resetViewCopyCount.incrementAndGet() <= RESET_PILL_COPY_LIMIT) {
                    Log.i(pillLine("copy:$name", view, param.args))
                }
                watchResetViewLayout(view)
            }
        }
    }

    /**
     * Shows the three-button container of one side and hides the three-dot icon beside it.
     *
     * Addressed by resource entry name - `keyboard_adjust_left_rl` / `keyboard_adjust_right_rl`
     * for the buttons, `keyboard_adjust_left_simple_ll` / `keyboard_adjust_right_simple_ll` for the
     * dots - and by the `<merge>` order as a fallback, because the name lookup is the only part of
     * this that can come back empty.
     *
     * `<merge>` inflates straight into the pill, so the fallback order is the one the layout
     * declares: `[0] keyboard_adjust_left_simple_ll`, `[1] keyboard_adjust_left_rl`,
     * `[2] keyboard_adjust_right_simple_ll`, `[3] keyboard_adjust_right_rl`. Only the `_rl` row of
     * one side is ever shown; `_simple_ll` is the collapsed form, and it is also where the
     * `left_more_iv` / `right_more_iv` dot icon lives.
     */
    private fun forceStripButtons(pill: android.view.View, left: Boolean): String {
        val group = pill as? android.view.ViewGroup ?: return "no-group"
        val side = if (left) "left" else "right"
        var buttons: android.view.View? = null
        var dots: android.view.View? = null
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index) ?: continue
            when (childEntryName(child)) {
                "keyboard_adjust_${side}_rl" -> buttons = child
                "keyboard_adjust_${side}_simple_ll" -> dots = child
            }
        }
        val byName = buttons != null && dots != null
        if (!byName) {
            val base = if (left) 0 else 2
            if (dots == null) dots = group.getChildAt(base)
            if (buttons == null) buttons = group.getChildAt(base + 1)
        }
        buttons?.visibility = android.view.View.VISIBLE
        dots?.visibility = android.view.View.GONE
        return "$side byName=$byName buttons=vis${buttons?.visibility} dots=vis${dots?.visibility}"
    }

    /** The pill child's resource entry name, e.g. `keyboard_adjust_left_rl`; `null` when absent. */
    private fun childEntryName(child: android.view.View): String? = runCatching {
        val id = child.id
        if (id == 0) null else child.resources.getResourceEntryName(id)
    }.getOrNull()

    /**
     * Puts the pill's two fold flags back to what its own `<clinit>` starts with.
     *
     * `z` starts `true` (expanded) and `A` starts `false` (no handle pressed); between them they
     * are the whole of the fold decision. Resolved once, from inside a live `f` / `g` call.
     */
    private fun pinStripFlags(owner: Class<*>) {
        if (!resetViewFlagsResolved) {
            resetViewFlagsResolved = true
            resetViewShowSimpleField = staticFieldOrNull(owner, RESET_VIEW_SHOW_SIMPLE_FLAG)
            resetViewTouchedField = staticFieldOrNull(owner, RESET_VIEW_TOUCHED_FLAG)
        }
        resetViewShowSimpleField?.let { field ->
            runCatching { if (!field.getBoolean(null)) field.setBoolean(null, true) }
        }
        resetViewTouchedField?.let { field ->
            runCatching { if (field.getBoolean(null)) field.setBoolean(null, false) }
        }
    }

    private fun staticFieldOrNull(owner: Class<*>, name: String): java.lang.reflect.Field? =
        runCatching { owner.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()

    /** `z` / `A` as they stand after [pinStripFlags]; `?` when the field could not be resolved. */
    private fun stripFlagState(): String {
        val showSimple = resetViewShowSimpleField?.let { field ->
            runCatching { field.getBoolean(null) }.getOrNull()
        }
        val touched = resetViewTouchedField?.let { field ->
            runCatching { field.getBoolean(null) }.getOrNull()
        }
        return "showSimple=$showSimple touched=$touched"
    }

    /**
     * One line carrying everything that separates a wrong anchor from a wrong width.
     *
     * `getLocationOnScreen` is the ground truth and `view.x` is not: the latter is relative to the
     * parent, so a view can read `x=0` while sitting a thousand pixels into the screen.
     */
    private fun pillLine(tag: String, view: android.view.View, args: Array<Any?>?): String {
        val screen = IntArray(2)
        runCatching { view.getLocationOnScreen(screen) }
        val parent = view.parent as? android.view.View
        return "Reset pill [$tag]: args=$args vis=${view.visibility}" +
            " self=${view.width}x${view.height}@(${view.x},${view.y})" +
            " screen=(${screen[0]},${screen[1]}) lp=${view.layoutParams}" +
            " parent=${parent?.javaClass?.simpleName} pw=${parent?.width} px=${parent?.x}" +
            " chain[${viewChain(view)}]" +
            " kids[${pillChildren(view)}]" +
            " | q[${paddingFields(currentPaddingModel())}]"
    }

    /** `class(width@x,y)` from the pill up to the window root, so the containing box is visible. */
    private fun viewChain(view: android.view.View): String {
        val parts = mutableListOf<String>()
        var node: android.view.View? = view
        var depth = 0
        while (node != null && depth < VIEW_CHAIN_DEPTH) {
            val screen = IntArray(2)
            runCatching { node.getLocationOnScreen(screen) }
            parts += "${node.javaClass.simpleName}(${node.width}@${screen[0]},${screen[1]})"
            node = node.parent as? android.view.View
            depth++
        }
        return parts.joinToString(" < ")
    }

    /** `#i name vis wxh@x,y` for each stacked child of the pill; the name is its resource entry. */
    private fun pillChildren(view: android.view.View): String {
        val group = view as? android.view.ViewGroup ?: return "n/a"
        return (0 until minOf(group.childCount, VIEW_TREE_CHILDREN)).joinToString(",") { index ->
            val child = group.getChildAt(index)
            "#$index ${childEntryName(child) ?: child.javaClass.simpleName} vis=${child.visibility}" +
                " ${child.width}x${child.height}@(${child.x},${child.y})"
        }
    }

    /**
     * Captures the pill instance from its companion as a second, independent way in.
     *
     * Kept as a fallback rather than the primary route. `e(ZZ)` was once written off as never firing
     * on 3.5.3 because not one `Reset pill:` line followed the install-time registration line - but
     * that was the probe's own doing: the display hook and the layout dump shared a single counter,
     * the dump spent it within a couple of frames, and the display hook then stayed silent while the
     * pill was plainly on screen. With the budgets split, `e(ZZ)` reports every show. The companion
     * still earns its place, because a keyboard scene that reveals the pill without going through
     * `e(ZZ)` would otherwise stay invisible to the probe.
     */
    private fun hookResetViewCompanion() {
        runCatching {
            val companion = loadClassOrNull(RESET_VIEW_COMPANION)
                ?: error("Failed to resolve $RESET_VIEW_COMPANION")
            val toggle = companion.declaredMethods.firstOrNull { candidate ->
                candidate.name == RESET_VIEW_TOGGLE &&
                    candidate.parameterTypes.size == 1 &&
                    candidate.parameterTypes[0] == PRIMITIVE_BOOLEAN
            }?.apply { isAccessible = true }
                ?: error("No $RESET_VIEW_COMPANION.$RESET_VIEW_TOGGLE(Z)")

            // The pill's constructor is not reachable here: `declaredConstructors` yields
            // `Constructor`, while this hook API only provides `Method.hookAfter`. The companion's
            // show / hide entry point is an ordinary instance method and holds the instance in its
            // synthetic `this$0`, so the view is recovered from the callback instead.
            toggle.hookAfter { param ->
                val host = param.thisObject ?: return@hookAfter
                val view = runCatching {
                    host.javaClass.getDeclaredField(RESET_VIEW_HOST_FIELD)
                        .apply { isAccessible = true }
                        .get(host) as? android.view.View
                }.getOrNull() ?: return@hookAfter
                watchResetViewLayout(view)
            }
            Log.i("Success: Probe single-hand action pill toggle via $RESET_VIEW_COMPANION.$RESET_VIEW_TOGGLE()")
        }.onFailure { error ->
            Log.i("Failed: Probe single-hand action pill toggle")
            Log.i(error)
        }
    }

    /**
     * Registers the one-shot geometry dump for the pill.
     *
     * Deliberately *not* inlined at the hook site: with a SAM conversion nested inside the
     * `hookAfter { }` lambda the compiler resolves `addOnGlobalLayoutListener` against the hook API
     * and fails with "receiver type mismatch" on this very call. A plain method has no such
     * competing receiver.
     */
    /**
     * Registers the geometry dump for the pill, on the first call that can bind it.
     *
     * Two mechanisms, because neither alone is enough: `post` covers the common case where the host
     * reveals the pill *before* it is attached (the bounds are still zero then), and
     * `addOnLayoutChangeListener` covers the reverse. The listener is the load-bearing one - it is
     * the only callback that survives a view that is populated after the fact.
     *
     * The listener is an explicit object rather than a lambda: with a SAM conversion nested inside
     * a `hookAfter { }` body the compiler resolves the call against the hook API instead and fails
     * with "receiver type mismatch". This method has no such competing receiver, but keeping the
     * shape explicit means the same mistake cannot come back.
     */
    private fun watchResetViewLayout(view: android.view.View) {
        if (!resetViewBound.compareAndSet(false, true)) return
        val dump = Runnable { reportResetViewLayout(view) }
        if (view.width > 0) dump.run() else view.post(dump)
        view.addOnLayoutChangeListener(
            object : android.view.View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    changed: android.view.View,
                    left: Int,
                    top: Int,
                    right: Int,
                    bottom: Int,
                    oldLeft: Int,
                    oldTop: Int,
                    oldRight: Int,
                    oldBottom: Int,
                ) {
                    if (right - left <= 0) return
                    if (pillPinActive) {
                        (changed as? android.view.ViewGroup)?.let { pinResetPillWidths(it) }
                    }
                    reportResetViewLayout(changed)
                }
            }
        )
    }

    /**
     * `m1.X1()` - the host's own unfolded / wide-screen test.
     *
     * The same predicate the host uses to decide whether to pin the pill's width at all, so it is
     * the right gate for the pin: where the host does not widen anything, nothing has drifted and
     * nothing should be corrected. Read live rather than cached, because folding changes the answer
     * mid-session. Safe to call from a hook body - the module's own bypass of this method is only
     * armed inside `k2()` / `Y2()`.
     */
    private fun unfoldedScreen(): Boolean = callM1("X1") as? Boolean ?: false

    /** True when `view` is a row of the pill or a cell inside one - never the pill itself. */
    private fun isInsideResetPill(view: android.view.View): Boolean {
        var parent: android.view.ViewParent? = view.parent
        var depth = 0
        while (parent is android.view.View && depth < PILL_DESCENT_DEPTH) {
            if (parent.javaClass.name == RESET_VIEW) return true
            parent = parent.parent
            depth++
        }
        return false
    }

    /**
     * The host's own one-handed cut-off, in pixels - `m1.m0(130)`.
     *
     * `Mgr.x(...)` compares `max(left, right)` against this same call before it switches the mode
     * off, so reading it back rather than hard-coding a number is what keeps the floor and the
     * switch-over in lockstep on any density or screen width.
     *
     * Returns `0` when the helper cannot be reached, and callers treat that as "no floor" - a missing
     * number must never be turned into a silent zero-pixel gap.
     */
    private fun singleHandGapFloor(): Int =
        (callM1(DP_TO_PX, SINGLE_HAND_GAP_FLOOR_DP) as? Int)?.coerceAtLeast(0) ?: 0

    /**
     * Backstop for [hookPillWidthSetter]: wraps the pill's rows to their content and gives them
     * [RESET_PILL_EDGE_INSET_DP] of breathing room from the edge they are anchored to.
     *
     * The clamp on `q3` only catches the widths the host routes through that helper. A width written
     * straight onto `layoutParams` would slip past it, and the strip is long enough that
     * `alignParentEnd` alone cannot be trusted to hide the difference. Runs at layout time, where
     * the values are already in place.
     *
     * Idempotent by construction, which is what keeps it out of a layout loop: both writers bail out
     * when the value is already correct, so the re-layout they trigger comes back to a subtree that
     * needs nothing and stops there.
     *
     * Only active while [pillPinActive] holds, which is latched per `q3` call and therefore carries
     * the unfolded-screen gate with it.
     */
    private fun pinResetPillWidths(pill: android.view.ViewGroup) {
        val inset = edgeInsetPx(pill)
        for (index in 0 until pill.childCount) {
            val row = pill.getChildAt(index)
            pinRowToEdge(row, inset)
            val rowGroup = row as? android.view.ViewGroup ?: continue
            for (cellIndex in 0 until rowGroup.childCount) {
                // Only containers get unwrapped. A cell of the expanded row is a `RelativeLayout`
                // holding an icon *and* a caption, and the host hands it the whole blank-strip width;
                // wrapping it is what lets the pair sit against the edge. A cell of the collapsed row
                // is the icon itself, sized by `@dimen/ime_adjust_reset_button_direction_width_height`
                // (80px). Wrapping *that* drops it to the drawable's intrinsic size - measured at
                // 28px on device - which shrinks the chevron to a sliver. Leave leaf views alone.
                val cell = rowGroup.getChildAt(cellIndex)
                if (cell is android.view.ViewGroup) rewriteToWrapContent(cell)
            }
        }
    }

    /**
     * Wraps a row to its content and insets it from whichever edge it is anchored to.
     *
     * Both margins are set because the anchor differs per row - the right-hand copies declare
     * `alignParentEnd`, the left-hand ones declare no horizontal rule at all and land on the start
     * edge. A margin on the unused side is inert for a `wrap_content` row, so setting both keeps
     * this branch-free.
     */
    private fun pinRowToEdge(view: android.view.View, inset: Int) {
        val params = view.layoutParams as? android.view.ViewGroup.MarginLayoutParams ?: return
        if (params.width == android.view.ViewGroup.LayoutParams.WRAP_CONTENT &&
            params.marginStart == inset && params.marginEnd == inset
        ) {
            return
        }
        params.width = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        params.marginStart = inset
        params.marginEnd = inset
        view.layoutParams = params
    }

    /** [RESET_PILL_EDGE_INSET_DP] in pixels, resolved per view so a density change is honoured. */
    private fun edgeInsetPx(view: android.view.View): Int = runCatching {
        (RESET_PILL_EDGE_INSET_DP * view.resources.displayMetrics.density).toInt()
    }.getOrDefault(0)

    private fun rewriteToWrapContent(view: android.view.View) {
        val params = view.layoutParams as? android.view.ViewGroup.MarginLayoutParams ?: return
        if (params.width == android.view.ViewGroup.LayoutParams.WRAP_CONTENT) return
        params.width = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        view.layoutParams = params
    }

    /** The laid-out report: one geometry line plus the subtree. Budgeted, then silent. */
    private fun reportResetViewLayout(view: android.view.View) {
        if (resetViewTreeCount.incrementAndGet() > RESET_PILL_TREE_LIMIT) return
        Log.i(pillLine("laid-out", view, null))
        Log.i("Reset pill tree:${viewTree(view)}")
    }

    /** The live `model.Q` instance, read from `m1.H0()` at call time (never at install time). */
    private fun currentPaddingModel(): Any? = runCatching {
        val cls = loadClassOrNull(M1) ?: return@runCatching null
        val instance = cls.getDeclaredField("a").apply { isAccessible = true }.get(null)
            ?: return@runCatching null
        cls.declaredMethods.firstOrNull {
            it.name == "H0" && it.parameterTypes.size == 3
        }?.apply { isAccessible = true }?.invoke(instance, null, 1, null)
    }.getOrNull()

    /**
     * Installs the fold-gate bypass on **every** candidate spelling of a gate, then lets the
     * preference key decide which candidate was the real one.
     *
     * ### Why a candidate list is the whole design (3.5.4, 2026-09-23)
     *
     * This used to hook `k2()` and `Y2()` because that is what 3.5.3 called the gates. 3.5.4 renamed
     * the pair to `l2()` / `a3()` and handed both old names to unrelated members - `k2()` is now the
     * SMS-code autofill preference, `Y2()` a list-emptiness test. Both spellings still exist with the
     * right signature, so the install reported `Success` on 24 of 24 hooks while the gate this
     * module exists for was not hooked at all. A device report shows it once read against the key:
     * `j1.k2() = true | … userOn=false` - the gate saying "on" while the user's own preference said
     * "off".
     *
     * No name can be trusted, and no *shape* separates the gates either: `settings` declares 118
     * no-arg booleans in 3.5.4, one per preference, and the gates sit among them. What does separate
     * them is the anchor - a gate reads its own preference key, and a key is a string literal that no
     * obfuscator rewrites. So every candidate gets a window, and whichever one reads
     * [SINGLE_HAND_SETTING] from inside its own window becomes
     * [HostMethods.Role.SINGLE_HAND_GATE].
     *
     * Every spelling in the list gets a window, and a spelling this build does not declare says so
     * and is skipped - deliberately *not* reported as a failed hook, because a candidate list is
     * wider than any single build and `Bridge` builds its "N installed / M failed" line off these
     * very log prefixes. Counting a spelling that this build never had would put a false failure in
     * the user-facing report.
     *
     * A window on a spelling that turns out not to consult `X1()` is inert rather than harmless -
     * `foldGateBypass` is only ever *read* by the `X1()` hook, so a method whose body never reaches
     * `X1()` never consumes it. That is precisely why this list holds every gate spelling instead of
     * only the one the probe resolves: the **menu** gate (`a3()` / `Y2()`) is the one that decides
     * whether the one-hand switch is usable in the settings menu, and it never reads the anchor key,
     * so the probe can never resolve it. A list holding only the layout gate installs clean, resolves
     * clean and leaves the switch greyed out.
     */
    private fun hookSingleHandModeGate() {
        hookFoldGate()

        val settingsClass = loadClassOrNull(SETTINGS)
        if (settingsClass == null) {
            Log.e("Failed: Resolve WeType settings class for single-hand unlock")
            return
        }

        HostMethods.SINGLE_HAND_GATES.forEach { methodName ->
            val gateMethod = settingsClass.declaredMethods.firstOrNull { method ->
                method.name == methodName &&
                    method.parameterTypes.isEmpty() &&
                    method.returnType == PRIMITIVE_BOOLEAN
            }?.apply { isAccessible = true }
            if (gateMethod == null) {
                Log.i("Skip: $SETTINGS.$methodName() is not in this host build")
                return@forEach
            }
            runCatching {
                // Open the window right before the host evaluates the gate, and close it after, so
                // only this evaluation sees the bypassed fold gate.
                gateMethod.hookBefore {
                    if (HookSettings.unlockSingleHandMode) foldGateBypass.set(true)
                    gateWindowOwner.set(methodName)
                    gateWindowDepth.set(gateDepth() + 1)
                }
                gateMethod.hookAfter { param ->
                    // Read the window *before* closing it. Clearing first is what this did until
                    // 2026-09-22, and it made the `bypass` field in every report read `false` for
                    // good: four `Single-hand gate:` lines were captured three seconds after
                    // `Settings applied … unlockSingleHandMode=true`, and all four ended
                    // `bypass=false`. The field exists precisely to say whether the fold gate was
                    // bypassed during *this* evaluation, so it cannot be sampled after the reset.
                    val bypassed = foldGateBypass.get() == true
                    // ⚠️ The close has to happen *before* the trace, not just before the return.
                    // The trace reads the anchor key through the very reader that
                    // [hookGateAnchorProbe] watches, and that probe adopts whoever
                    // [gateWindowOwner] names - so a trace taken inside the window would make every
                    // candidate look like the gate, including the ones that are not. Closing first
                    // is what keeps the anchor evidence honest.
                    val depth = gateDepth() - 1
                    if (depth <= 0) {
                        gateWindowDepth.remove()
                        gateWindowOwner.remove()
                        foldGateBypass.set(false)
                    } else {
                        gateWindowDepth.set(depth)
                    }
                    // Deliberately *not* forcing the result. The gate has four conditions and the
                    // last one is the user's own `ime_enable_single_hand_mode`; overriding it would
                    // make the in-keyboard toggle one-way (it could turn the mode on but never
                    // off). Bypassing the fold gate is enough - the user's setting decides the rest.
                    // One line per *distinct* state, not per call: the gate is evaluated on every
                    // keyboard layout pass, and the first calls all land during start-up, when the
                    // user's own setting has not been read yet. Keying on the trace is what keeps
                    // the interesting transition (the setting turning on) inside the budget.
                    val trace = singleHandTrace(bypassed, methodName)
                    val seen = "$methodName=${param.result} $trace"
                    if (seen != lastGateTrace) {
                        lastGateTrace = seen
                        if (gateReportCount.incrementAndGet() <= GATE_TRACE_LIMIT) {
                            Log.i("Single-hand gate: $SETTINGS.$methodName() = ${param.result} | $trace")
                        }
                    }
                }
                Log.i("Success: Watch single-hand gate candidate $SETTINGS.$methodName()")
            }.onFailure { error ->
                Log.i("Failed: Watch single-hand gate candidate $SETTINGS.$methodName()")
                Log.i(error)
            }
        }

        hookGateAnchorProbe(settingsClass)
    }

    /**
     * Watches `settings`' generic preference reader and adopts the gate candidate that reads the
     * single-hand key from inside its own window.
     *
     * The confirmation half of [hookSingleHandModeGate]: the window says *where* the read happened,
     * the key says *which* preference it asked for, and only a `settings` method that asked for
     * `ime_enable_single_hand_mode` itself can satisfy both. Nothing is adopted without it, which is
     * the difference between this and the name lookup that 3.5.4 defeated.
     *
     * The probe disarms once the role is resolved. Until then it runs on every preference read in
     * the input method, so the body checks the window *first* - the common case is one `ThreadLocal`
     * read and an immediate return, with no `String` comparison at all.
     */
    private fun hookGateAnchorProbe(settingsClass: Class<*>) {
        val readerName = settingReader ?: return
        val probe = settingsClass.declaredMethods.firstOrNull {
            it.name == readerName &&
                it.parameterTypes.size == 2 &&
                it.returnType == PRIMITIVE_BOOLEAN
        }?.apply { isAccessible = true } ?: return
        runCatching {
            probe.hookBefore { param ->
                if (anchorProbeDisarmed.get()) return@hookBefore
                // Already a bare method name - see [gateWindowOwner]. This used to be a qualified
                // `"$SETTINGS#$methodName"` chopped down here, which is how the role came to hold
                // `j1#l2` in 1.0.27 and silenced every caller of `singleHandActive()`.
                val candidate = gateWindowOwner.get() ?: return@hookBefore
                val key = param.args?.firstOrNull() as? String ?: return@hookBefore
                if (key != SINGLE_HAND_SETTING) return@hookBefore
                HostMethods.resolve(
                    HostMethods.Role.SINGLE_HAND_GATE,
                    candidate,
                    SETTINGS,
                    "read '$key' from inside its own window"
                )
                anchorProbeDisarmed.set(true)
            }
            Log.i("Success: Watch single-hand anchor through $SETTINGS.$readerName(String, boolean)")
        }.onFailure { error ->
            Log.i("Failed: Watch single-hand anchor through $SETTINGS.$readerName(String, boolean)")
            Log.i(error)
        }
    }

    /**
     * The rest of the gate's conditions, so a field report names *which* one closed it.
     *
     * The gate is `!float.f.V() && !N.<eligible>(N.<kindOf>()) && !X1() && <the user's preference>`,
     * read off the bytecode rather than guessed. Each of the first three compiles to `if-nez v0, :out`
     * against a zero-initialised return slot, so all three are **negated** - `floating=false`,
     * `fold=false` and `kindOk=false` are the *satisfied* readings - while only the preference is a
     * positive `if-eqz`. (An earlier revision of the knowledge base had the first three the other way
     * round; the bytecode and a device log settling it are both in REV-06.)
     *
     * [bypassed] is handed in rather than read here because the caller owns the window's lifetime
     * and has already closed it. [foldGate] is sampled *after* that close on purpose, so it reports
     * the host's own verdict instead of the module's bypass.
     */
    private fun singleHandTrace(bypassed: Boolean, methodName: String): String = runCatching {
        val resolved = HostMethods.name(HostMethods.Role.SINGLE_HAND_GATE)
        "floating=${callOnSingleton(FLOAT_SINGLETON, "a", "V")}" +
            " fold=${callM1("X1")}" +
            " kind=${kindProbe()}" +
            " userOn=${readHostPreference(SINGLE_HAND_SETTING)}" +
            " sceneE=${keyboardSceneId("e")} sceneJ=${keyboardSceneId("j")}" +
            " bypass=$bypassed resolved=$resolved current=${resolved == methodName}"
    }.getOrElse { "trace failed: $it" }

    /**
     * `model.N`'s "current keyboard kind", reported together with the check it belongs to.
     *
     * The check half is resolved first and the getter half is taken from
     * [HostMethods.SINGLE_HAND_KIND_FOR] keyed on it, because the getter half cannot be resolved on
     * its own: 3.5.3's `model.N` declares both `t0()` and `u0()` as no-arg `Int` getters, so "newest
     * spelling that exists" answers `u0()` on a build whose gate reads `t0()` - a wrong number that
     * reads exactly like a right one, which is how this field first went wrong. The method name is
     * printed next to the value, so the line never shows a reading without its provenance.
     */
    private fun kindProbe(): String = runCatching {
        val model = loadClassOrNull(KEYBOARD_MODEL) ?: return@runCatching "n/a"
        val instance = model.getDeclaredField("a").apply { isAccessible = true }.get(null)
            ?: return@runCatching "n/a"
        fun member(name: String, params: Int, returns: Class<*>) = model.declaredMethods.firstOrNull {
            it.name == name && it.parameterTypes.size == params && it.returnType == returns
        }?.apply { isAccessible = true }

        val check = HostMethods.KIND_CHECKS.firstNotNullOfOrNull { member(it, 1, PRIMITIVE_BOOLEAN) }
            ?: return@runCatching "no check (${HostMethods.KIND_CHECKS.joinToString()})"
        val kindName = HostMethods.SINGLE_HAND_KIND_FOR[check.name]
            ?: return@runCatching "${check.name}/? (kind pairing unknown)"
        val kindOf = member(kindName, 0, PRIMITIVE_INT)
            ?: return@runCatching "${check.name}/$kindName missing"
        val kind = kindOf.invoke(instance)
        "$kindName=$kind/${check.name}=${check.invoke(instance, kind)}"
    }.getOrElse { "failed" }

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

            // Scope semantics - deliberately *not* consume-once.
            //
            // Each gate evaluates this as the *third* of four conditions, and the two ahead of it
            // (`float.f.V()` and the keyboard-kind pair) can reach this same method on their own. A
            // consume-once flag is therefore spent before the condition that needs it runs: the
            // real verdict survives, `!X1()` collapses to false, and the gate stays false no matter
            // what the user's own switch says. The window is opened by the candidate gates returned
            // by `HostMethods.SINGLE_HAND_GATES` and closed by those same hooks' after-callbacks, so
            // this body must leave the flag alone and only answer the question it was asked.
            //
            // ⚠️ `X1()` is **true on the unfolded inner screen and false on the folded outer one**
            // (measured 2026-09-23: `X1()=true` at `app=2364x1672`, `X1()=false` at `app=1168x1712`),
            // and every gate negates it. So forcing `false` here is what *unlocks* one-handed mode on
            // the large screen, which is the whole point of the switch - not an inversion of it.
            foldGateMethod.hookBefore { param ->
                if (foldGateBypass.get() == true) param.result = false
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
