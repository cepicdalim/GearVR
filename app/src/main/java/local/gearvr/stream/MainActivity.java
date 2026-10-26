package local.gearvr.stream;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import org.gearvrf.GVRActivity;
import org.gearvrf.GVRAndroidResource;
import org.gearvrf.GVRContext;
import org.gearvrf.GVRMain;
import org.gearvrf.GVRMaterial;
import org.gearvrf.GVRScene;
import org.gearvrf.GVRTextureParameters;
import org.gearvrf.GVRSceneObject;
import org.gearvrf.GVRCursorController;
import org.gearvrf.io.CursorControllerListener;
import org.gearvrf.io.GVRControllerType;
import org.gearvrf.scene_objects.GVRTextViewSceneObject;
import org.gearvrf.scene_objects.GVRSphereSceneObject;
import org.gearvrf.scene_objects.GVRVideoSceneObject;
import org.gearvrf.scene_objects.GVRVideoSceneObjectPlayer;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;

public final class MainActivity extends GVRActivity implements SensorEventListener {
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
    private final float[] rotation = new float[3];
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SensorManager sensorManager;
    private NsdManager nsdManager;
    private WifiManager.MulticastLock multicastLock;
    private NsdManager.DiscoveryListener discoveryListener;
    private boolean fallbackAttempted;
    private volatile boolean fallbackFound;
    private Thread fallbackThread;
    private volatile Surface decoderSurface;
    private volatile Socket streamSocket;
    private volatile boolean receiverRunning;
    private volatile String activeSenderEndpoint;
    private Thread receiverThread;
    private MediaCodec decoder;
    private volatile boolean decoderConfigured;
    private int videoWidth;
    private int videoHeight;
    private volatile byte[] videoSps;
    private volatile byte[] videoPps;
    private volatile String lastInput = "Waiting for controller input";
    private volatile String discoveredSender = "Searching local network for a Mac sender...";
    private volatile String statusHeading = "Receiver ready";
    private volatile String sceneStatusText = "";
    private volatile GVRTextViewSceneObject vrStatus;
    private volatile StreamVrMain vrMain;
    private long lastControllerActionMs;

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

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        multicastLock = wifiManager.createMulticastLock("gearvr-stream-discovery");
        multicastLock.setReferenceCounted(false);
        vrMain = new StreamVrMain();
        setMain(vrMain);
        renderStatus("VR receiver starting");
    }

    @Override
    protected void onResume() {
        super.onResume();
        statusHeading = "Receiver foregrounded";
        startDiscovery();
        Sensor rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (rotationSensor == null) {
            rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }
        if (rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
        }
        renderStatus("Head tracking active");
    }

    @Override
    protected void onPause() {
        statusHeading = "Receiver paused";
        sensorManager.unregisterListener(this);
        stopDiscovery();
        stopReceiver();
        super.onPause();
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN && vrMain != null
                && handleControllerKey(event.getKeyCode())) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean handleControllerKey(int keyCode) {
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == android.view.KeyEvent.KEYCODE_BUTTON_R1) {
            vrMain.changeBackground(1);
        } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == android.view.KeyEvent.KEYCODE_BUTTON_L1) {
            vrMain.changeBackground(-1);
        } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == android.view.KeyEvent.KEYCODE_BUTTON_A) {
            vrMain.toggleTvFrame();
        } else {
            return false;
        }
        lastInput = android.view.KeyEvent.keyCodeToString(keyCode) + " pressed";
        renderStatus("Controller input detected");
        return true;
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
                    renderStatus("Searching for Mac sender");
                });
            }

            @Override
            public void onServiceFound(NsdServiceInfo service) {
                if (!"_gearvrsender._tcp.".equals(service.getServiceType())) {
                    return;
                }
                runOnUiThread(() -> {
                    discoveredSender = "Found sender: " + service.getServiceName() + ", resolving...";
                    renderStatus("Receiver diagnostics only");
                });
                nsdManager.resolveService(service, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo ignored, int errorCode) {
                        tryFallbackDiscovery();
                        runOnUiThread(() -> {
                            discoveredSender = "Sender found, address lookup failed (" + errorCode + ")";
                            renderStatus("Sender address lookup failed");
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
                            renderStatus("Connecting to Mac sender");
                        });
                    }
                });
            }

            @Override
            public void onServiceLost(NsdServiceInfo service) {
                runOnUiThread(() -> {
                    discoveredSender = "Searching local network for a Mac sender...";
                    renderStatus("Searching for Mac sender");
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
                    renderStatus("Network discovery failed");
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
                        renderStatus("Connected to Mac sender");
                    });
                } catch (IOException ignored) {
                }
            }
            if (!fallbackFound) {
                runOnUiThread(() -> {
                    if (!receiverRunning) {
                        discoveredSender = "No sender found on this Wi-Fi subnet";
                        renderStatus("Check Wi-Fi and firewall");
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
        try {
            streamSocket = socket;
            bindSocketToWifi(socket);
            socket.setTcpNoDelay(true);
            Log.i("GearVRStream", "Connecting to sender " + host + ":" + service.getPort());
            socket.connect(new InetSocketAddress(service.getHost(), service.getPort()), 5000);
            Log.i("GearVRStream", "TCP connected to sender " + host + ":" + service.getPort());
            DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            runOnUiThread(() -> {
                discoveredSender = "Connected to " + host + ":" + service.getPort() + ", waiting for video";
                renderStatus("Video connection active");
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
            if (streamSocket == socket) streamSocket = null;
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
            runOnUiThread(() -> {
                discoveredSender = "Receiving " + width + " x " + height + " H.264";
                renderStatus("Video stream active");
            });
        } catch (Exception error) {
            decoderConfigured = false;
            showReceiverError("H.264 decoder failed: " + error.getMessage());
        }
    }

    private synchronized void decodeFrame(byte[] packet) throws IOException {
        if (packet.length < 9 || decoder == null) return;
        ByteBuffer data = ByteBuffer.wrap(packet).order(java.nio.ByteOrder.BIG_ENDIAN);
        long presentationTimeUs = data.getLong();
        boolean keyFrame = data.get() != 0;
        byte[] accessUnit = new byte[data.remaining()];
        data.get(accessUnit);
        byte[] annexB = toAnnexB(accessUnit);
        MediaCodec activeDecoder = decoder;
        if (activeDecoder == null) return;
        int inputIndex = activeDecoder.dequeueInputBuffer(10000);
        if (inputIndex >= 0) {
            ByteBuffer input = activeDecoder.getInputBuffer(inputIndex);
            if (input != null && input.remaining() >= annexB.length) {
                input.clear();
                input.put(annexB);
                activeDecoder.queueInputBuffer(inputIndex, 0, annexB.length,
                        Math.max(0, presentationTimeUs), keyFrame ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
            } else {
                activeDecoder.queueInputBuffer(inputIndex, 0, 0, 0, 0);
            }
        }
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int outputIndex;
        while ((outputIndex = activeDecoder.dequeueOutputBuffer(info, 0)) >= 0) {
            activeDecoder.releaseOutputBuffer(outputIndex, true);
        }
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
            renderStatus("Video receiver error");
        });
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_GAME_ROTATION_VECTOR
                || event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            float[] matrix = new float[9];
            SensorManager.getRotationMatrixFromVector(matrix, event.values);
            float[] orientation = new float[3];
            SensorManager.getOrientation(matrix, orientation);
            rotation[0] = (float) Math.toDegrees(orientation[0]);
            rotation[1] = (float) Math.toDegrees(orientation[1]);
            rotation[2] = (float) Math.toDegrees(orientation[2]);
            renderStatus("Head tracking active");
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void renderStatus(String heading) {
        statusHeading = heading;
        sceneStatusText = String.format(Locale.US,
                "GEAR VR STREAM  |  %s\n%s\nHead %+.0f / %+.0f / %+.0f  |  %s",
                statusHeading, discoveredSender, rotation[0], rotation[1], rotation[2], lastInput);
    }

    private final class StreamVrMain extends GVRMain {
        private GVRTextViewSceneObject statusObject;
        private String renderedStatus;
        private GVRScene scene;
        private GVRSphereSceneObject backgroundObject;
        private GVRSceneObject screenFrame;
        private int backgroundIndex;
        private long lastBackgroundChangeMs;
        private boolean tvFrameEnabled = true;
        private float touchpadStartX;
        private boolean trackingTouchpadSwipe;
        private float touchpadLastX;
        private final CursorControllerListener controllerListener = new CursorControllerListener() {
            @Override
            public void onCursorControllerAdded(GVRCursorController controller) {
                controller.addControllerEventListener(StreamVrMain.this::onControllerEvent);
                lastInput = "Controller connected: " + controller.getControllerType();
                renderStatus("Controller ready");
            }

            @Override
            public void onCursorControllerRemoved(GVRCursorController controller) {
                controller.removeControllerEventListener(StreamVrMain.this::onControllerEvent);
                lastInput = "Controller disconnected";
                renderStatus("Controller disconnected");
            }
        };

        @Override
        public void onInit(GVRContext context) {
            scene = context.getMainScene();
            loadBackground(context, backgroundIndex);
            screenFrame = new GVRSceneObject(context, 3.38f, 1.98f);
            GVRMaterial frameMaterial = new GVRMaterial(context);
            frameMaterial.setColor(0xff111111);
            screenFrame.getRenderData().setMaterial(frameMaterial);
            screenFrame.getTransform().setPosition(0, 0, -2.53f);
            scene.addSceneObject(screenFrame);
            GVRVideoSceneObject video = new GVRVideoSceneObject(context, 3.2f, 1.8f,
                    new DecoderSurfacePlayer(), GVRVideoSceneObject.GVRVideoType.MONO);
            video.getTransform().setPosition(0, 0, -2.5f);
            scene.addSceneObject(video);

            statusObject = new GVRTextViewSceneObject(context, 2.7f, 0.35f, sceneStatusText);
            statusObject.setTextSize(20);
            statusObject.setTextColor(0xfff2f5f7);
            statusObject.setBackgroundColor(0xaa101820);
            statusObject.getTransform().setPosition(0, -1.15f, -2.4f);
            scene.addSceneObject(statusObject);
            vrStatus = statusObject;

            context.getInputManager().addCursorControllerListener(controllerListener);
            for (GVRCursorController controller : context.getInputManager().getCursorControllers()) {
                controllerListener.onCursorControllerAdded(controller);
            }
            renderStatus("Stereo VR scene ready");
        }

        @Override
        public void onStep() {
            String text = sceneStatusText;
            if (statusObject != null && !text.equals(renderedStatus)) {
                renderedStatus = text;
                statusObject.setText(text);
            }
        }

        private void onControllerEvent(GVRCursorController controller) {
            for (android.view.KeyEvent event : controller.getKeyEvents()) {
                if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                    handleControllerKey(event.getKeyCode());
                }
                lastInput = android.view.KeyEvent.keyCodeToString(event.getKeyCode())
                        + (event.getAction() == android.view.KeyEvent.ACTION_DOWN ? " pressed" : " released");
            }
            for (MotionEvent event : controller.getMotionEvents()) {
                lastInput = String.format(Locale.US, "Controller touch x %.2f, y %.2f",
                        event.getX(), event.getY());
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_BUTTON_PRESS) {
                    touchpadStartX = event.getX();
                    touchpadLastX = event.getX();
                    trackingTouchpadSwipe = true;
                } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE
                        && trackingTouchpadSwipe) {
                    float swipeDistance = event.getX() - touchpadLastX;
                    if (Math.abs(swipeDistance) >= 0.12f) {
                        performTouchpadSwipe(swipeDistance);
                        trackingTouchpadSwipe = false;
                    }
                    touchpadLastX = event.getX();
                } else if ((action == MotionEvent.ACTION_UP
                        || action == MotionEvent.ACTION_BUTTON_RELEASE) && trackingTouchpadSwipe) {
                    float swipeDistance = event.getX() - touchpadStartX;
                    if (Math.abs(swipeDistance) >= 0.12f) {
                        performTouchpadSwipe(swipeDistance);
                    } else {
                        toggleTvFrame();
                    }
                    trackingTouchpadSwipe = false;
                } else if (action == MotionEvent.ACTION_CANCEL) {
                    trackingTouchpadSwipe = false;
                }
            }
            renderStatus("Controller input detected");
        }

        private void performTouchpadSwipe(float distance) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastControllerActionMs < 400) return;
            lastControllerActionMs = now;
            lastInput = distance < 0 ? "Touchpad swipe left" : "Touchpad swipe right";
            changeBackground(distance < 0 ? 1 : -1);
        }

        private void toggleTvFrame() {
            if (screenFrame == null) return;
            tvFrameEnabled = !tvFrameEnabled;
            screenFrame.setEnable(tvFrameEnabled);
            renderStatus(tvFrameEnabled ? "TV frame layout" : "Floating screen layout");
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
                renderStatus("Background unavailable: " + BACKGROUND_NAMES[index]);
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
                            renderStatus("Background: " + BACKGROUND_NAMES[index]);
                        }

                        @Override
                        public void failed(Throwable error, GVRAndroidResource failedResource) {
                            renderStatus("Background load failed: " + BACKGROUND_NAMES[index]);
                        }
                    }, parameters, 1, 1);
            if (backgroundObject == null) {
                backgroundObject = new GVRSphereSceneObject(context, true, texture);
                backgroundObject.getTransform().setScale(50, 50, 50);
                backgroundObject.getRenderData().setDepthTest(false);
                backgroundObject.getRenderData().setDepthMask(false);
                backgroundObject.getRenderData().setRenderingOrder(-100);
                scene.addSceneObject(backgroundObject);
            } else {
                backgroundObject.getRenderData().getMaterial().setMainTexture(texture);
            }
        }
    }

    private final class DecoderSurfacePlayer implements GVRVideoSceneObjectPlayer<MediaCodec> {
        @Override
        public MediaCodec getPlayer() {
            return decoder;
        }

        @Override
        public void setSurface(Surface surface) {
            decoderSurface = surface;
            if (videoSps != null && videoPps != null) {
                configureDecoder(videoWidth, videoHeight, videoSps, videoPps);
            }
        }

        @Override
        public void release() {
            synchronized (MainActivity.this) {
                releaseDecoder();
            }
        }

        @Override
        public boolean canReleaseSurfaceImmediately() {
            return true;
        }

        @Override
        public void pause() {
        }

        @Override
        public void start() {
        }
    }
}