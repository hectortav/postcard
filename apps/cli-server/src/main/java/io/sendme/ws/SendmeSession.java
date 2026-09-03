package io.sendme.ws;

public interface SendmeSession {
    void send(String json);
    void close(int code, String reason);
}
