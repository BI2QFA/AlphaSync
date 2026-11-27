package io.github.bi2qfa.alphasync;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.Socket;

/** Real protocol handshake and command with no saved initiator, including a server restart. */
public final class DirectConnectionTest {
    public static void main(String[] args) throws Exception {
        PtpIpServer.Handler handler = (PtpIpServer.Handler) Proxy.newProxyInstance(
            DirectConnectionTest.class.getClassLoader(), new Class[]{PtpIpServer.Handler.class},
            new InvocationHandler() {
                public Object invoke(Object proxy, Method method, Object[] arguments) {
                    String name = method.getName();
                    if (name.equals("guid")) return new byte[8];
                    if (name.equals("deviceName")) return "Test camera";
                    if (name.equals("deviceInfo")) return new byte[0];
                    if (method.getReturnType() == Boolean.TYPE) return Boolean.FALSE;
                    if (method.getReturnType() == Integer.TYPE) return Integer.valueOf(80);
                    if (method.getReturnType() == Long.TYPE) return Long.valueOf(-1);
                    if (method.getReturnType() == String.class) return "";
                    return null;
                }
            });
        PtpIpServer.PairingHandler pairing = (PtpIpServer.PairingHandler) Proxy.newProxyInstance(
            DirectConnectionTest.class.getClassLoader(), new Class[]{PtpIpServer.PairingHandler.class},
            new InvocationHandler() {
                public Object invoke(Object proxy, Method method, Object[] arguments) {
                    if (method.getName().equals("isPairingOpen")) return Boolean.TRUE;
                    if (method.getName().equals("isDevicePaired")) return Boolean.FALSE;
                    if (method.getName().equals("pairingCode") || method.getName().equals("onPaired")) {
                        throw new AssertionError("Direct connection must not request a code or save pairing");
                    }
                    return null;
                }
            });
        for (int boot = 0; boot < 2; boot++) {
            PtpIpServer server = new PtpIpServer(handler);
            server.setPairingHandler(pairing);
            server.start();
            Socket socket = new Socket("127.0.0.1", server.getProtoPort());
            try {
                socket.setSoTimeout(2000);
                byte[] guid = new byte[16];
                guid[0] = (byte)(boot + 1);
                socket.getOutputStream().write(PtpCodec.initCmdReq(guid, "Unknown phone", PtpIpServer.PROTO_VERSION));
                PtpCodec.Msg ack = PtpCodec.read(socket.getInputStream());
                check(ack != null && ack.type == PtpCodec.T_INIT_CMD_ACK, "Unknown phone rejected");
                socket.getOutputStream().write(PtpCodec.opReq(PtpCodec.DP_NONE, PtpCodec.OP_PING, 1, null));
                PtpCodec.Msg response = PtpCodec.read(socket.getInputStream());
                check(response != null && response.type == PtpCodec.T_OPERATION_RSP, "Still waiting for pairing");
                PtpCodec.Op op = PtpCodec.parseOp(response.body);
                check(op.code == PtpCodec.RC_OK && op.txId == 1, "Command failed after direct handshake");
            } finally {
                socket.close();
                server.stop();
            }
        }
        System.out.println("PASS: unknown phones connect and PING without codes or pairing records across server restart");
    }
    private static void check(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
    }
}
