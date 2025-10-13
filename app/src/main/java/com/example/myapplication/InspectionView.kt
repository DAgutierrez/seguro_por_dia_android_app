package com.example.myapplication

import kotlinx.serialization.Serializable

@Serializable
data class InspectionView(
    val id: Int,
    val description: String,
    val camera_position: String? = null,
    val created_at: String? = null,
    val updated_at: String? = null
)
