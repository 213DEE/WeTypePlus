package cn.dsr213.wetypeplus.hook

import cn.dsr213.wetypeplus.bridge.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * The host methods whose *names* move between WeType builds, resolved by what they do.
 *
 * ### Why class names were not enough (3.5.4, 2026-09-23)
 *
 * [HostNames] fixes the class half of the problem: 3.5.4 shifted the whole `utils` family
 * (`m1` -> `n1`, `i1` -> `j1`, `Z0` -> `a1`) and resolving those names again restores 24 of 24
 * hooks. It does not fix the method half, and 3.5.4 moved those too - while keeping the old
 * spelling alive on an unrelated member:
 *
 * | role | 3.5.3 | 3.5.4 | what the 3.5.3 spelling does on 3.5.4 |
 * |---|---|---|---|
 * | single-hand layout gate | `i1.k2()` | `j1.l2()` | `k2()` reads `ime_enable_sms_verification_code_auto` |
 * | single-hand **menu** gate | `i1.Y2()` | `j1.a3()` | `Y2()` is a list-emptiness test (`L0()`, `isEmpty()`, `s1()`) |
 * | single-hand setter | `i1.a5(Z)` | `j1.d5(Z)` | `a5(Z)` writes `ime_plus_detail_quick_send` |
 * | split-keyboard gate | `Z0.P1()` | `a1.Q1()` | `P1()` reads `ime_show_voice_speed` |
 * | split-keyboard setter | `Z0.V3(Z)` | `a1.X3(Z)` | `V3(Z)` writes `ime_show_voice_speed` |
 *
 * Every stale spelling still **exists**, on the right class, with the right signature. A lookup
 * that only asks "is this member there" therefore succeeds and quietly hooks a different feature -
 * which is how 1.0.27 shipped with 24 green hooks and a single-hand mode that still did nothing.
 * On a device the log said so out loud, once you knew where to look:
 *
 * ```
 * Single-hand gate: j1.k2() = true | floating=false ... userOn=false bypass=true
 * ```
 *
 * `k2()` answered `true` while the user's own `ime_enable_single_hand_mode` read `false`. Two
 * different preferences behind one method name - and the module's whole single-hand layout path
 * (`singleHandActive`) was reading that name.
 *
 * ⚠️ **Two independent faults, and the first fix only addressed one.** The name drift above is what
 * the anchor was built for. The anchor worked - it picked `j1.l2()` out of 118 same-shaped
 * candidates on the first layout pass, in both the main and the `:hld` process - and single-hand
 * mode still did nothing, because the resolved name was stored as `"$SETTINGS#$methodName"` and
 * then handed to a caller that matches `Method.name` exactly. `j1#l2` never matches, `null` came
 * back, and `null == true` is `false`, which is indistinguishable from "the user has the mode off".
 * Both faults are recorded because either one alone produces the same 24-green-hooks report: a
 * green install says the module found *something*, never that it found the *right* thing, and
 * never that what it found is being asked for in the right shape.
 *
 * ### What decides instead
 *
 * A **preference key**. `ime_enable_single_hand_mode` and `ime_enable_split_keyboard_mode` are
 * string literals in the host's own code, and R8 does not rename string literals. The classes that
 * own them (`settings`, `paddingGate`) are per-preference repositories: each exposes one `()Z`
 * getter and one `(Z)V` setter per key, so "the member that touches this key" picks out exactly one
 * method per build, in both builds, whatever the obfuscator decided to call it.
 *
 * Names survive here as a **candidate list** only, and a candidate has to prove itself before the
 * module acts on it:
 *
 * - a **gate** proves itself by *reading* the key, seen from inside its own call - which is free,
 *   because the gate hook is already opening a window there for the fold-gate bypass;
 * - a **setter** proves itself by *writing* the key, seen by reading the key back around the call.
 *
 * Nothing is ever invoked before it has proved itself, so a stale candidate cannot make the module
 * write an unrelated preference. Until a role is resolved the callers degrade rather than guess:
 * [HostNames]' sibling reason - a wrong answer that looks right - is the one failure this module
 * cannot afford twice.
 *
 * ### Adding a host build
 *
 * Put the new spelling at the front of the candidate list. Nothing else changes: an unknown build
 * resolves by the anchor anyway, and a candidate list that is entirely wrong now costs two
 * discarded hooks and one `Failed:` line instead of a silently misplaced feature.
 */
