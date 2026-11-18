package io.nekohasekai.sfa.conductor

internal fun hasPendingUserInteraction(
    items: List<ConductorItem>,
    hasFileRequest: Boolean,
    hasVfsConsent: Boolean,
): Boolean =
    hasFileRequest ||
        hasVfsConsent ||
        items.any { item ->
            when (item) {
                is ConductorItem.Confirmation -> true
                is ConductorItem.Question -> !item.answered && !item.cancelled
                else -> false
            }
        }

internal fun shouldShowYourTurnHint(
    atBottom: Boolean,
    hasPendingUserInteraction: Boolean,
    turnEndedWhileAway: Boolean,
): Boolean = !atBottom && (hasPendingUserInteraction || turnEndedWhileAway)
