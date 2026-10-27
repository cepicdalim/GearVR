package local.gearvr.stream;

import android.content.Context;
import android.Manifest;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.widget.Toast;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;

import org.gearvrf.GVRActivity;
import org.gearvrf.GVRAndroidResource;
import org.gearvrf.GVRContext;
import org.gearvrf.GVRMain;
import org.gearvrf.GVRMaterial;
import org.gearvrf.GVRMeshCollider;
import org.gearvrf.GVRPicker;
import org.gearvrf.GVRScene;
import org.gearvrf.GVRSceneObject;
import org.gearvrf.GVRTextureParameters;
import org.gearvrf.GVRCursorController;
import org.gearvrf.io.CursorControllerListener;
import org.gearvrf.io.GVRControllerType;
import org.gearvrf.scene_objects.GVRLineSceneObject;
import org.gearvrf.scene_objects.GVRSphereSceneObject;
import org.gearvrf.scene_objects.GVRVideoSceneObject;
import org.gearvrf.scene_objects.GVRVideoSceneObjectPlayer;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.json.JSONArray;

public final class MainActivity extends GVRActivity {
    private static final int CAMERA_PERMISSION_REQUEST = 41;
    private static final int MAX_PACKET_SIZE = 16 * 1024 * 1024;
    private static final byte[] START_CODE = {0, 0, 0, 1};
    private static final String[] BACKGROUND_PATHS = {
        "backgrounds/rooms/cinema_hall.jpg",
        "backgrounds/rooms/games_room.jpg",
        "backgrounds/rooms/cinema_lobby.jpg",
        "backgrounds/rooms/comfy_cafe.jpg",
        "backgrounds/rooms/old_outdoor_theater.jpg",
        "backgrounds/nature/misty_pines.jpg",
        "backgrounds/nature/snowy_forest.jpg",
        "backgrounds/nature/autumn_field.jpg",
        "backgrounds/nature/kiara_1_dawn.jpg",
        "backgrounds/nature/the_sky_is_on_fire.jpg",
        "backgrounds/nature/venice_sunset.jpg",
        "backgrounds/nature/ettling_isar.jpg",
        "backgrounds/nature/ampleben_night_sky.jpg"
    };
    private static final String[] BACKGROUND_NAMES = {
        "Cinema hall", "Game room", "Cinema lobby", "Cafe", "Outdoor theater",
        "Misty pines", "Snowy forest", "Autumn field", "Mountain dawn",
        "Sunset coast", "Venice sunset", "Isar river", "Starry night"
    };
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CameraSurfacePlayer cameraPlayer = new CameraSurfacePlayer();
    private volatile boolean activityResumed;
    private volatile boolean cameraRequested;
    private NsdManager nsdManager;
    private WifiManager.MulticastLock multicastLock;
    private NsdManager.DiscoveryListener discoveryListener;
    private boolean fallbackAttempted;
    private volatile boolean fallbackFound;
    private Thread fallbackThread;
    private volatile Surface decoderSurface;
    private volatile Socket streamSocket;
    private volatile InputChannel inputChannel;
    private volatile float[] angularVelocity;
    private volatile long gyroTimestampNs;
    private final android.hardware.SensorEventListener gyroListener = new android.hardware.SensorEventListener() {
        @Override
        public void onSensorChanged(android.hardware.SensorEvent event) {
            angularVelocity = event.values.clone();
            gyroTimestampNs = event.timestamp;
        }

        @Override
        public void onAccuracyChanged(android.hardware.Sensor sensor, int accuracy) { }
    };
    private volatile boolean receiverRunning;
    private volatile String activeSenderEndpoint;
    private Thread receiverThread;
    private MediaCodec decoder;
    private HandlerThread decoderThread;
    private Handler decoderHandler;
    private final Runnable drainDecoder = new Runnable() {
        @Override
        public void run() {
            synchronized (MainActivity.this) {
                if (!decoderConfigured || decoder == null || decoderHandler == null) return;
                try {
                    drainDecoderOutput(decoder);
                    decoderHandler.postDelayed(this, 2);
                } catch (IllegalStateException error) {
                    Log.e("GearVRStream", "H.264 output drain failed", error);
                    releaseDecoder();
                    showReceiverError("H.264 output failed: " + error.getMessage());
                }
            }
        }
    };
    private volatile boolean decoderConfigured;
    private int videoWidth;
    private int videoHeight;
    private volatile byte[] videoSps;
    private volatile byte[] videoPps;
    private volatile String discoveredSender = "Searching local network for a Mac sender...";
    private volatile StreamVrMain vrMain;
    private org.gearvrf.utility.DockEventReceiver screenDockReceiver;
    private android.hardware.SensorManager wearSensorManager;
    private android.hardware.Sensor wearSensor;
    private boolean wearSensorNear;
    private final android.hardware.SensorEventListener wearListener = new android.hardware.SensorEventListener() {
        @Override
        public void onSensorChanged(android.hardware.SensorEvent event) {
            boolean near = event.values[0] < event.sensor.getMaximumRange();
            if (near && !wearSensorNear && vrMain != null) vrMain.requestScreenCenter();
            wearSensorNear = near;
        }

        @Override
        public void onAccuracyChanged(android.hardware.Sensor sensor, int accuracy) { }
    };

