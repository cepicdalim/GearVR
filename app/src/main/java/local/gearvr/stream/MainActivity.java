package local.gearvr.stream;

import android.content.Context;
import android.Manifest;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.view.MotionEvent;
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
        setMain(vrMain);
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        cameraPlayer.start();
        startDiscovery();
    }

    @Override
    protected void onPause() {
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
        private GVRSceneObject screenFrame;
        private GVRSceneObject paletteRoot;
        private GVRSceneObject[] paletteItems;
        private GVRMaterial[] paletteMaterials;
        private GVRSceneObject hoveredPaletteItem;
        private GVRSceneObject laserHitDot;
        private int backgroundIndex;
        private long lastBackgroundChangeMs;
        private boolean paletteVisible;
        private GVRCursorController activeController;
        private GVRSceneObject laserObject;
        private GVRSceneObject controllerPose;
        private GVRSceneObject videoObject;
        private GVRSceneObject cameraObject;
        private float screenOpacity = 0.75f;
        private boolean slidingTransparency;
        private float lastTouchX;
        private GVRSceneObject pressedPaletteItem;
        private boolean touchpadPressed;
        private boolean triggerPressed;
        private long lastPointerFrameMs;
        private int pickingCheckFrames;
        private final ArrayList<GVRCursorController> controllers = new ArrayList<>();
        private final ConcurrentLinkedQueue<android.view.KeyEvent> buttonEvents =
                new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<MotionEvent> touchEvents =
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
            screenFrame = new GVRSceneObject(context, 4.38f, 2.54f);
            GVRMaterial frameMaterial = new GVRMaterial(context);
            frameMaterial.setColor(0xff111111);
            screenFrame.getRenderData().setMaterial(frameMaterial);
            screenFrame.getRenderData().setDepthMask(false);
            screenFrame.getTransform().setPosition(0, 0, -2.53f);
            screenFrame.attachCollider(new GVRMeshCollider(context, false));
            scene.addSceneObject(screenFrame);
            createBackgroundPalette(context);
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
            scene.addSceneObject(video);
            createLaserHitDot(context);
            togglePalette();

                Log.i("GearVRStream", "VR scene initialized: palette=" + paletteVisible
                    + " tiles=" + paletteItems.length + " videoSurface=" + (decoderSurface != null));

            context.getInputManager().addCursorControllerListener(controllerListener);
            for (GVRCursorController controller : context.getInputManager().getCursorControllers()) {
                controllerListener.onCursorControllerAdded(controller);
            }
        }

        @Override
        public void onStep() {
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
            }
            updatePointer();
            android.view.KeyEvent event;
            while ((event = buttonEvents.poll()) != null) {
                handleButton(event);
                updatePointer();
            }
            MotionEvent touch;
            while ((touch = touchEvents.poll()) != null) {
                handleTouch(touch);
                touch.recycle();
            }
            animatePaletteHover();
        }

        private void updatePointer() {
            if (laserObject == null || laserHitDot == null || controllerPose == null) return;
            laserObject.setEnable(paletteVisible);
            if (!paletteVisible) {
                laserHitDot.setEnable(false);
                return;
            }
            pointerMatrix.set(controllerPose.getTransform().getModelMatrix());
            pointerMatrix.transformPosition(0f, 0f, 0f, rayOrigin);
            pointerMatrix.transformDirection(0f, 0f, -1f, rayDirection);
            if (rayDirection.lengthSquared() < 0.000001f) {
                laserObject.setEnable(false);
                laserHitDot.setEnable(false);
                if (hoveredPaletteItem != null) clearPaletteHover(hoveredPaletteItem);
                return;
            }
            rayDirection.normalize();
            GVRPicker.GVRPickedObject nearest = null;
            GVRSceneObject nearestTarget = null;
                for (int index = 0; index < paletteItems.length + 2; index++) {
                GVRSceneObject target = index < paletteItems.length ? paletteItems[index]
                    : (index == paletteItems.length ? videoObject : screenFrame);
                GVRPicker.GVRPickedObject hit = GVRPicker.pickSceneObject(target,
                    rayOrigin.x, rayOrigin.y, rayOrigin.z,
                    rayDirection.x, rayDirection.y, rayDirection.z);
                if (hit == null) continue;
                if (hit.hitDistance > 0f && hit.hitDistance <= 12f
                        && (nearest == null || hit.hitDistance < nearest.hitDistance)) {
                    nearest = hit;
                    nearestTarget = target;
                }
            }
            GVRSceneObject hitItem = nearest != null && isPaletteItem(nearest.hitObject)
                    ? nearest.hitObject : null;
            if (hoveredPaletteItem != null && hoveredPaletteItem != hitItem) {
                clearPaletteHover(hoveredPaletteItem);
            }
            if (hitItem != null) updatePaletteHover(hitItem);
            float distance = nearest == null ? 12f : nearest.hitDistance;
            laserObject.getTransform().setScale(1f, 1f, distance / 12f);
            if (nearest == null) {
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

        private boolean isPaletteItem(GVRSceneObject object) {
            return object != null && object.getParent() == paletteRoot && paletteVisible;
        }

        private void animatePaletteHover() {
            long now = android.os.SystemClock.elapsedRealtime();
            float elapsed = lastPointerFrameMs == 0 ? 1f / 60f
                    : Math.min(0.05f, (now - lastPointerFrameMs) / 1000f);
            lastPointerFrameMs = now;
            float blend = 1f - (float) Math.exp(-18f * elapsed);
            for (GVRSceneObject item : paletteItems) {
                float target = item == hoveredPaletteItem ? 1.06f : 1f;
                float scale = item.getTransform().getScaleX();
                scale += (target - scale) * blend;
                item.getTransform().setScale(scale, scale, 1f);
                int index = (Integer) item.getTag();
                paletteMaterials[index].setColor(item == hoveredPaletteItem
                        ? (triggerPressed && pressedPaletteItem == item ? 0xffffffff : 0xffe07a32)
                        : (index == backgroundIndex ? 0xff168aad : 0xff263744));
            }
        }

        private void onControllerEvent(GVRCursorController controller) {
            for (android.view.KeyEvent event : controller.getKeyEvents()) {
                android.view.KeyEvent copy = new android.view.KeyEvent(event);
                getGVRContext().runOnGlThread(() -> {
                    if (controller == activeController) buttonEvents.add(copy);
                });
            }
            if (controller.getControllerType() == GVRControllerType.CONTROLLER) {
                for (MotionEvent event : controller.getMotionEvents()) {
                    MotionEvent copy = MotionEvent.obtain(event);
                    getGVRContext().runOnGlThread(() -> {
                        if (controller == activeController) touchEvents.add(copy);
                        else copy.recycle();
                    });
                }
            }
        }

        private void handleTouch(MotionEvent event) {
            if (!cameraRequested || paletteVisible || touchpadPressed) {
                slidingTransparency = false;
                return;
            }
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                lastTouchX = event.getX();
                slidingTransparency = true;
            } else if (action == MotionEvent.ACTION_MOVE && slidingTransparency) {
                float touchX = event.getX();
                screenOpacity = Math.max(0f, Math.min(1f,
                        screenOpacity + (touchX - lastTouchX)));
                lastTouchX = touchX;
                updateScreenOpacity();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                slidingTransparency = false;
            }
        }

        private void resetTouch() {
            slidingTransparency = false;
            MotionEvent touch;
            while ((touch = touchEvents.poll()) != null) touch.recycle();
        }

        private void updateScreenOpacity() {
            float opacity = cameraRequested ? screenOpacity : 1f;
            for (GVRSceneObject object : new GVRSceneObject[] {videoObject, screenFrame}) {
                object.getRenderData().getMaterial().setOpacity(opacity);
                object.getRenderData().setAlphaBlend(cameraRequested);
                object.getRenderData().setDepthMask(object == videoObject && !cameraRequested);
            }
            screenFrame.getRenderData().setRenderingOrder(1000);
            videoObject.getRenderData().setRenderingOrder(1001);
                videoObject.getRenderData().setAlphaBlendFunc(
                    android.opengl.GLES20.GL_ONE, android.opengl.GLES20.GL_ONE_MINUS_SRC_ALPHA);
        }

        private void handleButton(android.view.KeyEvent event) {
            if (event.isCanceled()) {
                pressedPaletteItem = null;
                touchpadPressed = false;
                triggerPressed = false;
                return;
            }
            if (event.getRepeatCount() != 0) return;
            int keyCode = event.getKeyCode();
            boolean down = event.getAction() == android.view.KeyEvent.ACTION_DOWN;
            if (!down && event.getAction() != android.view.KeyEvent.ACTION_UP) return;
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                if (down && !touchpadPressed) togglePalette();
                touchpadPressed = down;
            } else if (keyCode == android.view.KeyEvent.KEYCODE_A
                    || keyCode == android.view.KeyEvent.KEYCODE_BUTTON_A
                    || keyCode == android.view.KeyEvent.KEYCODE_BUTTON_R2) {
                if (down && !triggerPressed) pressedPaletteItem = hoveredPaletteItem;
                if (!down && triggerPressed && paletteVisible
                        && pressedPaletteItem != null && pressedPaletteItem == hoveredPaletteItem) {
                    applyPaletteSelection((Integer) pressedPaletteItem.getTag());
                }
                triggerPressed = down;
                if (!down) pressedPaletteItem = null;
            } else if (keyCode == android.view.KeyEvent.KEYCODE_BACK && down && paletteVisible) {
                togglePalette();
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

        private void resetInteraction() {
            resetTouch();
            buttonEvents.clear();
            pressedPaletteItem = null;
            touchpadPressed = false;
            triggerPressed = false;
            if (paletteVisible) togglePalette();
            if (laserObject != null) laserObject.setEnable(false);
            if (laserHitDot != null) laserHitDot.setEnable(false);
        }

        private void detachController() {
            resetTouch();
            if (activeController != null) activeController.resetSceneObject();
            if (controllerPose != null && controllerPose.getParent() != null) {
                controllerPose.getParent().removeChildObject(controllerPose);
            }
            if (laserObject != null) laserObject.setEnable(false);
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
            laserMaterial.setColor(0.15f, 0.85f, 1.0f);
            laserMaterial.setLineWidth(2f);
            laser.getRenderData().setMaterial(laserMaterial);
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
            controllerPose.addChildObject(laser);
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
            float itemWidth = 0.58f;
            float itemHeight = 0.34f;
            float ringRadius = 1.34f;
            for (int index = 0; index < paletteItems.length; index++) {
                double angle = -Math.PI / 2.0 + 2.0 * Math.PI * index / paletteItems.length;
                GVRSceneObject tile = new GVRSceneObject(context, itemWidth, itemHeight);
                tile.setName("background-option-" + index);
                tile.setTag(index);
                tile.attachCollider(new GVRMeshCollider(context, true));
                GVRMaterial material = new GVRMaterial(context);
                material.setColor(index == backgroundIndex ? 0xff168aad : 0xff263744);
                tile.getRenderData().setMaterial(material);
                tile.getRenderData().setRenderingOrder(0);
                tile.getTransform().setPosition((float) Math.cos(angle) * ringRadius,
                    (float) Math.sin(angle) * ringRadius, -2.15f);
                if (index < BACKGROUND_PATHS.length) {
                    loadBackgroundThumbnail(context, index, material);
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
                    material.setMainTexture(texture);
                }
                paletteRoot.addChildObject(tile);
                paletteItems[index] = tile;
                paletteMaterials[index] = material;
            }
        }

        private void createLaserHitDot(GVRContext context) {
            laserHitDot = new GVRSphereSceneObject(context, false, 0.008f);
            GVRMaterial material = new GVRMaterial(context);
            material.setColor(0xff00e5ff);
            laserHitDot.getRenderData().setMaterial(material);
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

        private void togglePalette() {
            if (paletteRoot == null) return;
            resetTouch();
            pressedPaletteItem = null;
            paletteVisible = !paletteVisible;
            paletteRoot.setEnable(paletteVisible);
            if (!paletteVisible) {
                if (hoveredPaletteItem != null) clearPaletteHover(hoveredPaletteItem);
                if (laserHitDot != null) laserHitDot.setEnable(false);
            }
        }

        private void updatePaletteHover(GVRSceneObject item) {
            if (hoveredPaletteItem != item && hoveredPaletteItem != null) {
                int oldIndex = (Integer) hoveredPaletteItem.getTag();
                paletteMaterials[oldIndex].setColor(oldIndex == backgroundIndex
                        ? 0xff168aad : 0xff263744);
            }
            int index = (Integer) item.getTag();
            if (hoveredPaletteItem != item) {
                hoveredPaletteItem = item;
                paletteMaterials[index].setColor(0xffe07a32);
            }
        }

        private void clearPaletteHover(GVRSceneObject item) {
            if (hoveredPaletteItem != item) return;
            int index = (Integer) item.getTag();
            paletteMaterials[index].setColor(index == backgroundIndex
                    ? 0xff168aad : 0xff263744);
            hoveredPaletteItem = null;
            laserHitDot.setEnable(false);
        }

        private void applyPaletteSelection(int index) {
            if (index == BACKGROUND_PATHS.length
                    && checkSelfPermission(Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> requestPermissions(
                        new String[] {Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST));
                return;
            }
            resetTouch();
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
                        ? 0xff168aad : 0xff263744);
                paletteItems[itemIndex].getTransform().setScale(1f, 1f, 1f);
            }
            paletteVisible = false;
            paletteRoot.setEnable(false);
            hoveredPaletteItem = null;
            laserHitDot.setEnable(false);
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