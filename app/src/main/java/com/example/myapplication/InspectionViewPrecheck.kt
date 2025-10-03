package com.example.myapplication

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class InspectionViewPrecheck(
    val id: Int,
    @SerialName("inspection-view-id") val inspectionViewId: Int,
    val url: String,
    @SerialName("response-attribute") val responseAttribute: String,
    @SerialName("response-value") val responseValue: String,
    @SerialName("success-message") val successMessage: String,
    @SerialName("error-message") val errorMessage: String
)