    @Override
    protected void onInitAppSettings(org.gearvrf.utility.VrAppSettings settings) {
        settings.getEyeBufferParams().setResolutionWidth(1536);
        settings.getEyeBufferParams().setResolutionHeight(1536);
        settings.getEyeBufferParams().setMultiSamples(4);
        super.onInitAppSettings(settings);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        multicastLock = wifiManager.createMulticastLock("gearvr-stream-discovery");
        multicastLock.setReferenceCounted(false);
        vrMain = new StreamVrMain();
        screenDockReceiver = new org.gearvrf.utility.DockEventReceiver(this,
                () -> vrMain.requestScreenCenter(), () -> {
                    if (vrMain.getGVRContext() != null) {
                        vrMain.getGVRContext().runOnGlThread(vrMain::resetInteraction);
                    }
                });
        screenDockReceiver.start();
        wearSensorManager = (android.hardware.SensorManager) getSystemService(Context.SENSOR_SERVICE);
        wearSensor = wearSensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_PROXIMITY);
        setMain(vrMain);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (vrMain != null) vrMain.requestScreenCenter();
        wearSensorNear = false;
        if (wearSensor != null) wearSensorManager.registerListener(wearListener, wearSensor,
            android.hardware.SensorManager.SENSOR_DELAY_NORMAL);
        android.hardware.Sensor gyro = wearSensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE);
        if (gyro != null) wearSensorManager.registerListener(gyroListener, gyro, 16667);
        activityResumed = true;
        cameraPlayer.start();
        startDiscovery();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && vrMain != null) vrMain.requestScreenCenter();
    }

    @Override
    protected void onPause() {
        if (wearSensorManager != null) wearSensorManager.unregisterListener(wearListener);
        if (wearSensorManager != null) wearSensorManager.unregisterListener(gyroListener);
        angularVelocity = null;
        activityResumed = false;
        cameraPlayer.pause();
        stopDiscovery();
        stopReceiver();
        if (vrMain != null && vrMain.getGVRContext() != null) {
            vrMain.getGVRContext().runOnGlThread(vrMain::resetInteraction);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        activityResumed = false;
        if (screenDockReceiver != null) screenDockReceiver.stop();
        cameraPlayer.release();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != CAMERA_PERMISSION_REQUEST) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            if (vrMain != null && vrMain.getGVRContext() != null) {
                vrMain.getGVRContext().runOnGlThread(() ->
                        vrMain.applyPaletteSelection(BACKGROUND_PATHS.length));
            }
        } else {
            Toast.makeText(this, "Camera permission is needed for the Camera scene",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void startDiscovery() {
        if (discoveryListener != null) {
            return;
        }
        fallbackAttempted = false;
        if (multicastLock != null && !multicastLock.isHeld()) multicastLock.acquire();
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String serviceType) {
                runOnUiThread(() -> {
                    discoveredSender = "Searching local network for a Mac sender...";
                });
            }

            @Override
            public void onServiceFound(NsdServiceInfo service) {
                if (!"_gearvrsender._tcp.".equals(service.getServiceType())) {
                    return;
                }
                runOnUiThread(() -> {
                    discoveredSender = "Found sender: " + service.getServiceName() + ", resolving...";
                });
                nsdManager.resolveService(service, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo ignored, int errorCode) {
                        tryFallbackDiscovery();
                        runOnUiThread(() -> {
                            discoveredSender = "Sender found, address lookup failed (" + errorCode + ")";
                        });
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo resolved) {
                        Log.i("GearVRStream", "Sender resolved host="
                                + resolved.getHost().getHostAddress() + " port=" + resolved.getPort());
                        connectToSender(resolved);
                        runOnUiThread(() -> {
                            discoveredSender = "Found " + resolved.getServiceName() + " at "
                                    + resolved.getHost().getHostAddress() + ":" + resolved.getPort();
                        });
                    }
                });
            }

            @Override
            public void onServiceLost(NsdServiceInfo service) {
                runOnUiThread(() -> {
                    discoveredSender = "Searching local network for a Mac sender...";
                });
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                discoveryListener = null;
                releaseMulticastLock();
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                discoveryListener = null;
                releaseMulticastLock();
                runOnUiThread(() -> {
                    discoveredSender = "Network discovery unavailable (" + errorCode + ")";
                });
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                nsdManager.stopServiceDiscovery(this);
            }
        };
        nsdManager.discoverServices("_gearvrsender._tcp.", NsdManager.PROTOCOL_DNS_SD,
                discoveryListener);
        mainHandler.postDelayed(this::tryFallbackDiscovery, 5000);
    }

    private void stopDiscovery() {
        if (discoveryListener != null) {
            nsdManager.stopServiceDiscovery(discoveryListener);
            discoveryListener = null;
        }
        releaseMulticastLock();
    }

    private void releaseMulticastLock() {
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
    }

    private void tryFallbackDiscovery() {
        if (!receiverRunning && !fallbackAttempted && discoveryListener != null
                && multicastLock != null && multicastLock.isHeld()) {
            fallbackAttempted = true;
            connectToLocalNetworkSender();
        }
    }

    private void connectToLocalNetworkSender() {
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        if (wifiInfo == null || wifiInfo.getIpAddress() == 0) return;
        int address = wifiInfo.getIpAddress();
        byte[] bytes = new byte[] {(byte) address, (byte) (address >> 8),
                (byte) (address >> 16), (byte) (address >> 24)};
        int netmask = wifiManager.getDhcpInfo().netmask;
        byte[] subnet = new byte[4];
        for (int index = 0; index < 4; index++) {
            subnet[index] = (byte) (bytes[index] & (netmask >> (index * 8)));
        }
        long hostCount = Integer.toUnsignedLong(~netmask);
        if (hostCount < 2 || hostCount > 1024) return;
        fallbackFound = false;
        fallbackThread = new Thread(() -> {
                for (int host = 1; host < hostCount - 1 && !fallbackFound
                    && !Thread.currentThread().isInterrupted(); host++) {
                byte[] candidateBytes = new byte[4];
                for (int index = 0; index < 4; index++) {
                    candidateBytes[index] = (byte) (subnet[index] | ((host >> (index * 8)) & 0xff));
                }
                if (Arrays.equals(bytes, candidateBytes)) continue;
                try (Socket socket = new Socket()) {
                    InetAddress candidate = InetAddress.getByAddress(candidateBytes);
                    if (candidate.isAnyLocalAddress() || candidate.isLoopbackAddress()
                            || candidate.isMulticastAddress()) continue;
                    socket.connect(new InetSocketAddress(candidate, 43129), 120);
                    fallbackFound = true;
                    runOnUiThread(() -> {
                        NsdServiceInfo service = new NsdServiceInfo();
                        service.setServiceName("GearVR Stream");
                        service.setServiceType("_gearvrsender._tcp.");
                        service.setHost(candidate);
                        service.setPort(43129);
                        connectToSender(service);
                        discoveredSender = "Found sender at " + candidate.getHostAddress();
                    });
                } catch (IOException ignored) {
                }
            }
            if (!fallbackFound) {
                runOnUiThread(() -> {
                    if (!receiverRunning) {
                        discoveredSender = "No sender found on this Wi-Fi subnet";
                    }
                });
            }
        }, "gearvr-sender-subnet-scan");
        fallbackThread.start();
    }

    private synchronized void connectToSender(NsdServiceInfo service) {
        String endpoint = service.getHost().getHostAddress() + ":" + service.getPort();
        if (receiverRunning && endpoint.equals(activeSenderEndpoint)) {
            Log.i("GearVRStream", "Ignoring duplicate sender resolution for " + endpoint);
            return;
        }
        stopReceiver();
        activeSenderEndpoint = endpoint;
        receiverRunning = true;
        receiverThread = new Thread(() -> receiveStream(service), "gearvr-video-receiver");
        receiverThread.start();
    }

    private void stopReceiver() {
        receiverRunning = false;
        activeSenderEndpoint = null;
        fallbackFound = true;
        if (fallbackThread != null) {
            fallbackThread.interrupt();
            fallbackThread = null;
        }
        Socket socket = streamSocket;
        streamSocket = null;
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) { }
        }
        if (receiverThread != null) {
            receiverThread.interrupt();
            receiverThread = null;
        }
        synchronized (this) {
            releaseDecoder();
        }
    }

    private void receiveStream(NsdServiceInfo service) {
        String host = service.getHost().getHostAddress();
        Socket socket = new Socket();
        InputChannel channel = null;
        try {
            streamSocket = socket;
            bindSocketToWifi(socket);
            socket.setTcpNoDelay(true);
            Log.i("GearVRStream", "Connecting to sender " + host + ":" + service.getPort());
            socket.connect(new InetSocketAddress(service.getHost(), service.getPort()), 5000);
            channel = new InputChannel(socket);
            channel.offer(new JSONObject().put("type", "restart"), false, false);
            inputChannel = channel;
            channel.start();
            Log.i("GearVRStream", "TCP connected to sender " + host + ":" + service.getPort());
            DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            runOnUiThread(() -> {
                discoveredSender = "Connected to " + host + ":" + service.getPort() + ", waiting for video";
            });
            while (receiverRunning && !Thread.currentThread().isInterrupted()) {
                int packetLength = input.readInt();
                if (packetLength < 1 || packetLength > MAX_PACKET_SIZE) {
                    throw new IOException("Invalid video packet size: " + packetLength);
                }
                byte[] packet = new byte[packetLength];
                input.readFully(packet);
                if (packetLength >= 4 && packet[0] == 'G' && packet[1] == 'V'
                        && packet[2] == 'R' && packet[3] == '1') {
                    configureDecoder(packet);
                } else {
                    decodeFrame(packet);
                }
            }
        } catch (EOFException error) {
            if (receiverRunning) {
                Log.w("GearVRStream", "Sender closed the video connection", error);
                showReceiverError("Sender disconnected");
            }
        } catch (Exception error) {
            if (receiverRunning) {
                Log.e("GearVRStream", "Video connection failed to " + host + ":" + service.getPort(), error);
                showReceiverError("Video connection failed: " + error.getMessage());
            }
        } finally {
            try { socket.close(); } catch (IOException ignored) { }
            if (channel != null) {
                channel.writer.interrupt();
                if (inputChannel == channel) inputChannel = null;
            }
            if (streamSocket == socket) streamSocket = null;
        }
    }

    private final class InputChannel {
        private final Socket socket;
        private final ArrayBlockingQueue<byte[]> buttons = new ArrayBlockingQueue<>(32);
        private final AtomicReference<byte[]> latestPose = new AtomicReference<>();
        private final AtomicReference<byte[]> latestMove = new AtomicReference<>();
        private final AtomicReference<byte[]> latestScroll = new AtomicReference<>();
        private final Thread writer;

        InputChannel(Socket socket) {
            this.socket = socket;
            writer = new Thread(this::writeInput, "gearvr-input-sender");
        }

        void start() { writer.start(); }

        void offer(JSONObject event, boolean pose, boolean move) {
            byte[] data = event.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (pose) latestPose.set(data);
            else if ("scroll".equals(event.optString("type"))) latestScroll.set(data);
            else if (move) latestMove.set(data);
            else {
                latestMove.set(null);
                if (!buttons.offer(data)) {
                    try { socket.close(); } catch (IOException ignored) { }
                }
            }
        }

        private void writeInput() {
            try {
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                while (streamSocket == socket && !socket.isClosed() && !Thread.currentThread().isInterrupted()) {
                    byte[] button = buttons.poll(8, TimeUnit.MILLISECONDS);
                    if (button != null) writePacket(output, button);
                    if (buttons.isEmpty()) {
                        byte[] move = latestMove.getAndSet(null);
                        if (move != null) writePacket(output, move);
                    }
                    byte[] pose = latestPose.getAndSet(null);
                    if (pose != null) writePacket(output, pose);
                    byte[] scroll = latestScroll.getAndSet(null);
                    if (scroll != null) writePacket(output, scroll);
                }
            } catch (IOException | InterruptedException error) {
                try { socket.close(); } catch (IOException ignored) { }
            }
        }

        private void writePacket(DataOutputStream output, byte[] data) throws IOException {
            output.writeInt(data.length);
            output.write(data);
        }
    }

    private void bindSocketToWifi(Socket socket) throws IOException {
        ConnectivityManager connectivityManager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        for (Network network : connectivityManager.getAllNetworks()) {
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
            if (capabilities != null
                    && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                network.bindSocket(socket);
                return;
            }
        }
    }

    private synchronized void configureDecoder(byte[] packet) throws IOException {
        ByteBuffer data = ByteBuffer.wrap(packet).order(java.nio.ByteOrder.BIG_ENDIAN);
        data.position(4);
        int width = data.getShort() & 0xffff;
        int height = data.getShort() & 0xffff;
        byte[] sps = readParameterSet(data);
        byte[] pps = readParameterSet(data);
        if (width <= 0 || height <= 0) throw new IOException("Invalid video dimensions");
        videoWidth = width;
        videoHeight = height;
        videoSps = sps;
        videoPps = pps;
        if (vrMain != null && vrMain.getGVRContext() != null) {
            vrMain.getGVRContext().runOnGlThread(() -> vrMain.updateScreenAspect(width, height));
        }
        if (decoderSurface != null) configureDecoder(width, height, sps, pps);
    }

    private byte[] readParameterSet(ByteBuffer data) throws IOException {
        if (data.remaining() < 2) throw new IOException("Incomplete H.264 configuration");
        int size = data.getShort() & 0xffff;
        if (size == 0 || size > data.remaining()) throw new IOException("Invalid H.264 parameter set");
        byte[] result = new byte[size];
        data.get(result);
        return result;
    }

    private synchronized void configureDecoder(int width, int height, byte[] sps, byte[] pps) {
        if (decoderSurface == null || isFinishing()) return;
        releaseDecoder();
        try {
            decoder = MediaCodec.createDecoderByType("video/avc");
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(withStartCode(sps)));
            format.setByteBuffer("csd-1", ByteBuffer.wrap(withStartCode(pps)));
            decoder.configure(format, decoderSurface, null, 0);
            decoder.start();
            decoderConfigured = true;
                decoderThread = new HandlerThread("gearvr-video-output",
                    android.os.Process.THREAD_PRIORITY_DISPLAY);
                decoderThread.start();
                decoderHandler = new Handler(decoderThread.getLooper());
                decoderHandler.post(drainDecoder);
            runOnUiThread(() -> {
                discoveredSender = "Receiving " + width + " x " + height + " H.264";
            });
        } catch (Exception error) {
            releaseDecoder();
            Log.e("GearVRStream", "H.264 decoder configuration failed", error);
            showReceiverError("H.264 decoder failed: " + error.getMessage());
        }
    }

    private synchronized void decodeFrame(byte[] packet) throws IOException {
        if (packet.length < 9 || decoder == null || !decoderConfigured) return;
        ByteBuffer data = ByteBuffer.wrap(packet).order(java.nio.ByteOrder.BIG_ENDIAN);
        long presentationTimeUs = data.getLong();
        boolean keyFrame = data.get() != 0;
        byte[] accessUnit = new byte[data.remaining()];
        data.get(accessUnit);
        byte[] annexB = toAnnexB(accessUnit);
        MediaCodec activeDecoder = decoder;
        if (activeDecoder == null) return;
        drainDecoderOutput(activeDecoder);
        int inputIndex = activeDecoder.dequeueInputBuffer(0);
        while (inputIndex < 0 && receiverRunning && !Thread.currentThread().isInterrupted()) {
            drainDecoderOutput(activeDecoder);
            inputIndex = activeDecoder.dequeueInputBuffer(2000);
        }
        if (inputIndex < 0) return;
        ByteBuffer input = activeDecoder.getInputBuffer(inputIndex);
        if (input == null) throw new IOException("H.264 decoder input buffer unavailable");
        input.clear();
        if (input.remaining() < annexB.length) {
            throw new IOException("H.264 frame exceeds decoder input capacity");
        }
        input.put(annexB);
        activeDecoder.queueInputBuffer(inputIndex, 0, annexB.length,
                Math.max(0, presentationTimeUs), keyFrame ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
        drainDecoderOutput(activeDecoder);
    }

    private void drainDecoderOutput(MediaCodec activeDecoder) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int newestOutput = -1;
        while (true) {
            int outputIndex = activeDecoder.dequeueOutputBuffer(info, 0);
            if (outputIndex >= 0) {
                if (newestOutput >= 0) activeDecoder.releaseOutputBuffer(newestOutput, false);
                newestOutput = outputIndex;
            } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            }
        }
        if (newestOutput >= 0) activeDecoder.releaseOutputBuffer(newestOutput, true);
    }

    private byte[] toAnnexB(byte[] accessUnit) throws IOException {
        ByteBuffer data = ByteBuffer.wrap(accessUnit).order(java.nio.ByteOrder.BIG_ENDIAN);
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(accessUnit.length + 32);
        while (data.hasRemaining()) {
            if (data.remaining() < 4) throw new IOException("Malformed H.264 frame");
            long nalLength = data.getInt() & 0xffffffffL;
            if (nalLength == 0 || nalLength > data.remaining()) throw new IOException("Malformed H.264 NAL unit");
            output.write(START_CODE);
            byte[] nal = new byte[(int) nalLength];
            data.get(nal);
            output.write(nal);
        }
        return output.toByteArray();
    }

    private byte[] withStartCode(byte[] nal) {
        byte[] result = Arrays.copyOf(START_CODE, START_CODE.length + nal.length);
        System.arraycopy(nal, 0, result, START_CODE.length, nal.length);
        return result;
    }

    private synchronized void releaseDecoder() {
        decoderConfigured = false;
        if (decoderHandler != null) decoderHandler.removeCallbacksAndMessages(null);
        decoderHandler = null;
        if (decoderThread != null) decoderThread.quitSafely();
        decoderThread = null;
        MediaCodec activeDecoder = decoder;
        decoder = null;
        if (activeDecoder != null) {
            try { activeDecoder.stop(); } catch (IllegalStateException ignored) { }
            try { activeDecoder.release(); } catch (IllegalStateException ignored) { }
        }
    }

    private void showReceiverError(String message) {
        runOnUiThread(() -> {
            discoveredSender = message;
        });
    }

    private final class StreamVrMain extends GVRMain {
        private GVRScene scene;
        private GVRSphereSceneObject backgroundObject;
        private volatile boolean screenCenterRequested = true;
        private int screenCenterFrames;
        private boolean draggingScreen;
        private final org.joml.Quaternionf screenGrabRotation = new org.joml.Quaternionf();
        private final org.joml.Vector3f screenDirection = new org.joml.Vector3f(0f, 0f, -1f);
        private GVRSceneObject paletteRoot;
        private GVRSceneObject[] paletteItems;
        private GVRMaterial[] paletteMaterials;
        private GVRSceneObject settingsRoot;
        private GVRSceneObject[] settingsItems;
        private GVRMaterial[] settingsMaterials;
        private GVRSceneObject debugPanel;
        private GVRSceneObject zoomSlider;
        private GVRSceneObject zoomThumb;
        private GVRSceneObject zoomFill;
        private GVRSceneObject zoomLabel;
        private final org.gearvrf.GVRTexture[] zoomLabelTextures = new org.gearvrf.GVRTexture[101];
        private boolean draggingZoom;
        private boolean pointerOverScreen;
        private float screenZoom = 1f;
        private int displayedZoomPercent = -1;
        private static final float ZOOM_TRACK_WIDTH = 2.2f;
        private final org.joml.Matrix4f zoomInverse = new org.joml.Matrix4f();
        private final org.joml.Vector3f zoomRayOrigin = new org.joml.Vector3f();
        private final org.joml.Vector3f zoomRayDirection = new org.joml.Vector3f();
        private boolean settingsVisible;
        private boolean debugVisible;
        private long lastDebugUpdateMs;
        private long pendingMenuClickMs;
        private static final long DOUBLE_CLICK_MS = 300;
        private GVRSceneObject hoveredPaletteItem;
        private GVRSceneObject laserHitDot;
        private int backgroundIndex = 12;
        private long lastBackgroundChangeMs;
        private boolean paletteVisible;
        private GVRCursorController activeController;
        private GVRSceneObject laserObject;
        private GVRSceneObject controllerPose;
        private GVRSceneObject videoObject;
        private GVRSceneObject cameraObject;
        private float screenOpacity = 0.75f;
        private GVRSceneObject pressedPaletteItem;
        private boolean touchpadPressed;
        private InputChannel touchpadClickChannel;
        private boolean touchTracking;
        private float previousTouchX;
        private float previousTouchY;
        private boolean triggerPressed;
        private long lastTriggerReleaseMs;
        private long triggerDownMs;
        private boolean pointerOverScreenHeader;
        private GVRSceneObject screenHeader;
        private boolean screenCenterLocked;
        private GVRSceneObject screenLockCheck;
        private final org.joml.Matrix4f lockedScreenHead = new org.joml.Matrix4f();
        private final org.joml.Vector3f lockedScreenPosition = new org.joml.Vector3f();
        private final org.joml.Quaternionf lockedScreenRotation = new org.joml.Quaternionf();
        private final org.joml.Vector3f smoothedHeadPosition = new org.joml.Vector3f();
        private final org.joml.Quaternionf smoothedHeadRotation = new org.joml.Quaternionf();
        private long lockedScreenUpdateNs;
        private InputChannel remotePointerChannel;
        private float screenPointerX;
        private float screenPointerY;
        private float remotePointerX;
        private float remotePointerY;
        private long lastInputTimestampNs;
        private final org.joml.Quaternionf inputHeadRotation = new org.joml.Quaternionf();
        private final org.joml.Quaternionf inputControllerRotation = new org.joml.Quaternionf();
        private long lastPointerFrameMs;
        private int pickingCheckFrames;
        private final ArrayList<GVRCursorController> controllers = new ArrayList<>();
        private final ConcurrentLinkedQueue<android.view.KeyEvent> buttonEvents =
                new ConcurrentLinkedQueue<>();
        private final org.joml.Matrix4f pointerMatrix = new org.joml.Matrix4f();
        private final org.joml.Vector3f rayOrigin = new org.joml.Vector3f();
        private final org.joml.Vector3f rayDirection = new org.joml.Vector3f();
        private final GVRCursorController.ControllerEventListener buttonListener =
                this::onControllerEvent;
        private final CursorControllerListener controllerListener = new CursorControllerListener() {
            @Override
            public void onCursorControllerAdded(GVRCursorController controller) {
                getGVRContext().runOnGlThread(() -> {
                    if (controllers.contains(controller)) return;
                    controllers.add(controller);
                    controller.addControllerEventListener(buttonListener);
                    chooseController();
                });
            }

            @Override
            public void onCursorControllerRemoved(GVRCursorController controller) {
                getGVRContext().runOnGlThread(() -> {
                    controller.removeControllerEventListener(buttonListener);
                    controllers.remove(controller);
                    if (activeController == controller) {
                        detachController();
                        chooseController();
                    }
                });
            }
        };

        @Override
        public void onInit(GVRContext context) {
            Log.i("GearVRStream", "Eye buffer settings: " + getAppSettings().getEyeBufferParams());
            scene = context.getMainScene();
            loadBackground(context, backgroundIndex);
            createBackgroundPalette(context);
            createSettingsMenu(context);
            GVRVideoSceneObject video = new GVRVideoSceneObject(context, 4.2f, 2.3625f,
                    new DecoderSurfacePlayer(), GVRVideoSceneObject.GVRVideoType.MONO);
                GVRTextureParameters videoParameters = new GVRTextureParameters(context);
                videoParameters.setMinFilterType(GVRTextureParameters.TextureFilterType.GL_LINEAR);
                videoParameters.setMagFilterType(GVRTextureParameters.TextureFilterType.GL_LINEAR);
                videoParameters.setWrapSType(GVRTextureParameters.TextureWrapType.GL_CLAMP_TO_EDGE);
                videoParameters.setWrapTType(GVRTextureParameters.TextureWrapType.GL_CLAMP_TO_EDGE);
                video.getRenderData().getMaterial().getMainTexture().updateTextureParameters(videoParameters);
            video.getTransform().setPosition(0, 0, -2.5f);
            video.attachCollider(new GVRMeshCollider(context, false));
            videoObject = video;
                addMenuSurface(video, 0.035f, 2.4325f, -2.1175f, 0f, 0.006f, 0xff3c474d);
                addMenuSurface(video, 0.035f, 2.4325f, 2.1175f, 0f, 0.006f, 0xff3c474d);
                addMenuSurface(video, 4.2f, 0.035f, 0f, -1.19875f, 0.006f, 0xff3c474d);
                screenHeader = addMenuSurface(video, 4.27f, 0.18f,
                    0f, 1.27125f, 0.006f, 0xff202a30);
                addMenuSurface(screenHeader, 0.32f, 0.018f, 0f, 0f, 0.008f, 0xffa8b6bd);
            if (videoWidth > 0 && videoHeight > 0) updateScreenAspect(videoWidth, videoHeight);
            scene.addSceneObject(video);
            createLaserHitDot(context);
            showMenu(false);

                Log.i("GearVRStream", "VR scene initialized: palette=" + paletteVisible
                    + " tiles=" + paletteItems.length + " videoSurface=" + (decoderSurface != null));

            context.getInputManager().addCursorControllerListener(controllerListener);
            for (GVRCursorController controller : context.getInputManager().getCursorControllers()) {
                controllerListener.onCursorControllerAdded(controller);
            }
        }

        @Override
        public void onStep() {
            if (screenCenterRequested) {
                lockedScreenUpdateNs = 0;
                endRemotePointer();
                screenCenterRequested = false;
                screenCenterFrames = 12;
                draggingScreen = false;
            }
            if (screenCenterFrames > 0 && --screenCenterFrames == 0) centerScreen();
            if (screenCenterLocked) updateLockedScreen();
            else lockedScreenUpdateNs = 0;
            if (++pickingCheckFrames == 120) {
                int hitCount = 0;
                for (GVRSceneObject item : paletteItems) {
                    float[] model = item.getTransform().getModelMatrix();
                    org.joml.Vector3f direction = new org.joml.Vector3f(
                            model[12], model[13], model[14]).normalize();
                    GVRPicker.GVRPickedObject hit = GVRPicker.pickSceneObject(item,
                        0f, 0f, 0f, direction.x, direction.y, direction.z);
                    if (hit != null && hit.hitObject == item && hit.hitDistance > 0f) hitCount++;
                }
                Log.i("GearVRStream", "Palette mesh hit check: " + hitCount + "/" + paletteItems.length);
                float[] sliderModel = zoomSlider.getTransform().getModelMatrix();
                org.joml.Vector3f sliderDirection = new org.joml.Vector3f(
                    sliderModel[12], sliderModel[13], sliderModel[14]).normalize();
                GVRPicker.GVRPickedObject sliderHit = GVRPicker.pickSceneObject(zoomSlider,
                    0f, 0f, 0f, sliderDirection.x, sliderDirection.y, sliderDirection.z);
                Log.i("GearVRStream", "Zoom slider hit check: "
                    + (sliderHit != null && sliderHit.hitObject == zoomSlider
                    && sliderHit.hitDistance > 0f));
            }
            updatePointer();
            android.view.KeyEvent event;
            while ((event = buttonEvents.poll()) != null) {
                handleButton(event);
                updatePointer();
            }
            long now = android.os.SystemClock.elapsedRealtime();
            if (pendingMenuClickMs != 0 && now - pendingMenuClickMs >= DOUBLE_CLICK_MS) {
                pendingMenuClickMs = 0;
                if (settingsVisible) closeMenus();
                else showMenu(false);
            }
            if (settingsVisible && debugVisible && now - lastDebugUpdateMs >= 1000) {
                lastDebugUpdateMs = now;
                updateDebugPanel();
            }
            animatePaletteHover();
            sendTrackingInput();
        }

        private JSONArray quaternionArray(org.joml.Quaternionf rotation) throws org.json.JSONException {
            return new JSONArray().put(rotation.x).put(rotation.y).put(rotation.z).put(rotation.w);
        }

        private void sendTrackingInput() {
            long now = android.os.SystemClock.elapsedRealtimeNanos();
            InputChannel channel = inputChannel;
            if (!activityResumed || channel == null || now - lastInputTimestampNs < 16666667L) return;
            lastInputTimestampNs = now;
            try {
                pointerMatrix.set(scene.getMainCameraRig().getHeadTransform().getModelMatrix());
                pointerMatrix.getUnnormalizedRotation(inputHeadRotation).normalize();
                JSONObject pose = new JSONObject().put("type", "pose").put("timestampNs", now)
                        .put("head", quaternionArray(inputHeadRotation));
                if (controllerPose != null) {
                    pointerMatrix.set(controllerPose.getTransform().getModelMatrix());
                    pointerMatrix.getUnnormalizedRotation(inputControllerRotation).normalize();
                    pose.put("controller", quaternionArray(inputControllerRotation));
                }
                float[] velocity = angularVelocity;
                if (velocity != null && now - gyroTimestampNs < 100000000L) {
                    pose.put("angularVelocity", new JSONArray().put(velocity[0]).put(velocity[1]).put(velocity[2]));
                }
                channel.offer(pose, true, false);
                if (touchpadClickChannel != null && touchpadClickChannel != channel) releaseTouchpadClick();
                if (remotePointerChannel != null && remotePointerChannel != channel) endRemotePointer();
                if (touchpadClickChannel == channel && !menuVisible()
                    && pointerOverScreen && !draggingScreen) {
                    sendPointer(channel, "move", screenPointerX, screenPointerY);
                }
            } catch (org.json.JSONException error) {
                Log.w("GearVRStream", "Invalid tracking input", error);
            }
        }

        private void sendPointer(InputChannel channel, String action, float positionX, float positionY) {
            if (channel == null) return;
            try {
                channel.offer(new JSONObject().put("type", "pointer").put("action", action)
                        .put("x", positionX).put("y", positionY), false, action.equals("move"));
                remotePointerX = positionX;
                remotePointerY = positionY;
            } catch (org.json.JSONException error) {
                Log.w("GearVRStream", "Invalid pointer input", error);
            }
        }

        private void endRemotePointer() {
            if (remotePointerChannel != null) {
                sendPointer(remotePointerChannel, "up", pointerOverScreen ? screenPointerX : remotePointerX,
                        pointerOverScreen ? screenPointerY : remotePointerY);
                remotePointerChannel = null;
            }
        }

        private void updatePointer() {
            if (laserObject == null || laserHitDot == null || controllerPose == null) return;
            pointerMatrix.set(controllerPose.getTransform().getModelMatrix());
            pointerMatrix.transformPosition(0f, 0f, 0f, rayOrigin);
            pointerMatrix.transformDirection(0f, 0f, -1f, rayDirection);
            if (rayDirection.lengthSquared() < 0.000001f) {
                draggingZoom = false;
                laserObject.setEnable(false);
                laserHitDot.setEnable(false);
                if (hoveredPaletteItem != null) clearPaletteHover(hoveredPaletteItem);
                return;
            }
            rayDirection.normalize();
            if (draggingZoom && settingsVisible) updateZoomDrag();
            if (draggingScreen) {
                screenGrabRotation.transform(rayDirection, screenDirection);
                placeScreen();
            }
                laserObject.setEnable(true);
                laserObject.getRenderData().getMaterial().setOpacity(
                    menuVisible() || draggingScreen ? 0.85f : 0.3f);
                laserHitDot.getRenderData().getMaterial().setOpacity(
                        menuVisible() || draggingScreen ? 0.85f : 0.3f);
            float distance = screenHitDistance();
            pointerOverScreen = distance < 12f && !pointerOverScreenHeader;
            if (screenHeader != null) {
                screenHeader.getRenderData().getMaterial().setColor(
                        !menuVisible() && pointerOverScreenHeader && !screenCenterLocked
                        ? (draggingScreen ? 0xff42685c : 0xff34484f) : 0xff202a30);
            }
            GVRSceneObject hitItem = null;
            if (menuVisible()) {
                hitItem = null;
                GVRSceneObject root = paletteVisible ? paletteRoot : settingsRoot;
                zoomInverse.set(root.getTransform().getModelMatrix()).invert();
                zoomInverse.transformPosition(rayOrigin, zoomRayOrigin);
                zoomInverse.transformDirection(rayDirection, zoomRayDirection);
                if (Math.abs(zoomRayDirection.z) >= 0.0001f) {
                    float menuDistance = (0.025f - zoomRayOrigin.z) / zoomRayDirection.z;
                    float localX = zoomRayOrigin.x + zoomRayDirection.x * menuDistance;
                    float localY = zoomRayOrigin.y + zoomRayDirection.y * menuDistance;
                    float halfWidth = paletteVisible ? 1.525f : 1.45f;
                    float halfHeight = paletteVisible ? 1.375f : 1.775f;
                    float centerY = paletteVisible ? 0f : -0.5f;
                    if (menuDistance > 0f && menuDistance <= 12f
                            && (draggingZoom || (Math.abs(localX) <= halfWidth
                            && Math.abs(localY - centerY) <= halfHeight))) {
                        distance = menuDistance;
                        pointerOverScreen = false;
                        for (GVRSceneObject item : paletteVisible ? paletteItems : settingsItems) {
                            int tag = (Integer) item.getTag();
                            float width = paletteVisible ? 0.66f
                                    : (tag == 109 ? 2.6f : (tag >= 104 && tag <= 108 ? 0.49f : 1.25f));
                            float height = paletteVisible ? 0.52f : (tag == 109 ? 0.28f : 0.31f);
                            if (Math.abs(localX - item.getTransform().getPositionX()) <= width / 2f
                                    && Math.abs(localY - item.getTransform().getPositionY()) <= height / 2f) {
                                hitItem = item;
                                break;
                            }
                        }
                    }
                }
            }
            if (draggingZoom) hitItem = zoomSlider;
            if (hoveredPaletteItem != null && hoveredPaletteItem != hitItem) {
                clearPaletteHover(hoveredPaletteItem);
            }
            if (hitItem != null) updatePaletteHover(hitItem);
                org.joml.Quaternionf beamRotation = new org.joml.Quaternionf().rotationTo(
                    new org.joml.Vector3f(0f, 0f, -1f), rayDirection);
                laserObject.getTransform().setPosition(rayOrigin.x, rayOrigin.y, rayOrigin.z);
                laserObject.getTransform().setRotation(beamRotation.w, beamRotation.x,
                    beamRotation.y, beamRotation.z);
            laserObject.getTransform().setScale(1f, 1f, distance / 12f);
                laserObject.getRenderData().getMaterial().setOpacity(
                    menuVisible() || pointerOverScreen || pointerOverScreenHeader || draggingScreen ? 0.85f : 0.3f);
                laserObject.getRenderData().getMaterial().setColor(hitItem != null || pointerOverScreen
                    || (pointerOverScreenHeader && !screenCenterLocked)
                    ? (triggerPressed ? 0xffffffff : 0xff46d9b5) : 0xffff5c4d);
                laserHitDot.getRenderData().getMaterial().setColor(hitItem != null || pointerOverScreen
                    || (pointerOverScreenHeader && !screenCenterLocked)
                    ? (triggerPressed ? 0xffffffff : 0xff46d9b5) : 0xffffffff);
                laserHitDot.getRenderData().getMaterial().setOpacity(0.95f);
                laserHitDot.getRenderData().setDepthTest(false);
                laserObject.getRenderData().setDepthTest(!menuVisible());
                if (distance >= 12f) {
                laserHitDot.setEnable(false);
            } else {
                float dotDistance = Math.max(0f, distance - 0.012f);
                laserHitDot.getTransform().setPosition(
                        rayOrigin.x + rayDirection.x * dotDistance,
                        rayOrigin.y + rayDirection.y * dotDistance,
                        rayOrigin.z + rayDirection.z * dotDistance);
                float dotScale = Math.max(0.5f, Math.min(3f, distance / 2f));
                laserHitDot.getTransform().setScale(dotScale, dotScale, dotScale);
                laserHitDot.setEnable(true);
            }
        }

        private float screenHitDistance() {
            pointerOverScreenHeader = false;
            if (videoObject == null) return 12f;
            zoomInverse.set(videoObject.getTransform().getModelMatrix()).invert();
            zoomInverse.transformPosition(rayOrigin, zoomRayOrigin);
            zoomInverse.transformDirection(rayDirection, zoomRayDirection);
            if (Math.abs(zoomRayDirection.z) < 0.0001f) return 12f;
            float distance = -zoomRayOrigin.z / zoomRayDirection.z;
            float localX = zoomRayOrigin.x + zoomRayDirection.x * distance;
            float localY = zoomRayOrigin.y + zoomRayDirection.y * distance;
                pointerOverScreenHeader = distance > 0f && distance < 12f
                    && Math.abs(localX) <= 2.135f && localY >= 1.18125f && localY <= 1.36125f;
            screenPointerX = Math.max(0f, Math.min(1f, localX / 4.2f + 0.5f));
            screenPointerY = Math.max(0f, Math.min(1f, 0.5f - localY / 2.3625f));
            return distance > 0f && distance < 12f
                && (pointerOverScreenHeader || (Math.abs(localX) <= 2.1f && Math.abs(localY) <= 1.18125f))
                ? distance : 12f;
        }

        private boolean isPaletteItem(GVRSceneObject object) {
            return object != null && ((object.getParent() == paletteRoot && paletteVisible)
                    || (object.getParent() == settingsRoot && settingsVisible));
        }

        private boolean menuVisible() {
            return paletteVisible || settingsVisible;
        }

        private GVRMaterial itemMaterial(GVRSceneObject item) {
            int index = (Integer) item.getTag();
            return index >= 100 ? settingsMaterials[index - 100] : paletteMaterials[index];
        }

        private boolean itemSelected(GVRSceneObject item) {
            int index = (Integer) item.getTag();
            return index < 100 ? index == backgroundIndex
                    : (index >= 104 && index <= 108
                    && Math.abs(screenOpacity - (1f - (index - 104) * 0.25f)) < 0.01f)
                    || (index == 102 && debugVisible)
                    || (index == 111 && screenCenterLocked);
        }

        private void animatePaletteHover() {
            long now = android.os.SystemClock.elapsedRealtime();
            float elapsed = lastPointerFrameMs == 0 ? 1f / 60f
                    : Math.min(0.05f, (now - lastPointerFrameMs) / 1000f);
            lastPointerFrameMs = now;
            float blend = 1f - (float) Math.exp(-18f * elapsed);
            if (!menuVisible()) return;
            for (GVRSceneObject item : paletteVisible ? paletteItems : settingsItems) {
                float target = 1f;
                float scale = item.getTransform().getScaleX();
                scale += (target - scale) * blend;
                item.getTransform().setScale(scale, scale, 1f);
                itemMaterial(item).setColor(item == hoveredPaletteItem || (item == zoomSlider && draggingZoom)
                    ? (triggerPressed && pressedPaletteItem == item ? 0xffffffff : 0xffffc857)
                    : (itemSelected(item) ? 0xff46d9b5 : 0xff71808a));
            }
        }

        private void onControllerEvent(GVRCursorController controller) {
            for (android.view.MotionEvent event : controller.getMotionEvents()) {
                int action = event.getActionMasked();
                float positionX = event.getX();
                float positionY = event.getY();
                getGVRContext().runOnGlThread(() -> {
                    if (controller == activeController
                            && controller.getControllerType() == GVRControllerType.CONTROLLER) {
                        handleTouch(action, positionX, positionY);
                    }
                });
            }
            for (android.view.KeyEvent event : controller.getKeyEvents()) {
                android.view.KeyEvent copy = new android.view.KeyEvent(event);
                getGVRContext().runOnGlThread(() -> {
                    if (controller == activeController) buttonEvents.add(copy);
                });
            }
        }

        private void handleTouch(int action, float positionX, float positionY) {
            if (action == android.view.MotionEvent.ACTION_UP
                    || action == android.view.MotionEvent.ACTION_CANCEL) {
                touchTracking = false;
                Log.d("GearVRTouch", "Touch ended");
                return;
            }
            if (action != android.view.MotionEvent.ACTION_DOWN
                    && action != android.view.MotionEvent.ACTION_MOVE) return;
            if (!Float.isFinite(positionX) || !Float.isFinite(positionY)) return;
            float deltaX = positionX - previousTouchX;
            float deltaY = positionY - previousTouchY;
            boolean moved = touchTracking && action == android.view.MotionEvent.ACTION_MOVE;
            touchTracking = true;
            previousTouchX = positionX;
            previousTouchY = positionY;
                if (!moved || menuVisible() || touchpadPressed || triggerPressed
                    || draggingScreen || pendingMenuClickMs != 0 || inputChannel == null) return;
            if (deltaX == 0f && deltaY == 0f) return;
            try {
                inputChannel.offer(new JSONObject().put("type", "scroll")
                        .put("deltaX", Math.max(-100f, Math.min(100f, deltaX)))
                        .put("deltaY", Math.max(-100f, Math.min(100f, deltaY))), false, false);
            } catch (org.json.JSONException error) {
                Log.w("GearVRStream", "Invalid scroll input", error);
            }
        }

        private void resetMenuClick() {
            pendingMenuClickMs = 0;
        }

        private void releaseTouchpadClick() {
            if (touchpadClickChannel == null) return;
            sendPointer(touchpadClickChannel, "up", pointerOverScreen ? screenPointerX : remotePointerX,
                    pointerOverScreen ? screenPointerY : remotePointerY);
            touchpadClickChannel = null;
        }

        private void updateScreenOpacity() {
            float opacity = cameraRequested ? screenOpacity : 1f;
            for (GVRSceneObject object : new GVRSceneObject[] {videoObject}) {
                object.getRenderData().getMaterial().setOpacity(opacity);
                object.getRenderData().setAlphaBlend(opacity < 1f);
                object.getRenderData().setDepthMask(opacity >= 1f);
            }
            videoObject.getRenderData().setRenderingOrder(1001);
                videoObject.getRenderData().setAlphaBlendFunc(
                    android.opengl.GLES20.GL_ONE, android.opengl.GLES20.GL_ONE_MINUS_SRC_ALPHA);
        }

        private void handleButton(android.view.KeyEvent event) {
            if (event.isCanceled()) {
                releaseTouchpadClick();
                endRemotePointer();
                draggingZoom = false;
                pendingMenuClickMs = 0;
                draggingScreen = false;
                pressedPaletteItem = null;
                touchpadPressed = false;
                triggerPressed = false;
                lastTriggerReleaseMs = 0;
                return;
            }
            if (event.getRepeatCount() != 0) return;
            int keyCode = event.getKeyCode();
            boolean down = event.getAction() == android.view.KeyEvent.ACTION_DOWN;
            if (!down && event.getAction() != android.view.KeyEvent.ACTION_UP) return;
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                if (down != touchpadPressed) {
                    if (!down) {
                        releaseTouchpadClick();
                        if (menuVisible() && pressedPaletteItem != null
                                && pressedPaletteItem != zoomSlider
                                && pressedPaletteItem == hoveredPaletteItem) {
                            int selection = (Integer) pressedPaletteItem.getTag();
                            if (selection >= 100) applySettingsSelection(selection - 100);
                            else applyPaletteSelection(selection);
                        }
                        draggingZoom = false;
                        pressedPaletteItem = null;
                    } else if (menuVisible()) {
                        pressedPaletteItem = hoveredPaletteItem;
                        if (settingsVisible && pressedPaletteItem == zoomSlider) {
                            draggingZoom = true;
                            updateZoomDrag();
                        }
                    } else if (!menuVisible() && !draggingScreen && pointerOverScreen
                            && decoderConfigured && inputChannel != null) {
                        touchpadClickChannel = inputChannel;
                        sendPointer(touchpadClickChannel, "down", screenPointerX, screenPointerY);
                    }
                }
                touchpadPressed = down;
            } else if (keyCode == android.view.KeyEvent.KEYCODE_A
                    || keyCode == android.view.KeyEvent.KEYCODE_BUTTON_A
                    || keyCode == android.view.KeyEvent.KEYCODE_BUTTON_R2) {
                if (down && !triggerPressed) {
                    triggerDownMs = android.os.SystemClock.elapsedRealtime();
                    if (!menuVisible() && pointerOverScreenHeader && !screenCenterLocked) {
                        releaseTouchpadClick();
                        screenGrabRotation.rotationTo(rayDirection, screenDirection);
                        draggingScreen = true;
                        lastTriggerReleaseMs = 0;
                    }
                }
                if (!down && triggerPressed) {
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (!draggingScreen && now - triggerDownMs < DOUBLE_CLICK_MS) {
                        if (lastTriggerReleaseMs != 0 && now - lastTriggerReleaseMs < DOUBLE_CLICK_MS) {
                            lastTriggerReleaseMs = 0;
                            if (menuVisible()) closeMenus();
                            else showMenu(false);
                        } else lastTriggerReleaseMs = now;
                    } else lastTriggerReleaseMs = 0;
                }
                triggerPressed = down;
                if (!down) {
                    draggingScreen = false;
                    endRemotePointer();
                    draggingZoom = false;
                    pressedPaletteItem = null;
                }
            }
        }

        private void chooseController() {
            GVRCursorController preferred = null;
            for (GVRCursorController controller : controllers) {
                if (!controller.isEnabled()) continue;
                if (controller.getControllerType() == GVRControllerType.CONTROLLER
                        || (preferred == null && controller.getControllerType() == GVRControllerType.GAZE)) {
                    preferred = controller;
                }
                if (controller.getControllerType() == GVRControllerType.CONTROLLER) break;
            }
            if (preferred != null && preferred != activeController) attachControllerLaser(preferred);
        }

        private void requestScreenCenter() {
            screenCenterRequested = true;
        }

        private void updateScreenAspect(int width, int height) {
            if (videoObject != null && width > 0 && height > 0) {
                float aspect = (float) width / height;
                float renderedHeight = Math.min(2.3625f, 3.65f / aspect);
                float renderedWidth = renderedHeight * aspect;
                videoObject.getTransform().setScale(screenZoom * renderedWidth / 4.2f,
                    screenZoom * renderedHeight / 2.3625f, 1f);
            }
        }

        private void centerScreen() {
            if (videoObject == null || scene == null) return;
            org.joml.Matrix4f head = new org.joml.Matrix4f().set(
                scene.getMainCameraRig().getHeadTransform().getModelMatrix());
            head.transformDirection(0f, 0f, -1f, screenDirection).normalize();
            placeScreen();
        }

            private void updateLockedScreen() {
                if (videoObject == null || scene == null) return;
                lockedScreenHead.set(scene.getMainCameraRig().getHeadTransform().getModelMatrix());
                lockedScreenHead.transformPosition(0f, 0f, 0f, lockedScreenPosition);
                lockedScreenHead.getUnnormalizedRotation(lockedScreenRotation).normalize();
                long now = android.os.SystemClock.elapsedRealtimeNanos();
                if (lockedScreenUpdateNs == 0 || now - lockedScreenUpdateNs > 250000000L) {
                    smoothedHeadPosition.set(lockedScreenPosition);
                    smoothedHeadRotation.set(lockedScreenRotation);
                } else {
                    float elapsed = (now - lockedScreenUpdateNs) / 1000000000f;
                    float blend = 1f - (float) Math.exp(-elapsed / 0.08f);
                    smoothedHeadPosition.lerp(lockedScreenPosition, blend);
                    smoothedHeadRotation.slerp(lockedScreenRotation, blend).normalize();
                }
                lockedScreenUpdateNs = now;
                lockedScreenPosition.set(0f, 0f, -2.5f);
                smoothedHeadRotation.transform(lockedScreenPosition);
                lockedScreenPosition.add(smoothedHeadPosition);
                videoObject.getTransform().setPosition(lockedScreenPosition.x,
                    lockedScreenPosition.y, lockedScreenPosition.z);
                videoObject.getTransform().setRotation(smoothedHeadRotation.w,
                    smoothedHeadRotation.x, smoothedHeadRotation.y, smoothedHeadRotation.z);
            }

        private void placeScreen() {
            screenDirection.normalize();
            screenDirection.y = Math.max(-0.95f, Math.min(0.95f, screenDirection.y));
            screenDirection.normalize();
            org.joml.Matrix4f head = new org.joml.Matrix4f().set(
                    scene.getMainCameraRig().getHeadTransform().getModelMatrix());
            org.joml.Vector3f position = head.transformPosition(0f, 0f, 0f,
                    new org.joml.Vector3f()).add(new org.joml.Vector3f(screenDirection).mul(2.5f));
            org.joml.Quaternionf rotation = new org.joml.Quaternionf()
                    .rotationTo(new org.joml.Vector3f(0f, 0f, -1f), screenDirection);
            videoObject.getTransform().setPosition(position.x, position.y, position.z);
            videoObject.getTransform().setRotation(rotation.w, rotation.x, rotation.y, rotation.z);
        }

        private void resetInteraction() {
            lastTriggerReleaseMs = 0;
            releaseTouchpadClick();
            touchTracking = false;
            endRemotePointer();
            draggingScreen = false;
            resetMenuClick();
            buttonEvents.clear();
            pressedPaletteItem = null;
            touchpadPressed = false;
            triggerPressed = false;
            closeMenus();
            if (laserObject != null) laserObject.setEnable(false);
            if (laserHitDot != null) laserHitDot.setEnable(false);
        }

        private void detachController() {
            lastTriggerReleaseMs = 0;
            releaseTouchpadClick();
            touchTracking = false;
            endRemotePointer();
            draggingZoom = false;
            draggingScreen = false;
            resetMenuClick();
            if (activeController != null) activeController.resetSceneObject();
            if (controllerPose != null && controllerPose.getParent() != null) {
                controllerPose.getParent().removeChildObject(controllerPose);
            }
            if (laserObject != null) laserObject.setEnable(false);
            if (laserObject != null && laserObject.getParent() == null) {
                scene.removeSceneObject(laserObject);
            }
            if (laserHitDot != null) laserHitDot.setEnable(false);
            if (hoveredPaletteItem != null) clearPaletteHover(hoveredPaletteItem);
            buttonEvents.clear();
            pressedPaletteItem = null;
            touchpadPressed = false;
            triggerPressed = false;
            activeController = null;
            controllerPose = null;
            laserObject = null;
        }

        private void attachControllerLaser(GVRCursorController controller) {
            detachController();
            controllerPose = new GVRSceneObject(getGVRContext());
            GVRLineSceneObject laser = new GVRLineSceneObject(getGVRContext(), 12f);
            GVRMaterial laserMaterial = new GVRMaterial(getGVRContext());
            laserMaterial.setColor(1.0f, 0.08f, 0.04f);
            laserMaterial.setLineWidth(2f);
            laser.getRenderData().setMaterial(laserMaterial);
            laser.getRenderData().setAlphaBlend(true);
            laser.getRenderData().setDepthTest(true);
            laser.getRenderData().setDepthMask(false);
            laser.getRenderData().setRenderingOrder(10000);
            laser.setPickingEnabled(false);
            if (laserHitDot == null && getGVRContext() != null) {
                createLaserHitDot(getGVRContext());
            }
            if (laserHitDot != null) {
                if (laserHitDot.getParent() != null) {
                    laserHitDot.getParent().removeChildObject(laserHitDot);
                }
                scene.addSceneObject(laserHitDot);
            }
            scene.addSceneObject(laser);
            if (controller.getControllerType() == GVRControllerType.CONTROLLER) {
                controller.setSceneObject(controllerPose);
                controllerPose.getTransform().setPosition(0f, 0f, 0f);
            } else {
                scene.getMainCameraRig().addChildObject(controllerPose);
            }
            laserObject = laser;
            activeController = controller;
        }

        private void createBackgroundPalette(GVRContext context) {
            paletteRoot = new GVRSceneObject(context);
            paletteRoot.setName("background-palette");
            paletteRoot.setEnable(false);
            scene.addSceneObject(paletteRoot);
            paletteItems = new GVRSceneObject[BACKGROUND_PATHS.length + 1];
            paletteMaterials = new GVRMaterial[paletteItems.length];
            addMenuSurface(paletteRoot, 3.05f, 2.75f, 0f, 0f, 0f, 0xff10171c);
            addMenuText(paletteRoot, "Environments", 2.7f, 0.24f, 0f, 1.18f, 0.015f);
            float itemWidth = 0.66f;
            float itemHeight = 0.52f;
            for (int index = 0; index < paletteItems.length; index++) {
                GVRSceneObject tile = addMenuSurface(paletteRoot, itemWidth, itemHeight,
                        (index % 4 - 1.5f) * 0.72f, 0.74f - (index / 4) * 0.59f,
                        0.025f, 0xff71808a);
                tile.setName("background-option-" + index);
                tile.setTag(index);
                tile.setPickingEnabled(true);
                tile.attachCollider(new GVRMeshCollider(context, true));
                GVRMaterial material = tile.getRenderData().getMaterial();
                GVRSceneObject preview = addMenuSurface(tile, 0.63f, 0.35f,
                        0f, 0.067f, 0.008f, 0xffffffff);
                if (index < BACKGROUND_PATHS.length) {
                    loadBackgroundThumbnail(context, index, preview.getRenderData().getMaterial());
                } else {
                    android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                            256, 144, android.graphics.Bitmap.Config.ARGB_8888);
                    android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
                    canvas.drawColor(0xff183c35);
                    android.graphics.Paint paint = new android.graphics.Paint(
                            android.graphics.Paint.ANTI_ALIAS_FLAG);
                    paint.setColor(0xffffffff);
                    paint.setTextSize(36f);
                    paint.setTextAlign(android.graphics.Paint.Align.CENTER);
                    canvas.drawText("Camera", 128f, 85f, paint);
                    org.gearvrf.GVRTexture texture = new org.gearvrf.GVRTexture(context);
                    texture.setImage(new org.gearvrf.GVRBitmapTexture(context, bitmap));
                    preview.getRenderData().getMaterial().setMainTexture(texture);
                }
                addMenuText(tile, index < BACKGROUND_NAMES.length ? BACKGROUND_NAMES[index]
                        : "Camera", 0.63f, 0.135f, 0f, -0.175f, 0.009f);
                paletteItems[index] = tile;
                paletteMaterials[index] = material;
            }
        }

        private GVRSceneObject addMenuSurface(GVRSceneObject parent, float width, float height,
                                             float positionX, float positionY, float positionZ,
                                             int color) {
            GVRSceneObject object = new GVRSceneObject(getGVRContext(), width, height);
            GVRMaterial material = new GVRMaterial(getGVRContext());
            material.setColor(color);
            object.getRenderData().setMaterial(material);
            object.getRenderData().disableLight();
            object.getRenderData().setDepthTest(false);
            object.getRenderData().setDepthMask(false);
                object.getRenderData().setRenderingOrder(parent.getRenderData() == null
                    ? 9000 + Math.round(positionZ * 1000)
                    : parent.getRenderData().getRenderingOrder() + 10);
            object.setPickingEnabled(false);
            object.getTransform().setPosition(positionX, positionY, positionZ);
            parent.addChildObject(object);
            return object;
        }

        private void setMenuText(GVRSceneObject object, String text, int width, int height) {
            object.getRenderData().getMaterial().setMainTexture(createMenuTextTexture(text, width, height));
        }

        private org.gearvrf.GVRTexture createMenuTextTexture(String text, int width, int height) {
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                    width, height, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
            canvas.drawColor(0xff182229);
            android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            paint.setColor(0xffffffff);
            paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            String[] lines = text.split("\n");
            float lineHeight = height / (float) lines.length;
            paint.setTextSize(Math.min(40f, lineHeight * 0.52f));
            for (String line : lines) {
                if (paint.measureText(line) > width - 40f) {
                    paint.setTextSize(paint.getTextSize() * (width - 40f) / paint.measureText(line));
                }
            }
            paint.setTextAlign(android.graphics.Paint.Align.CENTER);
            for (int index = 0; index < lines.length; index++) {
                canvas.drawText(lines[index], width / 2f,
                        lineHeight * (index + 0.5f) - (paint.ascent() + paint.descent()) / 2f, paint);
            }
            org.gearvrf.GVRTexture texture = new org.gearvrf.GVRTexture(getGVRContext());
            texture.setImage(new org.gearvrf.GVRBitmapTexture(getGVRContext(), bitmap));
            return texture;
        }

        private GVRSceneObject addMenuText(GVRSceneObject parent, String text, float width,
                                          float height, float positionX, float positionY, float positionZ) {
            GVRSceneObject object = addMenuSurface(parent, width, height,
                    positionX, positionY, positionZ, 0xffffffff);
            setMenuText(object, text, Math.round(width * 400), Math.round(height * 400));
            object.getRenderData().setRenderingOrder(9100);
            return object;
        }

        private void createSettingsMenu(GVRContext context) {
            settingsRoot = new GVRSceneObject(context);
            settingsRoot.setName("stream-settings");
            settingsRoot.getTransform().setScale(0.75f, 0.75f, 0.75f);
            settingsRoot.setEnable(false);
            scene.addSceneObject(settingsRoot);
            addMenuSurface(settingsRoot, 2.9f, 3.55f, 0f, -0.5f, 0f, 0xff10171c);
            addMenuText(settingsRoot, "Stream settings", 2.6f, 0.25f, 0f, 1.04f, 0.015f);
            settingsItems = new GVRSceneObject[11];
            settingsMaterials = new GVRMaterial[12];
            String[] labels = {"Environments", "Center screen", "Debug info", "Close",
                    "0%", "25%", "50%", "75%", "100%"};
            for (int index = 0; index < labels.length; index++) {
                boolean preset = index >= 4;
                float width = preset ? 0.49f : 1.25f;
                float positionX = preset ? (index - 6) * 0.54f : (index % 2 - 0.5f) * 1.35f;
                float positionY = preset ? -0.29f : 0.65f - (index / 2) * 0.39f;
                GVRSceneObject item = addMenuSurface(settingsRoot, width, 0.31f,
                        positionX, positionY, 0.025f, 0xff71808a);
                item.setTag(100 + index);
                item.setPickingEnabled(true);
                item.attachCollider(new GVRMeshCollider(context, true));
                addMenuText(item, labels[index], width - 0.025f, 0.285f, 0f, 0f, 0.008f);
                settingsItems[index] = item;
                settingsMaterials[index] = item.getRenderData().getMaterial();
            }
            addMenuText(settingsRoot, "Camera screen transparency", 2.6f, 0.2f, 0f, -0.02f, 0.015f);
                zoomLabel = addMenuText(settingsRoot, "Screen zoom: 100%", 2.6f, 0.2f,
                    0f, -0.59f, 0.015f);
                for (int index = 0; index < zoomLabelTextures.length; index++) {
                    zoomLabelTextures[index] = createMenuTextTexture(
                            "Screen zoom: " + (50 + index) + "%", 1040, 80);
                }
                zoomSlider = addMenuSurface(settingsRoot, 2.6f, 0.28f,
                    0f, -0.84f, 0.025f, 0xff71808a);
                zoomSlider.setName("screen-zoom-slider");
                zoomSlider.setTag(109);
                zoomSlider.setPickingEnabled(true);
                zoomSlider.attachCollider(new GVRMeshCollider(context, true));
                addMenuSurface(zoomSlider, 2.575f, 0.255f, 0f, 0f, 0.005f, 0xff182229);
                addMenuSurface(zoomSlider, ZOOM_TRACK_WIDTH, 0.035f, 0f, 0f, 0.01f, 0xff71808a);
                zoomFill = addMenuSurface(zoomSlider, ZOOM_TRACK_WIDTH, 0.035f,
                    0f, 0f, 0.015f, 0xff46d9b5);
                zoomThumb = addMenuSurface(zoomSlider, 0.08f, 0.19f,
                    0f, 0f, 0.02f, 0xffffffff);
                settingsItems[9] = zoomSlider;
                settingsMaterials[9] = zoomSlider.getRenderData().getMaterial();
                addMenuText(settingsRoot, "50%", 0.4f, 0.15f, -1.1f, -1.07f, 0.015f);
                addMenuText(settingsRoot, "150%", 0.4f, 0.15f, 1.1f, -1.07f, 0.015f);
                updateZoomSlider();
                GVRSceneObject lockScreen = addMenuSurface(settingsRoot, 1.25f, 0.31f,
                    0f, -1.35f, 0.025f, 0xff71808a);
                lockScreen.setTag(111);
                addMenuSurface(lockScreen, 1.225f, 0.285f, 0f, 0f, 0.005f, 0xff182229);
                addMenuSurface(lockScreen, 0.17f, 0.17f, -0.49f, 0f, 0.01f, 0xffffffff);
                addMenuSurface(lockScreen, 0.135f, 0.135f, -0.49f, 0f, 0.015f, 0xff182229);
                screenLockCheck = addMenuText(lockScreen, "X", 0.135f, 0.135f,
                    -0.49f, 0f, 0.02f);
                screenLockCheck.setEnable(screenCenterLocked);
                addMenuText(lockScreen, "Lock centered", 0.96f, 0.285f, 0.105f, 0f, 0.008f);
                settingsItems[10] = lockScreen;
                settingsMaterials[11] = lockScreen.getRenderData().getMaterial();
                debugPanel = addMenuText(settingsRoot, "", 2.6f, 0.5f, 0f, -1.88f, 0.015f);
            debugPanel.setEnable(false);
        }

            private void updateZoomDrag() {
                zoomInverse.set(zoomSlider.getTransform().getModelMatrix()).invert();
                zoomInverse.transformPosition(rayOrigin, zoomRayOrigin);
                zoomInverse.transformDirection(rayDirection, zoomRayDirection);
                if (Math.abs(zoomRayDirection.z) < 0.0001f) return;
                float distance = -zoomRayOrigin.z / zoomRayDirection.z;
                if (distance <= 0f) return;
                float localX = zoomRayOrigin.x + zoomRayDirection.x * distance;
                if (Float.isNaN(localX) || Float.isInfinite(localX)) return;
                screenZoom = 0.5f + Math.max(0f, Math.min(1f,
                    localX / ZOOM_TRACK_WIDTH + 0.5f));
                updateScreenAspect(videoWidth > 0 ? videoWidth : 1920,
                    videoHeight > 0 ? videoHeight : 1080);
                updateZoomSlider();
            }

            private void updateZoomSlider() {
                float fraction = screenZoom - 0.5f;
                zoomThumb.getTransform().setPositionX((fraction - 0.5f) * ZOOM_TRACK_WIDTH);
                zoomFill.setEnable(fraction > 0f);
                zoomFill.getTransform().setScale(Math.max(0.001f, fraction), 1f, 1f);
                zoomFill.getTransform().setPositionX((fraction - 1f) * ZOOM_TRACK_WIDTH / 2f);
                int percent = Math.round(screenZoom * 100f);
                if (percent != displayedZoomPercent) {
                displayedZoomPercent = percent;
                zoomLabel.getRenderData().getMaterial().setMainTexture(zoomLabelTextures[percent - 50]);
                }
            }

        private void updateDebugPanel() {
            String controller = activeController == null ? "Disconnected"
                    : activeController.getControllerType().toString();
            String endpoint = activeSenderEndpoint == null ? "Searching" : activeSenderEndpoint;
            setMenuText(debugPanel, "Sender: " + endpoint
                    + "\nH.264: " + (decoderConfigured ? videoWidth + " x " + videoHeight : "Waiting")
                    + "\nController: " + controller
                    + "\nEnvironment: " + (cameraRequested ? "Camera" : BACKGROUND_NAMES[backgroundIndex])
                    + " | Transparency: " + Math.round((1f - screenOpacity) * 100f) + "%", 1040, 260);
        }

        private void applySettingsSelection(int index) {
            pendingMenuClickMs = 0;
            if (index == 0) showMenu(true);
            else if (index == 1) {
                requestScreenCenter();
                closeMenus();
            } else if (index == 2) {
                debugVisible = !debugVisible;
                debugPanel.setEnable(debugVisible);
                if (debugVisible) updateDebugPanel();
            } else if (index == 3) closeMenus();
            else if (index == 11) {
                screenCenterLocked = !screenCenterLocked;
                screenLockCheck.setEnable(screenCenterLocked);
                draggingScreen = false;
                screenCenterRequested = false;
                screenCenterFrames = 0;
                if (screenCenterLocked) updateLockedScreen();
            }
            else if (index >= 4 && index <= 8) {
                screenOpacity = 1f - (index - 4) * 0.25f;
                updateScreenOpacity();
                if (debugVisible) updateDebugPanel();
            }
            Log.i("GearVRStream", "Settings selection: " + index
                    + " transparency=" + Math.round((1f - screenOpacity) * 100f)
                    + "% debug=" + debugVisible);
        }

        private void createLaserHitDot(GVRContext context) {
            laserHitDot = new GVRSphereSceneObject(context, false, 0.008f);
            GVRMaterial material = new GVRMaterial(context);
            material.setColor(0xffff140a);
            laserHitDot.getRenderData().setMaterial(material);
            laserHitDot.getRenderData().setAlphaBlend(true);
            laserHitDot.getRenderData().disableLight();
            laserHitDot.getRenderData().setDepthTest(true);
            laserHitDot.getRenderData().setDepthMask(false);
            laserHitDot.getRenderData().setRenderingOrder(10001);
            laserHitDot.setPickingEnabled(false);
            laserHitDot.setEnable(false);
            scene.addSceneObject(laserHitDot);
        }

        private void loadBackgroundThumbnail(GVRContext context, int index, GVRMaterial material) {
            try {
                String thumbnailPath = "backgrounds/thumbnails/"
                        + BACKGROUND_PATHS[index].substring("backgrounds/".length());
                GVRAndroidResource resource = new GVRAndroidResource(context, thumbnailPath);
                org.gearvrf.GVRTexture texture = context.getAssetLoader().loadTexture(resource,
                        new GVRAndroidResource.TextureCallback() {
                            @Override
                            public boolean stillWanted(GVRAndroidResource requestedResource) {
                                return requestedResource.equals(resource);
                            }

                            @Override
                            public void loaded(org.gearvrf.GVRImage image,
                                               GVRAndroidResource loadedResource) {
                            }

                            @Override
                            public void failed(Throwable error,
                                               GVRAndroidResource failedResource) {
                                Log.w("GearVRStream", "Background thumbnail unavailable: "
                                        + BACKGROUND_NAMES[index], error);
                            }
                        });
                material.setMainTexture(texture);
            } catch (IOException error) {
                Log.w("GearVRStream", "Background thumbnail unavailable: "
                        + BACKGROUND_NAMES[index], error);
            }
        }

        private void closeMenus() {
            releaseTouchpadClick();
            draggingZoom = false;
            resetMenuClick();
            pressedPaletteItem = null;
            if (hoveredPaletteItem != null) clearPaletteHover(hoveredPaletteItem);
            paletteVisible = false;
            settingsVisible = false;
            if (paletteRoot != null) paletteRoot.setEnable(false);
            if (settingsRoot != null) settingsRoot.setEnable(false);
            if (laserHitDot != null) laserHitDot.setEnable(false);
        }

        private void showMenu(boolean palette) {
            releaseTouchpadClick();
            endRemotePointer();
            closeMenus();
            draggingScreen = false;
            paletteVisible = palette;
            settingsVisible = !palette;
            GVRSceneObject root = palette ? paletteRoot : settingsRoot;
                org.joml.Matrix4f head = new org.joml.Matrix4f().set(
                    scene.getMainCameraRig().getHeadTransform().getModelMatrix());
            org.joml.Vector3f position = head.transformPosition(0f, 0f, -2.15f,
                    new org.joml.Vector3f());
            org.joml.Quaternionf rotation = head.getUnnormalizedRotation(new org.joml.Quaternionf());
            root.getTransform().setPosition(position.x, position.y, position.z);
            root.getTransform().setRotation(rotation.w, rotation.x, rotation.y, rotation.z);
            root.setEnable(true);
            if (!palette && debugVisible) updateDebugPanel();
            Log.i("GearVRStream", "VR menu opened: " + (palette ? "environments" : "settings"));
        }

        private void updatePaletteHover(GVRSceneObject item) {
            hoveredPaletteItem = item;
        }

        private void clearPaletteHover(GVRSceneObject item) {
            if (hoveredPaletteItem != item) return;
                itemMaterial(item).setColor(itemSelected(item) ? 0xff46d9b5 : 0xff71808a);
                item.getTransform().setScale(1f, 1f, 1f);
            hoveredPaletteItem = null;
                if (laserHitDot != null) laserHitDot.setEnable(false);
        }

        private void applyPaletteSelection(int index) {
            if (index == BACKGROUND_PATHS.length
                    && checkSelfPermission(Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> requestPermissions(
                        new String[] {Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST));
                return;
            }
            resetMenuClick();
            backgroundIndex = index;
            cameraRequested = index == BACKGROUND_PATHS.length;
            if (cameraRequested) {
                if (cameraObject == null) {
                    GVRVideoSceneObject preview = new GVRVideoSceneObject(getGVRContext(),
                            16f, 9f, cameraPlayer, GVRVideoSceneObject.GVRVideoType.MONO);
                    preview.setName("rear-camera-passthrough");
                    preview.getTransform().setPosition(0f, 0f, -4f);
                    preview.getRenderData().setDepthTest(false);
                    preview.getRenderData().setDepthMask(false);
                    preview.getRenderData().setRenderingOrder(-90);
                    preview.setPickingEnabled(false);
                    scene.getMainCameraRig().addChildObject(preview);
                    cameraObject = preview;
                }
                cameraObject.setEnable(true);
                cameraPlayer.start();
            } else {
                if (cameraObject != null) cameraObject.setEnable(false);
                cameraPlayer.pause();
                loadBackground(getGVRContext(), index);
            }
            updateScreenOpacity();
            for (int itemIndex = 0; itemIndex < paletteMaterials.length; itemIndex++) {
                paletteMaterials[itemIndex].setColor(itemIndex == index
                        ? 0xff46d9b5 : 0xff71808a);
                paletteItems[itemIndex].getTransform().setScale(1f, 1f, 1f);
            }
            closeMenus();
        }

        private void changeBackground(int direction) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastBackgroundChangeMs < 400) return;
            lastBackgroundChangeMs = now;
            backgroundIndex = (backgroundIndex + direction + BACKGROUND_PATHS.length)
                    % BACKGROUND_PATHS.length;
            loadBackground(getGVRContext(), backgroundIndex);
        }

        private void loadBackground(GVRContext context, int index) {
            org.gearvrf.GVRTextureParameters parameters = new GVRTextureParameters(context);
            parameters.setWrapSType(GVRTextureParameters.TextureWrapType.GL_REPEAT);
            parameters.setWrapTType(GVRTextureParameters.TextureWrapType.GL_CLAMP_TO_EDGE);
            parameters.setMinFilterType(GVRTextureParameters.TextureFilterType.GL_LINEAR);
            parameters.setMagFilterType(GVRTextureParameters.TextureFilterType.GL_LINEAR);
            GVRAndroidResource resource;
            try {
                resource = new GVRAndroidResource(context, BACKGROUND_PATHS[index]);
            } catch (IOException error) {
                return;
            }
            org.gearvrf.GVRTexture texture = context.getAssetLoader().loadTexture(resource,
                    new GVRAndroidResource.TextureCallback() {
                        @Override
                        public boolean stillWanted(GVRAndroidResource requestedResource) {
                            return index == backgroundIndex && requestedResource.equals(resource);
                        }

                        @Override
                        public void loaded(org.gearvrf.GVRImage image,
                                           GVRAndroidResource loadedResource) {
                        }

                        @Override
                        public void failed(Throwable error, GVRAndroidResource failedResource) {
                        }
                    }, parameters, 1, 1);
            if (backgroundObject == null) {
                backgroundObject = new GVRSphereSceneObject(context, true, texture);
                backgroundObject.getTransform().setScale(50, 50, 50);
                backgroundObject.getRenderData().setDepthTest(false);
                backgroundObject.getRenderData().setDepthMask(false);
                backgroundObject.getRenderData().setRenderingOrder(-100);
                backgroundObject.getRenderData().getPass(0)
                    .setCullFace(org.gearvrf.GVRRenderPass.GVRCullFaceEnum.None);
                scene.addSceneObject(backgroundObject);
            } else {
                backgroundObject.getRenderData().getMaterial().setMainTexture(texture);
            }
        }
    }

    private final class CameraSurfacePlayer implements GVRVideoSceneObjectPlayer<Camera> {
        private Camera camera;
        private volatile Surface surface;

        @Override
        public Camera getPlayer() { return camera; }

        @Override
        public void setSurface(Surface surface) {
            this.surface = surface;
            start();
        }

        @Override
        public void start() {
            mainHandler.post(() -> {
                if (!activityResumed || !cameraRequested || surface == null || camera != null
                        || checkSelfPermission(Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED) return;
                try {
                    int cameraId = -1;
                    Camera.CameraInfo info = new Camera.CameraInfo();
                    for (int index = 0; index < Camera.getNumberOfCameras(); index++) {
                        Camera.getCameraInfo(index, info);
                        if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                            cameraId = index;
                            break;
                        }
                    }
                    if (cameraId < 0) throw new IllegalStateException("No rear camera available");
                    camera = Camera.open(cameraId);
                    Camera.Parameters parameters = camera.getParameters();
                    Camera.Size bestSize = null;
                    for (Camera.Size size : parameters.getSupportedPreviewSizes()) {
                        if (size.width <= 1280 && Math.abs((float) size.width / size.height
                                - 16f / 9f) < 0.03f
                                && (bestSize == null || size.width > bestSize.width)) bestSize = size;
                    }
                    if (bestSize != null) parameters.setPreviewSize(bestSize.width, bestSize.height);
                    if (parameters.getSupportedFocusModes().contains(
                            Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                        parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
                    }
                    camera.setParameters(parameters);
                    int rotation = getWindowManager().getDefaultDisplay().getRotation() * 90;
                    camera.setDisplayOrientation((info.orientation - rotation + 360) % 360);
                    camera.setPreviewDisplay(new android.view.SurfaceHolder() {
                        @Override public Surface getSurface() { return surface; }
                        @Override public void addCallback(Callback callback) { }
                        @Override public void removeCallback(Callback callback) { }
                        @Override public boolean isCreating() { return false; }
                        @Override public void setType(int type) { }
                        @Override public void setFixedSize(int width, int height) { }
                        @Override public void setSizeFromLayout() { }
                        @Override public void setFormat(int format) { }
                        @Override public void setKeepScreenOn(boolean keepScreenOn) { }
                        @Override public android.graphics.Canvas lockCanvas() { return null; }
                        @Override public android.graphics.Canvas lockCanvas(android.graphics.Rect dirty) { return null; }
                        @Override public void unlockCanvasAndPost(android.graphics.Canvas canvas) { }
                        @Override public android.graphics.Rect getSurfaceFrame() { return new android.graphics.Rect(); }
                    });
                    camera.startPreview();
                    Log.i("GearVRStream", "Rear camera passthrough started");
                } catch (IOException | RuntimeException error) {
                    closeCamera();
                    Log.e("GearVRStream", "Rear camera unavailable", error);
                    Toast.makeText(MainActivity.this, "Rear camera unavailable",
                            Toast.LENGTH_LONG).show();
                }
            });
        }

        private void closeCamera() {
            if (camera == null) return;
            try { camera.stopPreview(); } catch (RuntimeException ignored) { }
            camera.release();
            camera = null;
        }

        @Override
        public void pause() { mainHandler.post(this::closeCamera); }

        @Override
        public void release() {
            mainHandler.post(() -> {
                closeCamera();
                if (surface != null) surface.release();
                surface = null;
            });
        }

        @Override
        public boolean canReleaseSurfaceImmediately() { return false; }
    }

    private final class DecoderSurfacePlayer implements GVRVideoSceneObjectPlayer<MediaCodec> {
        @Override
        public MediaCodec getPlayer() {
            return decoder;
        }

        @Override
        public void setSurface(Surface surface) {
            synchronized (MainActivity.this) {
                decoderSurface = surface;
                if (videoSps != null && videoPps != null) {
                    configureDecoder(videoWidth, videoHeight, videoSps, videoPps);
                }
            }
        }

        @Override
        public void release() {
            synchronized (MainActivity.this) {
                releaseDecoder();
                if (decoderSurface != null) decoderSurface.release();
                decoderSurface = null;
            }
        }

        @Override
        public boolean canReleaseSurfaceImmediately() {
            return false;
        }

        @Override
        public void pause() {
        }

        @Override
        public void start() {
        }
    }
}