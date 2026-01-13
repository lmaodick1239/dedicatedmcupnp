package pigcart.dedicatedmcupnp;

import com.dosse.upnp.UPnP;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.*;
import java.util.Enumeration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class DedicatedMcUpnp {
    public static final String MOD_ID = "dedicatedmcupnp";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    
    private static ScheduledExecutorService refreshScheduler = null;

    @SuppressWarnings("unchecked")
    public static <S> LiteralArgumentBuilder<S> getCommands() {
        return (LiteralArgumentBuilder<S>) Commands.literal("upnp")
                .requires((commandSourceStack) -> commandSourceStack.hasPermission(4)) //"owner" permission level
                .executes(ctx -> {
                    if (!UPnP.isUPnPAvailable()) {
                        StonecutterUtil.sendMsg(ctx, "UPnP is not available on this network.");
                    } else {
                        StonecutterUtil.sendMsg(ctx, "IPv4 Address: " + UPnP.getExternalIPv4());
                        try {
                            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
                            while (networkInterfaces.hasMoreElements()) {
                                NetworkInterface netInterface = networkInterfaces.nextElement();
                                if (netInterface.isUp() && !netInterface.isLoopback() && !netInterface.isVirtual() && !netInterface.getName().startsWith("tunnel")) {
                                    Enumeration<InetAddress> addresses = netInterface.getInetAddresses();
                                    while (addresses.hasMoreElements()) {
                                        InetAddress address = addresses.nextElement();
                                        if (address instanceof Inet6Address
                                                && !address.isAnyLocalAddress()
                                                && !address.isLinkLocalAddress()
                                                && !address.isLoopbackAddress()
                                        ) {
                                            // seems to be no way to distinguish between temporary and private addresses here
                                            StonecutterUtil.sendMsg(ctx, "IPv6 address: " + address);
                                        }
                                    }
                                }
                            }
                        } catch (Exception ignored) {}
                        StonecutterUtil.sendMsg(ctx, "The following ports are mapped: ");
                        int portsMapped = 0;
                        for (int port : Config.tcpPorts) {
                            if (UPnP.isMappedTCP(port)) {
                                StonecutterUtil.sendMsg(ctx, "TCP " + port);
                                portsMapped++;
                            }
                        }
                        for (int port : Config.udpPorts) {
                            if (UPnP.isMappedUDP(port)) {
                                StonecutterUtil.sendMsg(ctx, "UDP " + port);
                                portsMapped++;
                            }
                        }
                        if (portsMapped == 0) {
                            StonecutterUtil.sendMsg(ctx, "No ports are mapped.");
                        }
                    }
                    return 0;
                });
    }
    
    private static void refreshUPnPPorts() {
        LOGGER.info("Refreshing UPnP port mappings...");
        if (UPnP.isUPnPAvailable()) {
            int successCount = 0;
            int totalPorts = Config.tcpPorts.size() + Config.udpPorts.size();
            
            for (int port : Config.tcpPorts) {
                if (UPnP.openPortTCP(port, "Minecraft Server (dedicatedmcupnp)")) {
                    successCount++;
                } else {
                    LOGGER.warn("Failed to refresh TCP port {}", port);
                }
            }
            for (int port : Config.udpPorts) {
                if (UPnP.openPortUDP(port, "Minecraft Server (dedicatedmcupnp)")) {
                    successCount++;
                } else {
                    LOGGER.warn("Failed to refresh UDP port {}", port);
                }
            }
            
            LOGGER.info("UPnP refresh complete: {}/{} ports refreshed successfully", successCount, totalPorts);
        } else {
            LOGGER.error("UPnP is not available. Refresh failed.");
        }
    }

    public static void onServerStarted(MinecraftServer server) {
        if (server.isDedicatedServer()) {
            Config.readConfig();
            LOGGER.info("Attempting UPnP port forwarding...");
            if (UPnP.isUPnPAvailable()) {
                for (int port : Config.tcpPorts) {
                    if (UPnP.isMappedTCP(port)) {
                        LOGGER.warn("TCP port {} is already mapped. (Either another service is using it or your server did not shut down correctly!)", port);
                    } else if (UPnP.openPortTCP(port, "Minecraft Server (dedicatedmcupnp)")) {
                        LOGGER.info("UPnP opened TCP port {}", port);
                    } else {
                        LOGGER.error("UPnP failed to open TCP port {}", port);
                    }
                }
                for (int port : Config.udpPorts) {
                    if (UPnP.isMappedUDP(port)) {
                        LOGGER.warn("UDP port {} is already mapped. (Either another service is using it or your server did not shut down correctly!)", port);
                    } else if (UPnP.openPortUDP(port, "Minecraft Server (dedicatedmcupnp)")) {
                        LOGGER.info("UPnP opened UDP port {}", port);
                    } else {
                        LOGGER.error("UPnP failed to open UDP port {}", port);
                    }
                }

                // Schedule periodic refresh if configured
                if (Config.refreshIntervalMinutes > 0) {
                    LOGGER.info("Scheduling UPnP refresh every {} minutes", Config.refreshIntervalMinutes);
                    refreshScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "UPnP-Refresh");
                        t.setDaemon(true);
                        return t;
                    });
                    refreshScheduler.scheduleAtFixedRate(
                        DedicatedMcUpnp::refreshUPnPPorts,
                        Config.refreshIntervalMinutes,
                        Config.refreshIntervalMinutes,
                        TimeUnit.MINUTES
                    );
                } else {
                    LOGGER.info("Periodic UPnP refresh is disabled (refresh interval is 0)");
                }

            } else {
                LOGGER.error("UPnP is not available on this network. If you are an admin please check the settings on your router/hub");
            }
        }
    }

    public static void onServerStopping(MinecraftServer server) {
        if (server.isDedicatedServer()) {
            // Stop the refresh scheduler
            if (refreshScheduler != null && !refreshScheduler.isShutdown()) {
                LOGGER.info("Stopping UPnP refresh scheduler...");
                refreshScheduler.shutdown();
                try {
                    if (!refreshScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                        refreshScheduler.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    LOGGER.warn("UPnP refresh scheduler shutdown was interrupted", e);
                    refreshScheduler.shutdownNow();
                    Thread.currentThread().interrupt();
                }
                refreshScheduler = null;
            }
            
            LOGGER.info("Closing UPnP ports...");
            for (int port : Config.tcpPorts) {
                if (UPnP.closePortTCP(port)) {
                    LOGGER.info("Closed TCP port {}", port);
                }
            }
            for (int port : Config.udpPorts) {
                if (UPnP.closePortUDP(port)) {
                    LOGGER.info("Closed UDP port {}", port);
                }
            }
        }
    }
}