internal object HostMethods {

    // ------------------------------------------------------------------ anchors

    /** `settings`' single-hand preference. The anchor, not a name. */
    const val SINGLE_HAND_KEY = "ime_enable_single_hand_mode"

    /** `paddingGate`'s split-keyboard preference. The anchor for the split pair. */
    const val SPLIT_KEY = "ime_enable_split_keyboard_mode"

    // ------------------------------------------------------------------ candidates

    /**
     * `settings`' single-hand gates, `()Z`. Newest host build first, and **all spellings, not just
     * the current one** - this list is what the fold-gate bypass is installed on.
     *
     * There are two of them per build, and they are not interchangeable:
     *
     * - `l2` / `k2` - the **layout** gate. `!float.f.V() && !N.check(N.singleHandKind()) &&
     *   !m1.X1() && <the user's own preference>`, and it is read from `model.Q` (the padding model),
     *   `adjust.b`, `settings.b` and the toolbar. This is the one that decides how the keyboard is
     *   laid out, and the only one that reads `ime_enable_single_hand_mode`.
     * - `a3` / `Y2` - the **menu** gate. Same first three conditions, but keyed on the *split*
     *   keyboard kind and finishing on a width test (`m1.z(...) >= b7.e.b(context, 320)`). Its only
     *   caller is `settings.a.e()`, for `R.string.s10_item_single_hand` - "is the one-hand item
     *   usable in the settings menu".
     *
     * ⚠️ **Dropping the menu gate is silent on every report the module produces.** Only the layout
     * gate reads the anchor key, so only the layout gate is ever *resolved* by the probe; a list
     * holding just that one still installs clean, still resolves, still logs a correct-looking
     * `Single-hand gate:` line - and leaves the switch greyed out in the menu, which is the one
     * thing a user notices. 1.0.27 shipped exactly that: the list was cut from `["k2","Y2"]` to
     * `["l2","k2"]`, which on 3.5.3 kept the layout gate and dropped the menu gate, and on 3.5.4
     * never had the menu gate at all (that build's `a3` was not in any list).
     *
     * So the rule for this list is not "which candidate is the gate" - the probe answers that -
     * but "which methods must see `X1()` as false". A candidate that turns out not to consult `X1()`
     * makes its window inert and costs nothing.
     */
    val SINGLE_HAND_GATES = listOf("l2", "a3", "k2", "Y2")

    /** `settings`' single-hand setter, `(Z)V`. */
    val SINGLE_HAND_SETTERS = listOf("d5", "a5")

    /** `paddingGate`'s split-keyboard gate, `()Z`. */
    val SPLIT_GATES = listOf("Q1", "P1")

    /** `paddingGate`'s split-keyboard setter, `(Z)V`. */
    val SPLIT_SETTERS = listOf("X3", "V3")

    /**
     * `settings`' generic `(String, boolean) -> boolean` preference reader.
     *
     * Every per-preference getter is a one-line wrapper around this one, which is what makes the key
     * observable at all. It kept its name across both builds; the candidate list is there for the
     * build that finally moves it.
     */
    val SETTING_READERS = listOf("B")

    /**
     * `model.N`'s "is that kind eligible" check, `(I) -> boolean` - `P1` from 3.5.4, `O1` up to
     * 3.5.3. Both gates negate this one, so `false` is the reading that lets a gate through.
     *
     * This half identifies a build on its own: 3.5.3's `model.N` declares `O1(I)` and no `P1(I)`,
     * and 3.5.4 declares both. Newest spelling first is therefore enough here.
     */
    val KIND_CHECKS = listOf("P1", "O1")

