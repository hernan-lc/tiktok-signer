package io.github.nglmercer.tiktoklive;

import java.util.concurrent.CompletableFuture;

/** Connection acquisition boundary. Canceling a returned future cancels that attempt only. */
public interface ConnectionProvider extends AutoCloseable {
    CompletableFuture<ConnectResponse> connect(String uniqueId);

    /** Direct backends wait for a valid server batch before announcing a usable session. */
    default boolean requiresRoomConfirmation() {
        return false;
    }

    @Override
    void close();
}
