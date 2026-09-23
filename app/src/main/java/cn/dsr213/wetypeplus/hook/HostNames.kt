package cn.dsr213.wetypeplus.hook

import cn.dsr213.wetypeplus.bridge.Log
import cn.dsr213.wetypeplus.bridge.loadClassOrNull

/**
 * The host's obfuscated class names, discovered at install time instead of written down.
 *
 * ### Why this exists (WeType 3.5.4, 2026-09-23)
 *
 * Hooking a shipped app means naming classes R8 is free to rename, and 3.5.4 renamed precisely the
 * ones this module needs. Inside `com.tencent.wetype.plugin.hld.utils` the whole `m1` family moved to
 * `n1` (`m1$b` -> `n1$b`, `m1$h0` -> `n1$h0`, `m1$t0` -> `n1$t0`, `m1$P` -> `n1$P`), `i1` moved to
 * `j1`, and `Z0` moved to `a1`. The method names did *not* move: the class that is now `n1` still
 * declares `g1()`/`X1()`/`B2(IIIII)`/`q3(View,Integer,Integer)`, exactly as the hooks expect.
 *
 * So what broke was never a version check - this module has none. It loaded, installed what it could
 * (12 of 24), and failed the rest on names that had gone stale.
 *
 * ### ⚠️ The trap this has to avoid
 *
 * After the shift, `m1` and `i1` still **exist** as classes: unrelated 5- and 2-method classes moved
 * into the freed names. A lookup that only asks "did the class load?" therefore *succeeds* and hands
 * back the wrong class, and every member lookup on it fails with a message that points at the right
 * name for the wrong reason. That is exactly the log shape of the 3.5.4 failure:
 *
 * ```
 * Failed: Raise keyboard width ceiling via ...utils.m1.g1()
 * ...utils.m1#g1(): Double          <- class found, member missing
 * ```
 *
 * Hence a candidate is accepted only when it declares the members this module actually hooks, which
 * is a property of the code and not of the obfuscator's current mood.
 *
 * ### Adding a host build
 *
 * Put the new name at the front of the relevant candidate list in [candidates]. Nothing else needs
 * to change; validation decides. A build whose names are all unknown degrades to "expected name",
 * so the failure still reads the way a report expects it to, and the log says so plainly.
 */
internal object HostNames {

    private const val UTILS = "com.tencent.wetype.plugin.hld.utils."

    /** Successfully resolved role -> full class name. Failures are deliberately not cached. */
    private val resolved = mutableMapOf<String, String>()

    /**
     * Candidate simple names per role, newest host build first.
     *
     * The order is a speed hint, not a decision: every candidate is validated, so listing a name
     * that a given build does not have simply costs one failed `Class.forName`.
     */
    private val candidates = mapOf(
        // WxImeUIUtil - the single source of every keyboard size constant. Also the outer class of
        // the width cache item and the width providers, which is why so much hangs off it.
        "widthUtil" to listOf("n1", "m1"),
        // The host settings singleton that owns the single-hand-mode gate.
        "settings" to listOf("j1", "i1"),
        // Holds `P1()` (split keyboard) and `V3(Z)`, its setter.
        "paddingGate" to listOf("a1", "Z0")
    )

    /** `utils.n1` on 3.5.4, `utils.m1` up to 3.5.3. */
    val widthUtil: String get() = role("widthUtil")

    /** The width cache item (`AppScreenWidthCacheItem`), the instance token for the width override. */
    val widthItem: String get() = widthUtil + "\$b"

    /** The cache base shared by the width/height items; `d(boolean)` is its whole cache logic. */
    val widthCacheBase: String get() = widthUtil + "\$h0"

    val widthProviderMain: String get() = widthUtil + "\$t0"

    val widthProviderMax: String get() = widthUtil + "\$P"

    /** `utils.j1` on 3.5.4, `utils.i1` up to 3.5.3. */
    val settings: String get() = role("settings")

    /** `utils.a1` on 3.5.4, `utils.Z0` up to 3.5.3. */
    val paddingGate: String get() = role("paddingGate")

    private fun role(key: String): String {
        resolved[key]?.let { return it }
        val list = candidates.getValue(key)
        val hit = list.firstOrNull { name -> matches(key, UTILS + name) }
        if (hit == null) {
            // Name the expected class rather than an empty string: a failing hook should still read
            // the way the module was written, and this line is what tells a report why it failed.
            Log.i(
                "HostNames: no $key candidate matched (${list.joinToString()}) - " +
                    "this WeType build is newer than the module knows about"
            )
            return UTILS + list.first()
        }
        val full = UTILS + hit
        if (hit != list.first()) {
            Log.i("HostNames: $key resolved to $hit, not the preferred ${list.first()}")
        } else {
            Log.i("HostNames: $key = $hit")
        }
        resolved[key] = full
        return full
    }

    /**
     * Identity test: does this class look like the one the role names?
     *
     * Reflection only - `Class.forName(name, false, loader)` never runs the class initialiser, which
     * matters here: touching a host static at install time is what killed the input method process
     * in 1.0.x and is forbidden for every class named in this module.
     */
    private fun matches(key: String, className: String): Boolean {
        val cls = loadClassOrNull(className) ?: return false
        val methods = runCatching { cls.declaredMethods }.getOrNull() ?: return false
        fun declares(name: String, params: Int, returns: Class<*>?): Boolean = methods.any {
            it.name == name && it.parameterTypes.size == params &&
                (returns == null || it.returnType == returns)
        }
        return when (key) {
            // Both width-chain entry points: the scale factor and the unfolded-screen gate.
            "widthUtil" -> declares("g1", 0, Double::class.javaPrimitiveType) &&
                declares("X1", 0, Boolean::class.javaPrimitiveType)
            // Both halves of the single-hand gate - **and the preference reader**.
            //
            // The reader is the part that carries weight. It is what every anchor in [HostMethods]
            // reads a preference key through, so "the class that declares it" is a property of the
            // code rather than of the obfuscator's current mood - and it happens to separate the two
            // builds cleanly: 3.5.3's `i1` has it with 393 members, 3.5.4's `j1` with 400, against 3
            // and 2 members for the empty classes that moved into the other spelling.
            //
            // ⚠️ The `k2`/`Y2` names are the module's *own* gate candidates and are only a second
            // fingerprint. They are not evidence of anything on their own: 3.5.4 kept both alive on
            // unrelated members (`k2()` reads `ime_enable_sms_verification_code_auto`, `Y2()` tests
            // a list for emptiness), so they pass here for a different reason on each build. Take
            // them out before trusting them for a build neither of these two is.
            "settings" -> HostMethods.SETTING_READERS.any {
                declares(it, 2, Boolean::class.javaPrimitiveType)
            } && declares("k2", 0, Boolean::class.javaPrimitiveType) &&
                declares("Y2", 0, Boolean::class.javaPrimitiveType)
            "paddingGate" -> declares("P1", 0, Boolean::class.javaPrimitiveType) &&
                declares("V3", 1, Void.TYPE)
            else -> false
        }
    }
}
