package com.example.domain.model

enum class CutMode(val id: String, val displayName: String, val description: String) {
    FAST_CUT(
        id = "fast",
        displayName = "Fast Cut",
        description = "Fast cutting with minimal processing and no intentional re-encoding."
    ),
    PRECISE_CUT(
        id = "precise",
        displayName = "Precise Cut",
        description = "More accurate cutting using re-encoding/keyframe handling when supported."
    )
}
