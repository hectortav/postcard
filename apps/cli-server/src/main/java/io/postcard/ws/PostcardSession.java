package io.postcard.ws;

public interface PostcardSession {
    void send(String json);
    void close(int code, String reason);
}
