package com.eyreland.enderlink

import org.bukkit.World
import org.bukkit.event.Event
import org.bukkit.plugin.java.JavaPlugin
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

open class PluginTest {
    protected lateinit var server: ServerMock
    protected lateinit var plugin: JavaPlugin
    protected lateinit var world: World

    @BeforeTest
    fun setUp() {
        server = MockBukkit.mock(object : ServerMock() {
            private var stopping = false
            // MockBukkit 不实现停服；记录生产故障分支是否请求停服。
            override fun shutdown() { stopping = true }
            override fun isStopping() = stopping
        })
        plugin = requireNotNull(javaClass.classLoader.getResourceAsStream("plugin.yml")).use {
            MockBukkit.loadWith(TestPlugin::class.java, it)
        }
        plugin.description.permissions.forEach { server.pluginManager.addPermission(it) }
        world = server.addSimpleWorld("world")
    }

    @AfterTest
    fun tearDown() { MockBukkit.unmock() }

    protected fun dispatch(event: Event) { server.pluginManager.callEvent(event) }
}

open class TestPlugin : JavaPlugin()
