package com.example.muzerion

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import java.util.Locale

/** The app intentionally defaults to English independently of the device language. */
class MuzerionApplication : Application() {
    override fun onCreate() { super.onCreate(); AppLanguage.init(this) }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLanguage.init(this)
    }
}
internal object AppLanguage {
    @Volatile var code: String = "en"
        private set
    private lateinit var app: Context
    val listeners=java.util.concurrent.CopyOnWriteArraySet<()->Unit>()
    val locale: Locale get() = Locale.forLanguageTag(code)
    @Volatile private lateinit var localizedResources: Resources
    val resources: Resources get() = localizedResources
    private fun saved(context: Context) = context.getSharedPreferences("muzerion_language",0)
        .getString("language","en").let {if(it=="ru") "ru" else "en"}
    fun init(context: Context) {
        app=context.applicationContext
        code=saved(context)
        Locale.setDefault(locale)
        localizedResources=wrap(app).resources
    }
    fun wrap(context: Context): Context {
        val language=saved(context)
        val configuration=Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList(Locale.forLanguageTag(language)))
        return context.createConfigurationContext(configuration)
    }
    fun select(context: Context, language: String) {
        require(language=="ru" || language=="en")
        if(language==code) return
        context.getSharedPreferences("muzerion_language",0).edit().putString("language",language).apply()
        code=language
        Locale.setDefault(locale)
        localizedResources=wrap(app).resources
        listeners.forEach {it()}
        generateSequence(context) {(it as? ContextWrapper)?.baseContext}
            .filterIsInstance<android.app.Activity>().firstOrNull()?.recreate()
    }
    fun tracks(count: Int) = resources.getQuantityString(R.plurals.track_count,count,count)
    fun webPage(html: String): String {
        if(code=="ru") return html
        val ruConfig=Configuration(app.resources.configuration).apply {setLocale(Locale.forLanguageTag("ru"))}
        val russian=app.createConfigurationContext(ruConfig).resources
        var result=html.replace("function countLabel(count) {", "function countLabel(count) { return count + (count === 1 ? ' track' : ' tracks');")
            .replace("[\"Б\", \"КБ\", \"МБ\", \"ГБ\", \"ТБ\"]", "[\"B\", \"KB\", \"MB\", \"GB\", \"TB\"]")
        for(id in webStrings) result=result.replace(russian.getString(id),resources.getString(id))
        return result.replace("lang=\"ru\"","lang=\"en\"").replace("ru-RU","en-US")
    }
    private val webStrings=intArrayOf(
        R.string.msg_a78ed42cdb,
        R.string.msg_1f0c00373c,
        R.string.msg_8bba719cd9,
        R.string.msg_a31f392414,
        R.string.msg_36041e8a0d,
        R.string.msg_8828a88273,
        R.string.msg_53eb25337a,
        R.string.msg_ea6a699a40,
        R.string.msg_e77d1ab0d0,
        R.string.msg_b781342279,
        R.string.msg_c1cb4dde1f,
        R.string.msg_218bfeb257,
        R.string.msg_8ef2b3bd1a,
        R.string.msg_1e1b70b17c,
        R.string.msg_05a491ff62,
        R.string.msg_dfd6eb45ef,
        R.string.msg_70c4bdc4e1,
        R.string.msg_8e1abb9475,
        R.string.msg_c97fa8b29b,
        R.string.msg_e7c745ab26,
        R.string.msg_27bccafb77,
        R.string.msg_6fb5cb3976,
        R.string.msg_10f6115072,
        R.string.msg_536a8d45bd,
        R.string.msg_92fd82aae5,
        R.string.msg_461d22517a,
        R.string.msg_2525af6b13,
        R.string.msg_ed030118aa,
        R.string.msg_e0ecc3666b,
        R.string.msg_3de49828e8,
        R.string.msg_aec93b16ba,
        R.string.msg_495e0759b8,
        R.string.msg_6433b522a1,
        R.string.msg_94e28a0a47,
        R.string.msg_10de9d74b2
    )
}
internal fun tr(id: Int,vararg args: Any?): String =
    if(args.isEmpty()) AppLanguage.resources.getString(id) else AppLanguage.resources.getString(id,*args)
