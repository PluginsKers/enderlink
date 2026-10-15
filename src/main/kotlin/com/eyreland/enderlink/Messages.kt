package com.eyreland.enderlink

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.renderer.TranslatableComponentRenderer
import net.kyori.adventure.translation.TranslationStore
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.Locale
import java.util.PropertyResourceBundle

object Messages {
    private val translations = TranslationStore.messageFormat(Key.key("enderlink:messages")).apply {
        defaultLocale(Locale.US)
        for (locale in listOf(Locale.US, Locale.SIMPLIFIED_CHINESE)) {
            val path = "/lang/${locale.toString().lowercase(Locale.ROOT)}.properties"
            val bundle = requireNotNull(Messages::class.java.getResourceAsStream(path)) { path }
                .reader(Charsets.UTF_8).use(::PropertyResourceBundle)
            registerAll(locale, bundle, true)
        }
    }
    private val renderer = TranslatableComponentRenderer.usingTranslationSource(translations)

    fun text(sender: CommandSender, key: String, vararg args: Component): Component =
        text((sender as? Player)?.locale() ?: Locale.US, key, *args)

    fun text(locale: Locale, key: String, vararg args: Component): Component =
        renderer.render(Component.translatable("enderlink.$key", *args), locale)
}
