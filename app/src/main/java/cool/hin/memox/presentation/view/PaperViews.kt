package cool.hin.memox.presentation.view

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageButton
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import cool.hin.memox.R
import cool.hin.memox.presentation.dp

fun Context.paperText(value: CharSequence, size: Float = 15f, bold: Boolean = false,
    color: Int = R.color.paper_ink): TextView = TextView(this).apply {
    text = value
    textSize = size
    setTextColor(ContextCompat.getColor(context, color))
    typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    setLineSpacing(3.dp.toFloat(), 1.12f)
    includeFontPadding = false
}

fun Context.paperColumn(padding: Int = 0) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(padding.dp, padding.dp, padding.dp, padding.dp)
}

fun Context.paperRow() = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun LinearLayout.paperAdd(view: View, top: Int = 0, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) {
    addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
        if (height >= 0) height.dp else height).apply { topMargin = top.dp })
}

fun Context.paperCard(content: View) = MaterialCardView(this).apply {
    radius = 20.dp.toFloat()
    cardElevation = 1.dp.toFloat()
    setCardBackgroundColor(ContextCompat.getColor(context, R.color.paper_card))
    strokeWidth = 1.dp
    setStrokeColor(ContextCompat.getColor(context, R.color.paper_line))
    addView(content, ViewGroup.LayoutParams(-1, -2))
}

fun Context.paperButton(title: String, primary: Boolean = false, onClick: () -> Unit) =
    MaterialButton(this).apply {
        text = title
        isAllCaps = false
        textSize = 13f
        cornerRadius = 24.dp
        minHeight = 48.dp
        insetTop = 0
        insetBottom = 0
        backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context,
            if (primary) R.color.paper_accent else R.color.paper_wash))
        setTextColor(if (primary) android.graphics.Color.WHITE else ContextCompat.getColor(context, R.color.paper_ink))
        setOnClickListener { onClick() }
    }

fun View.paperPill(context: Context, color: Int = R.color.paper_wash) {
    background = GradientDrawable().apply {
        setColor(ContextCompat.getColor(context, color))
        cornerRadius = 28.dp.toFloat()
    }
}

fun Context.paperIcon(icon: Int, description: String, onClick: (View) -> Unit) = ImageButton(this).apply {
    setImageResource(icon)
    imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.paper_muted))
    contentDescription = description
    setPadding(10.dp, 10.dp, 10.dp, 10.dp)
    background = null
    isFocusable = true
    setOnClickListener(onClick)
}
