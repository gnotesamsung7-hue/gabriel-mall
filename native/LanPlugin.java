package com.markdave.gabrielmall;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Local Wi-Fi / hotspot co-op: the host phone runs a tiny WebSocket relay that forwards every message to the other players. */
@CapacitorPlugin(name = "Lan")
public class LanPlugin extends Plugin {
    private Relay relay;

    @PluginMethod
    public void start(PluginCall call) {
        int port = call.getInt("port", 8777);
        try {
            stopRelay();
            relay = new Relay(new InetSocketAddress(port));
            relay.setReuseAddr(true);
            relay.start();
            JSObject ret = new JSObject();
            ret.put("ips", ipArray());
            ret.put("port", port);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Could not start: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        stopRelay();
        call.resolve();
    }

    @PluginMethod
    public void ips(PluginCall call) {
        JSObject r = new JSObject();
        r.put("ips", ipArray());
        call.resolve(r);
    }

    @Override
    protected void handleOnDestroy() {
        stopRelay();
    }

    private void stopRelay() {
        if (relay != null) {
            try { relay.stop(300); } catch (Exception ignored) { }
            relay = null;
        }
    }

    private static JSArray ipArray() {
        List<String> first = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    String ip = a.getHostAddress();
                    if (ip.startsWith("192.168.") || ip.startsWith("172.")) first.add(ip); else rest.add(ip);
                }
            }
        } catch (Exception ignored) { }
        JSArray arr = new JSArray();
        for (String s : first) arr.put(s);
        for (String s : rest) arr.put(s);
        return arr;
    }

    static class Relay extends WebSocketServer {
        private final Map<WebSocket, String> peers = new ConcurrentHashMap<>();

        Relay(InetSocketAddress addr) { super(addr); }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) { }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            String p = peers.remove(conn);
            if (p == null) return;
            String msg = "{\"t\":\"left\",\"peer\":" + JSONObject.quote(p) + "}";
            for (WebSocket o : getConnections()) if (o != conn && o.isOpen()) o.send(msg);
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            if (!peers.containsKey(conn) && message.startsWith("{\"t\":\"hello\"")) {
                try { peers.put(conn, new JSONObject(message).optString("peer", "")); } catch (Exception ignored) { }
            }
            for (WebSocket o : getConnections()) if (o != conn && o.isOpen()) o.send(message);
        }

        @Override
        public void onError(WebSocket conn, Exception ex) { }

        @Override
        public void onStart() { setConnectionLostTimeout(30); }
    }
}
