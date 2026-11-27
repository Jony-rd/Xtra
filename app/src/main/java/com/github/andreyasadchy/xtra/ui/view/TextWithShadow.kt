package com.github.andreyasadchy.xtra.ui.view

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

class TextWithShadow : AppCompatTextView {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    override fun onDraw(canvas: Canvas) {
        if (background != null) {
            super.onDraw(canvas)
            return
        }
        for (i in 0..1) {
            super.onDraw(canvas)
        }
    }
}