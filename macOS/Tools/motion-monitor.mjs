import dgram from 'node:dgram';

const socket = dgram.createSocket('udp4');
let lastPrint = 0;
let packets = 0;

socket.on('error', error => {
    console.error(error.message);
    socket.close();
    process.exitCode = 1;
});
socket.on('message', data => {
    try {
        const pose = JSON.parse(data.toString('utf8'));
        if (pose.type !== 'pose' || !Array.isArray(pose.head)) return;
        packets += 1;
        const now = Date.now();
        if (now - lastPrint < 1000) return;
        console.log(JSON.stringify({ packets, ...pose }));
        lastPrint = now;
    } catch (error) {
        console.error(`Invalid motion packet: ${error.message}`);
    }
});
socket.bind(43130, '127.0.0.1', () => {
    console.log('Listening for GearVR motion on UDP 127.0.0.1:43130');
});