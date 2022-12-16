package io.programmes_radio.www.progradio

import kotlinx.serialization.Serializable

@Serializable
data class Radio(
    val codeName: String,
    val name: String,
    val streamUrl: String,
    val pictureUrl: String?,
    val channelName: String?,
)