package com.example.myapplication

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.animation.AnimationUtils

fun View.applyPressAnimation(context: Context, onClick: (() -> Unit)? = null) {
    var isPressedInside = false

    setOnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isPressedInside = true
                view.clearAnimation()
                view.startAnimation(AnimationUtils.loadAnimation(context, R.anim.btn_scale_down))
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val stillInside = event.x >= 0f && event.x <= view.width &&
                    event.y >= 0f && event.y <= view.height

                when {
                    stillInside && !isPressedInside -> {
                        isPressedInside = true
                        view.clearAnimation()
                        view.startAnimation(AnimationUtils.loadAnimation(context, R.anim.btn_scale_down))
                    }

                    !stillInside && isPressedInside -> {
                        isPressedInside = false
                        view.clearAnimation()
                        view.startAnimation(AnimationUtils.loadAnimation(context, R.anim.btn_scale_up))
                    }
                }

                true
            }

            MotionEvent.ACTION_UP -> {
                val shouldClick = isPressedInside
                isPressedInside = false
                view.clearAnimation()
                view.startAnimation(AnimationUtils.loadAnimation(context, R.anim.btn_scale_up))
                if (shouldClick) onClick?.invoke()
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                isPressedInside = false
                view.clearAnimation()
                view.startAnimation(AnimationUtils.loadAnimation(context, R.anim.btn_scale_up))
                true
            }

            else -> false
        }
    }
}
