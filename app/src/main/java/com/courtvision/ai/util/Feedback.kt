package com.courtvision.ai.util

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import com.courtvision.ai.R

/** Short confirmation message ("Saved") shown whenever the user saves something. */
object Feedback {
    private var toast: Toast? = null

    fun show(context: Context, @StringRes text: Int = R.string.saved) {
        toast?.cancel()
        toast = Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).also { it.show() }
    }
}
