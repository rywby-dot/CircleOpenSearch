package org.circleopensearch.capturetests

import org.circleopensearch.SingleCapture

fun main() {
    val early = SingleCapture<String>()
    check(early.receive("before-onShow"))
    check(early.phase == SingleCapture.Phase.READY && early.value == "before-onShow")
    check(!early.receive("duplicate") && early.value == "before-onShow")
    early.timeout()
    check(early.phase == SingleCapture.Phase.READY)
    early.close()
    check(early.value == null && !early.receive("late-after-hide"))
    val missing = SingleCapture<String>()
    missing.timeout()
    check(missing.phase == SingleCapture.Phase.TIMED_OUT)
    check(missing.receive("delayed") && missing.phase == SingleCapture.Phase.READY)
    val denied = SingleCapture<String>()
    check(denied.receive(null) && denied.phase == SingleCapture.Phase.UNAVAILABLE)
    check(!denied.receive("second-image"))
    val next = SingleCapture<String>()
    check(next.phase == SingleCapture.Phase.WAITING && next.value == null)
    println("SingleCapture: early callback, duplicate, timeout, denial, close and fresh-session checks passed")
}
