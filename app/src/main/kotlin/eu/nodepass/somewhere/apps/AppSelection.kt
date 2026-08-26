// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.apps

/**
 * How the user asked for applications to be treated.
 *
 * Stored, and therefore allowed to be a preference that means nothing yet — a
 * mode with an empty set is a perfectly ordinary thing to have saved. What gets
 * applied to a tunnel is [AppRule], which is a different type for a reason.
 */
enum class SelectionMode {
    /** Every application on the device is carried. The default and today's behaviour. */
    Everything,

    /** Only the named applications are carried. */
    OnlyThese,

    /** Every application except the named ones is carried. */
    AllButThese,
}

/** The stored answer: a mode, and the packages it names. */
data class AppSelection(
    val mode: SelectionMode = SelectionMode.Everything,
    val packages: Set<String> = emptySet(),
)

/**
 * What a tunnel is actually built with.
 *
 * Android offers two mutually exclusive builder calls —
 * `addAllowedApplication` and `addDisallowedApplication` — and using both on
 * one builder throws. So this is a sealed type with one case per call rather
 * than a pair of collections that could both be non-empty: the illegal state
 * is not representable, instead of being prevented by a comment.
 *
 * There is no `Everything` case, and that is the second thing this type
 * enforces. **NW-A-04: the client is always outside its own tunnel** — its
 * traffic would otherwise loop back through the tunnel it is carrying — so
 * there is always at least one application to exclude, and "carry everything"
 * is spelled `AllButThese(self)`.
 */
sealed interface AppRule {
    val packages: Set<String>

    /** `addAllowedApplication` for each. Nothing else reaches the tunnel. */
    data class OnlyThese(
        override val packages: Set<String>,
    ) : AppRule

    /** `addDisallowedApplication` for each. Everything else reaches it. */
    data class AllButThese(
        override val packages: Set<String>,
    ) : AppRule
}

/**
 * The rule this selection becomes on a device that has [installed] installed.
 *
 * Two filters, both of which prevent a specific failure.
 *
 * **Packages that are no longer installed are dropped.** Both builder calls
 * throw `NameNotFoundException` for a package the device does not have, and an
 * exception there does not fail the selection — it fails `establish()`, so a
 * stale entry from an uninstalled application takes the whole tunnel down.
 *
 * **This client is forced out, in every mode.** Not offered, not defaulted:
 * removed from [AppRule.OnlyThese] and added to [AppRule.AllButThese], so
 * there is no path through this function that puts it inside the tunnel.
 */
fun AppSelection.ruleFor(
    installed: Set<String>,
    self: String,
): AppRule {
    val present = packages.filter { it in installed }.toSet()
    return when (mode) {
        SelectionMode.Everything -> AppRule.AllButThese(setOf(self))
        SelectionMode.OnlyThese -> AppRule.OnlyThese(present - self)
        SelectionMode.AllButThese -> AppRule.AllButThese(present + self)
    }
}

/** True when this rule means the tunnel carries no application at all. */
val AppRule.carriesNothing: Boolean
    get() = this is AppRule.OnlyThese && packages.isEmpty()
