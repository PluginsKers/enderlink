package com.eyreland.enderlink

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.renderer.TranslatableComponentRenderer
import net.kyori.adventure.translation.TranslationStore
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.text.MessageFormat
import java.util.Locale
import java.util.Properties

object Messages {
    @Volatile
    private var renderer = load()

    @Synchronized
    fun reload(plugin: JavaPlugin) {
        renderer = load(plugin)
    }

    private fun load(plugin: JavaPlugin? = null): TranslatableComponentRenderer<Locale> {
        val translations = TranslationStore.messageFormat(Key.key("enderlink:messages"))
        translations.defaultLocale(Locale.US)
        for (locale in listOf(Locale.US, Locale.SIMPLIFIED_CHINESE)) {
            val path = "lang/${locale.toString().lowercase(Locale.ROOT)}.properties"
            val properties = Properties()
            requireNotNull(Messages::class.java.getResourceAsStream("/$path")) { path }
                .reader(Charsets.UTF_8).use(properties::load)
            if (plugin != null) {
                val file = File(plugin.dataFolder, path)
                if (!file.exists()) plugin.saveResource(path, false)
                file.reader(Charsets.UTF_8).use(properties::load)
            }
            for (key in properties.stringPropertyNames()) {
                val format = try {
                    MessageFormat(properties.getProperty(key).replace("'", "''"), locale)
                } catch (failure: IllegalArgumentException) {
                    throw IllegalArgumentException("Invalid translation '$key' in $path", failure)
                }
                translations.register(key, locale, format)
            }
        }
        return TranslatableComponentRenderer.usingTranslationSource(translations)
    }

    fun text(sender: CommandSender, key: String, vararg args: Component): Component =
        text((sender as? Player)?.locale() ?: Locale.US, key, *args)

    fun text(locale: Locale, key: String, vararg args: Component): Component =
        renderer.render(Component.translatable("enderlink.$key", *args), locale)
}
