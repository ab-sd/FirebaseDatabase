package com.example.basicfiredatabase.utils

import android.content.Context
import com.example.basicfiredatabase.R

/**
 * Translate an English event type (stored in Firestore) to a string resource.
 * Keeps a normalized mapping so minor apostrophe/whitespace differences are tolerated.
 */
object EventTypeTranslator {

    private fun normalize(s: String): String =
        s.trim()
            .replace('\u2019', '\'') // normalize typographic apostrophe to ASCII
            .replace(Regex("\\s+"), " ") // collapse repeated spaces
            .lowercase()

    // Map normalized English phrase -> string resource id (one canonical source of truth).
    private val map: Map<String, Int> = mapOf(
        normalize("Community Feeding Program") to R.string.type_community_feeding_program,
        normalize("Back-to-School Drive") to R.string.type_back_to_school_drive,
        normalize("Children’s Health and Wellness Fair") to R.string.type_childrens_health_and_wellness_fair,
        normalize("Sports and Recreation Day") to R.string.type_sports_and_recreation_day,
        normalize("Community Clean-Up and Beautification") to R.string.type_community_clean_up_and_beautification,
        normalize("Food and Hygiene Pack Distribution") to R.string.type_food_and_hygiene_pack_distribution,
        normalize("Emergency Relief Fundraising Event") to R.string.type_emergency_relief_fundraising_event
    )

    /**
     * Returns the localized string for the English eventType stored in Firestore.
     * If no mapping exists, returns the original English value (safe fallback).
     */
    fun getLocalized(ctx: Context, englishValue: String?): String {
        if (englishValue.isNullOrBlank()) return ""
        val key = normalize(englishValue)
        val resId = map[key]
        return if (resId != null) ctx.getString(resId) else englishValue
    }
}