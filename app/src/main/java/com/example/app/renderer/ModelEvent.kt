package com.example.app.renderer

sealed class ModelEvent {
    data class Move(val x: Float, val y: Float) : ModelEvent()
    data class Update(val rotate: Float, val scale: Float) : ModelEvent()
}