    /**
     * Which `model.N` kind getter belongs to each check spelling, for the single-hand gate.
     *
     * The getter half **cannot** be resolved by "the newest spelling that exists" the way the check
     * can: 3.5.3's `model.N` declares both `t0()` and `u0()` among its 13 no-arg `Int` getters, so a
     * newest-first walk answers `u0()` on a build whose gate actually reads `t0()`. That is a wrong
     * number that looks exactly like a right one - the failure mode this whole file exists to avoid
     * - and it is how the 3.5.4 trace came to print `kind=kotlinx.coroutines.flow.m@…` where an
     * `Int` belongs, `t0()` having by then become a `StateFlow` getter.
     *
     * Keyed on the check instead, the pair is unambiguous on both builds, and a future build that
     * moves a getter alone reports `?` rather than a plausible reading from the wrong method.
     *
     * (The same pairing for the *split* kind, which only the menu gate consults: `P1` -> `n0`,
     * `O1` -> `m0`.)
     */
    val SINGLE_HAND_KIND_FOR = mapOf("P1" to "u0", "O1" to "t0")

    // ------------------------------------------------------------------ resolved state

    enum class Role(val text: String) {
        SINGLE_HAND_GATE("single-hand gate"),
        SINGLE_HAND_SETTER("single-hand setter"),
        SPLIT_GATE("split-keyboard gate"),
        SPLIT_SETTER("split-keyboard setter")
    }

    /** Resolved member name per role. Written once, from a hook body, at most once per role. */
    private val resolved = ConcurrentHashMap<Role, String>()

    /** The member name that has proved itself for [role], or `null` while it is still unknown. */
    fun name(role: Role): String? = resolved[role]

    fun isResolved(role: Role, methodName: String): Boolean = resolved[role] == methodName

    /**
     * Drops every resolution, for a hot reload.
     *
     * A hot reload hands the module a **new class loader**, so the members these names point at
     * belong to the generation that just went away. Re-using them would name methods the new
     * generation cannot see; the anchors simply re-resolve on the next layout pass, which costs one
     * pass of "not in one-handed mode" and nothing else.
     */
    fun reset() {
        resolved.clear()
    }

    /**
     * Records that [methodName] on [owner] has been observed doing [role]'s job, quoting [evidence].
     *
     * [methodName] is normalised to a **bare member name** (`l2`) before it is stored, because
     * [name] hands it back to callers that use it as a lookup key against `Method.name`, and a
     * qualified label would make every one of them fail silently. [owner] is what carries the
     * class, for the log line only.
     *
     * A second claim on an already-resolved role is reported rather than swallowed: two different
     * members reading the same preference would mean the anchor itself is ambiguous, and keeping
     * whichever answered first would hide that.
     */
    fun resolve(role: Role, methodName: String, owner: String, evidence: String) {
        // A role is consumed as a `Method.name` argument - `singleHandActive()` passes it straight
        // to `callOnSingleton`, which matches `Method.name` exactly. 1.0.27 stored a qualified
        // `"j1#l2"` here instead, so the lookup asked for a method called `j1#l2` and got `null`
        // every time: the one-handed path went dead and reported nothing, because "no such method"
        // and "mode is off" are the same answer. Enforcing the invariant at the single writer
        // rather than at each reader is what keeps that failure mode from coming back through a
        // second call site. A qualified label is display material; [owner] carries it separately.
        val bare = methodName.substringAfterLast('#').substringAfterLast('.').removeSuffix("()")
        val previous = resolved.putIfAbsent(role, bare)
        when {
            previous == bare -> Unit
            previous == null ->
                Log.i("Success: Resolve WeType ${role.text} as $owner.$bare() - $evidence")
            else ->
                Log.i(
                    "Failed: WeType ${role.text} is ambiguous - $owner.$bare() also matched " +
                        "($evidence), keeping $owner.$previous()"
                )
        }
    }
}
