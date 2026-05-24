package services

import java.util.Locale
import java.util.ResourceBundle

object Messages {
    private val bundle: ResourceBundle = ResourceBundle.getBundle("messages", Locale.getDefault())

    fun get(key: String): String {
        return bundle.getString(key)
    }

    fun get(key: String, vararg args: Any): String {
        val format = bundle.getString(key)
        return String.format(Locale.getDefault(), format, *args)
    }
}
