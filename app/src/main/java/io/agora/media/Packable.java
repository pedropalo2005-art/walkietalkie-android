package io.agora.media;

public interface Packable {
    ByteBuf marshal(ByteBuf out);
}
