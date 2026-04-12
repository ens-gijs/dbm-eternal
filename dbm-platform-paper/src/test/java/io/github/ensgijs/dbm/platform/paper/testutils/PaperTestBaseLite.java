//package io.github.ensgijs.dbm.platform.paper.testutils;
//
//import io.papermc.paper.plugin.configuration.PluginMeta;
//import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
//import org.bukkit.Bukkit;
//import org.bukkit.Server;
//import org.bukkit.configuration.file.FileConfiguration;
//import org.bukkit.configuration.file.YamlConfiguration;
//import org.bukkit.inventory.ItemFactory;
//import org.bukkit.plugin.Plugin;
//import org.bukkit.plugin.PluginManager;
//import org.jetbrains.annotations.NotNull;
//import org.jetbrains.annotations.Nullable;
//import org.junit.jupiter.api.BeforeEach;
//
//import java.util.*;
//import java.util.logging.*;
//
//import static org.mockito.ArgumentMatchers.any;
//import static org.mockito.Mockito.*;
//
///// This file should only use mockito from the world of testing, not junit, etc.
//public class PaperTestBaseLite {
//    // These need to have a long-lived scope for Bukkit 1.14+ which uses weak refs
//    private static final Logger BASE_LOGGER;
//    protected BukkitSchedulerFake fakeBukkitScheduler;
//    protected Server fakeServer;
//    protected ItemFactory fakeItemFactory;
//    protected PluginManager fakePluginManager;
////    protected Server.Spigot fakeServerSpigot;
//    /// plugin name -> plugin instance
//    protected Map<String, Plugin> plugins;
//
//    static {
//        ConsoleHandler handler = new ConsoleHandler();
//        handler.setFormatter(new SimpleFormatter() {
//            private static final String format = "[%1$tT.%1$tL] [%2$20s / %3$-7s : %4$24s] %5$s%n";
//
//            @Override
//            public synchronized String format(LogRecord lr) {
//                Optional<Thread> thread = getThread(lr.getLongThreadID());
//                String threadName = thread.isPresent() ? thread.get().getName() : "tid:" + lr.getLongThreadID();
//                return String.format(format,
//                        new Date(lr.getMillis()),
//                        threadName,
//                        lr.getLevel().getLocalizedName(),
//                        lr.getLoggerName(),
//                        lr.getMessage()
//                );
//            }
//        });
//
//        BASE_LOGGER = Logger.getLogger("testserver");
//        BASE_LOGGER.setUseParentHandlers(false);
//        BASE_LOGGER.addHandler(handler);
//        BASE_LOGGER.setLevel(Level.ALL);
//    }
//
//    @BeforeEach
//    protected void setUp() throws Exception {
//        plugins = new HashMap<>();
//        fakeItemFactory = new ItemFactoryFake();
//        fakePluginManager = mock(PluginManager.class);
//        when(fakePluginManager.getPlugins()).thenAnswer(
//                call -> plugins.values().toArray(new Plugin[0]));
//        when(fakePluginManager.getPlugin(anyString())).thenAnswer(
//                call -> plugins.get(call.getArgument(0)));
//
//        fakeBukkitScheduler = new BukkitSchedulerFake(Runnable::run);
//        doAnswer(call -> null).when(fakePluginManager).registerEvents(any(), any());
//
//        fakeServer = mock(Server.class);
////        fakeServerSpigot = mock(Server.Spigot.class);
////        when(fakeServer.spigot()).thenReturn(fakeServerSpigot);
//        when(fakeServer.getLogger()).thenReturn(BASE_LOGGER);
//        when(fakeServer.getName()).thenReturn("SpigotTestBaseServer");
//        when(fakeServer.getVersion()).thenReturn("TestV");
//        when(fakeServer.getBukkitVersion()).thenReturn("BukkitV");
//        when(fakeServer.getPluginManager()).thenReturn(fakePluginManager);
//        when(fakeServer.getScheduler()).thenReturn(fakeBukkitScheduler);
//
//        final UnsafeValuesFake unsafeValuesFake = new UnsafeValuesFake();
//        when(fakeServer.getUnsafe()).thenReturn(unsafeValuesFake);
//
//        when(fakeServer.getItemFactory()).thenReturn(fakeItemFactory);
//        // PAPER does stupid shit now days with some net.kyori Service crap in Bukkit.setServer() - avoid that hell by bypassing the method.
//        try {
//            ReflectionUtil.getField(Bukkit.class, "server").set(null, fakeServer);
//        } catch (IllegalAccessException e) {
//            throw new RuntimeException(e);
//        }
//    }
//
//    private static Optional<Thread> getThread(long threadId) {
//        return Thread.getAllStackTraces().keySet().stream()
//                .filter(t -> t.getId() == threadId)
//                .findFirst();
//    }
//
//    protected Plugin mockPlugin(@NotNull String pluginName, @Nullable FileConfiguration pluginConfig) {
//        return mockPlugin(pluginName, pluginConfig, Collections.emptyList(), Collections.emptyList());
//    }
//    protected Plugin mockPlugin(
//            @NotNull String pluginName,
//            @Nullable FileConfiguration pluginConfig,
//            List<String> dependencies
//    ) {
//        return mockPlugin(pluginName, pluginConfig, dependencies, Collections.emptyList());
//    }
//    protected Plugin mockPlugin(
//            @NotNull String pluginName,
//            @Nullable FileConfiguration pluginConfig,
//            List<String> dependencies,
//            List<String> softDependencies
//    ) {
//        Plugin plugin = mock(Plugin.class);
//        when(plugin.getName()).thenReturn(pluginName);
//        when(plugin.getLogger()).thenReturn(BASE_LOGGER);
//        when(plugin.getServer()).thenReturn(fakeServer);
//        when(plugin.isEnabled()).thenReturn(true);
//        if (pluginConfig == null) pluginConfig = new YamlConfiguration();
//        when(plugin.getConfig()).thenReturn(pluginConfig);
//        final var lem = mock(LifecycleEventManager.class);
////        when(lem.registerEventHandler(any(), any())).then(call -> {
////            ((LifecycleEventHandler) call.getArgument(2));
////        });
//        when(plugin.getLifecycleManager()).thenReturn(lem);
//
//        final PluginMeta meta = mock(PluginMeta.class);
//        when(meta.getPluginDependencies()).thenReturn(dependencies);
//        when(meta.getPluginSoftDependencies()).thenReturn(softDependencies);
//        when(plugin.getPluginMeta()).thenReturn(meta);
//
//        plugins.put(pluginName, plugin);
//        return plugin;
//    }
//}
