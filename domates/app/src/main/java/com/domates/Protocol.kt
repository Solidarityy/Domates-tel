package com.domates

/**
 * JSON message type constants shared between all components.
 * Single source of truth so string literals never drift.
 */
object MessageType {
    // Device → Server
    const val TASK = "task"
    const val SCREEN_STATE = "screen_state"
    const val ACTION_ACK = "action_ack"

    // Server → Device
    const val SCREEN_REQUEST = "screen_request"
    const val ACTION = "action"
    const val TASK_RESULT = "task_result"
}

object ActionType {
    const val CLICK = "click"
    const val TYPE = "type"
    const val SCROLL = "scroll"
    const val SWIPE = "swipe"
    const val PRESS = "press"
    const val OPEN_APP = "open_app"
    const val WAIT = "wait"
    const val DONE = "done"
}
